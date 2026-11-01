"""Pick which suspect a spoken question is addressed to (F-05 '용의자 판별').

Order of precedence:
1. An explicit addressee - a name followed by 한테/에게/께 ("집사한테 물어봐") - switches suspect.
   A jamo-level fuzzy match covers STT errors in that position ("지브사한테" -> 집사).
2. Otherwise the current suspect (``default``) is kept. Merely mentioning someone ("서진 씨 코트에서
   영수증이 나왔어요") is a question *about* them, and STT output has no commas to mark a vocative.
3. With no current suspect, the first suspect mentioned by name is used.

Names must start a word and be followed only by an honorific and/or particle, so "집사람이"
or "요리 사진" do not match. Fuzzy matching is limited to the addressee position, where a
person's name is expected; elsewhere two-syllable names collide with ordinary words (사진/서진).
Kept rule-based so it runs on-device without an LLM call.
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from dataclasses import dataclass
from difflib import SequenceMatcher

from voice_eval.content import Case

FUZZY_THRESHOLD = 0.85
_HONORIFICS = ("", "씨", "님")
_ADDRESSEE_PARTICLES = ("한테", "에게", "께")
_TRAILING = ("", "는", "도", "만", "요")
_MENTION_PARTICLES = (
    "", "은", "는", "이", "가", "을", "를", "의", "도", "만", "랑", "이랑", "하고",
    "와", "과", "에", "요", "야", "아", "께서", "한테서", "에게서",
)
_MENTION_SUFFIXES = frozenset(p + t for p in _MENTION_PARTICLES for t in _TRAILING)
_ADDRESSEE_SUFFIXES = frozenset(p + t for p in _ADDRESSEE_PARTICLES for t in _TRAILING)
_HANGUL_BASE = 0xAC00
_HANGUL_LAST = 0xD7A3
_CHOSEONG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
_JUNGSEONG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
_JONGSEONG = (
    "", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ", "ㄾ",
    "ㄿ", "ㅀ", "ㅁ", "ㅂ", "ㅄ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ",
)
_NON_WORD = re.compile(r"[^\w]+")

Aliases = Mapping[str, tuple[str, ...]]


@dataclass(frozen=True)
class _Hit:
    token_index: int
    alias_length: int
    suspect_id: str
    addressed: bool


def decompose_jamo(text: str) -> str:
    parts = []
    for char in text:
        code = ord(char)
        if _HANGUL_BASE <= code <= _HANGUL_LAST:
            index = code - _HANGUL_BASE
            parts.append(_CHOSEONG[index // 588])
            parts.append(_JUNGSEONG[(index % 588) // 28])
            parts.append(_JONGSEONG[index % 28])
        else:
            parts.append(char)
    return "".join(parts)


def suspect_aliases(case: Case) -> dict[str, tuple[str, ...]]:
    return {member.id: member.aliases for member in case.cast if member.is_suspect}


def _tokens(utterance: str) -> list[str]:
    return [token for token in _NON_WORD.split(utterance) if token]


def _suffix_kind(rest: str) -> str | None:
    """'addressed', 'mention', or None when ``rest`` is not honorific + particle (집사+'람이')."""
    for honorific in _HONORIFICS:
        if not rest.startswith(honorific):
            continue
        tail = rest[len(honorific) :]
        if tail in _ADDRESSEE_SUFFIXES:
            return "addressed"
        if tail in _MENTION_SUFFIXES:
            return "mention"
    return None


def _match_at(tokens: list[str], index: int, alias: str) -> str | None:
    parts = alias.split()
    end = index + len(parts) - 1
    is_multi_word = len(parts) > 1 and end < len(tokens) and tokens[index:end] == parts[:-1]
    if is_multi_word and tokens[end].startswith(parts[-1]):
        kind = _suffix_kind(tokens[end][len(parts[-1]) :])
        if kind is not None:
            return kind
    compact = "".join(parts)  # STT often drops the space: "서진씨한테", "박여사한테"
    if tokens[index].startswith(compact):
        return _suffix_kind(tokens[index][len(compact) :])
    return None


def _hits(tokens: list[str], aliases: Aliases) -> list[_Hit]:
    hits = []
    for suspect_id, names in aliases.items():
        for name in names:
            for index in range(len(tokens)):
                kind = _match_at(tokens, index, name)
                if kind is not None:
                    hits.append(_Hit(index, len(name), suspect_id, kind == "addressed"))
    return hits


def _earliest(hits: list[_Hit]) -> str | None:
    if not hits:
        return None
    return min(hits, key=lambda hit: (hit.token_index, -hit.alias_length)).suspect_id


def _addressee_stem(token: str) -> str | None:
    for suffix in sorted(_ADDRESSEE_SUFFIXES, key=len, reverse=True):
        if token.endswith(suffix) and len(token) > len(suffix):
            stem = token[: -len(suffix)]
            for honorific in _HONORIFICS[1:]:
                if stem.endswith(honorific) and len(stem) > len(honorific):
                    return stem[: -len(honorific)]
            return stem
    return None


def _windows(token: str, size: int) -> list[str]:
    sizes = {max(2, size - 1), size, size + 1}
    return [
        token[start : start + width]
        for width in sorted(sizes)
        for start in range(max(1, len(token) - width + 1))
    ]


def _fuzzy_addressee(tokens: list[str], aliases: Aliases) -> str | None:
    stems = [stem for stem in map(_addressee_stem, tokens) if stem]
    best_score = 0.0
    best_id: str | None = None
    for suspect_id, names in aliases.items():
        for name in names:
            alias = "".join(name.split())
            if len(alias) < 2:
                continue
            alias_jamo = decompose_jamo(alias)
            for stem in stems:
                for window in _windows(stem, len(alias)):
                    score = SequenceMatcher(None, alias_jamo, decompose_jamo(window)).ratio()
                    if score > best_score:
                        best_score, best_id = score, suspect_id
    return best_id if best_score >= FUZZY_THRESHOLD else None


def identify_suspect(
    utterance: str,
    aliases: Aliases,
    default: str | None = None,
) -> str | None:
    """Suspect id the utterance is addressed to; ``default`` is the suspect currently being asked."""
    tokens = _tokens(utterance)
    hits = _hits(tokens, aliases)
    addressed = _earliest([hit for hit in hits if hit.addressed]) or _fuzzy_addressee(
        tokens, aliases
    )
    if addressed is not None:
        return addressed
    if default is not None:
        return default
    return _earliest(hits)
