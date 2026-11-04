"""Deterministic checks on a spoken answer and streaming latency measurement."""

from __future__ import annotations

import math
import re
import unicodedata
from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass
from itertools import takewhile

from voice_eval.content import Case

MAX_SENTENCES = 3
OPENER_MAX_LETTERS = 7
"""A sentence this short ("하, 참.", "맨발이요?", "잠시만요, 탐정님.") opens an answer rather than being one."""
MAX_FREE_OPENERS = 2
MIN_HANGUL_RATIO = 0.8

# A single terminal mark; ellipses ("음...", "…") are hesitation, not sentence ends.
_SENTENCE_END = re.compile(r"(?<![.…])[.?!。？！](?![.…])(?=\s|$)")
# While streaming, "약 3." may continue as "약 3.5초", so only a mark followed by whitespace counts.
_CONFIRMED_SENTENCE_END = re.compile(r"(?<![.…])[.?!。？！](?![.…])(?=\s)")
_IGNORED_CATEGORIES = frozenset("PSZC")
"""Unicode punctuation, symbols, separators and control/format characters."""
_FORMAT_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("markdown", re.compile(r"[*_#`|]")),
    ("bracket", re.compile(r"[()\[\]{}<>「」『』【】]")),
    ("quote", re.compile(r"[\"“”‘’]")),
    ("emoji", re.compile("[\U0001F300-\U0001FAFF☀-➿]")),
    ("list", re.compile(r"(?:^|\n)\s*(?:[-•]|\d+\.)\s")),
)


def _is_hangul(char: str) -> bool:
    code = ord(char)
    return 0xAC00 <= code <= 0xD7A3 or 0x1100 <= code <= 0x11FF or 0x3130 <= code <= 0x318F


def first_sentence_end(text: str) -> int | None:
    """Index just past the first sentence-ending punctuation, or None."""
    match = _SENTENCE_END.search(text)
    return match.end() if match else None


def split_sentences(text: str) -> tuple[str, ...]:
    sentences = []
    start = 0
    for match in _SENTENCE_END.finditer(text):
        sentence = text[start : match.end()].strip()
        if sentence:
            sentences.append(sentence)
        start = match.end()
    remainder = text[start:].strip()
    if remainder:
        sentences.append(remainder)
    return tuple(sentences)


def counted_sentences(sentences: Sequence[str]) -> int:
    """How many sentences count toward the answer's sentence limit, as the app counts them
    (Kotlin text/Sentences.kt). Up to MAX_FREE_OPENERS short openers at the start are free, so an
    interjection or an echoed question does not push the last sentence, often the denial, out."""
    free = sum(1 for _ in takewhile(_is_short_opener, sentences[:MAX_FREE_OPENERS]))
    return len(sentences) - free


def _is_short_opener(sentence: str) -> bool:
    return sum(1 for char in sentence if char.isalpha()) <= OPENER_MAX_LETTERS


READOUT_BRACKETS = "[]()（）【】"
"""Suspects only speak, so brackets in an answer hold a stage direction or a rule label."""
_READOUT_TRAILING = " .!?。？！…~\"'”’」』"
_SUBJECT_PARTICLES = "이가은는"
_HANGUL_FIRST, _HANGUL_COUNT, _FINALS, _FINAL_BIEUP = 0xAC00, 11172, 28, 17


def looks_like_readout(sentence: str, speaker: str = "") -> bool:
    """A sentence no suspect would say aloud, as the app judges it (Kotlin text/Sentences.kt): a
    bracketed stage direction or label, the speaker's own name as the subject ("윤서진이 아는 사실에
    없는 질문이라 …"), or the plain '-다' of instructions ("윤하린은 이 말을 처음 듣는다."). Suspects
    speak 해요체 or 하십시오체, so '-ㅂ니다' and the propositive '-ㅂ시다' are speech."""
    text = sentence.strip()
    if any(char in READOUT_BRACKETS for char in text):
        return True
    if speaker and _names_speaker(text, speaker):
        return True
    core = text.rstrip(_READOUT_TRAILING)
    return core.endswith("다") and not _is_polite_ending(core)


def _names_speaker(text: str, speaker: str) -> bool:
    """Suspects say 저 or 제가, so their full name and a subject particle is the prompt talking about
    them. '윤서진이에요' and '윤서진입니다' introduce the speaker: no space follows the particle."""
    if not text.startswith(speaker):
        return False
    rest = text[len(speaker) :]
    return len(rest) >= 2 and rest[0] in _SUBJECT_PARTICLES and rest[1].isspace()


def _is_polite_ending(core: str) -> bool:
    """'-습니다', '-합니다', '-합시다': the syllable before 니다 or 시다 ends in ㅂ, unlike '아니다'."""
    if len(core) < 3 or not core.endswith(("니다", "시다")):
        return False
    syllable = ord(core[-3]) - _HANGUL_FIRST
    return 0 <= syllable < _HANGUL_COUNT and syllable % _FINALS == _FINAL_BIEUP


_STAGE_DIRECTION = re.compile(r"\[[^\]]*\]|\([^)]*\)|（[^）]*）|【[^】]*】|\*")
""""[짧게]", "(sigh)", "（한숨）", "【속마음】" and markdown asterisks around them."""


def strip_stage_directions(sentence: str) -> str:
    """The sentence as the app speaks it (Kotlin text/Sentences.kt): bracketed stage directions and
    asterisks removed and whitespace collapsed, or "" if no word is left. Unchanged if it has none,
    so "[짧게] 어머, 탐정님." is spoken as "어머, 탐정님." before looks_like_readout judges it."""
    if not _STAGE_DIRECTION.search(sentence):
        return sentence
    rest = " ".join(_STAGE_DIRECTION.sub(" ", sentence).split())
    return rest if any(char.isalnum() for char in rest) else ""


def hangul_ratio(text: str) -> float:
    letters = [char for char in text if char.isalpha()]
    if not letters:
        return 0.0
    return sum(1 for char in letters if _is_hangul(char)) / len(letters)


def format_violations(text: str) -> tuple[str, ...]:
    """Categories of markup that a TTS engine would read aloud or choke on."""
    return tuple(name for name, pattern in _FORMAT_PATTERNS if pattern.search(text))


def normalize_for_match(text: str) -> str:
    """NFKC and lowercase, keeping only letters, digits and marks, so a forbidden phrase still
    matches through spacing, punctuation ("범인은… 집사입니다", "동·백"), zero-width characters or
    decomposed Hangul. The Kotlin guard (text/PythonText.kt) mirrors this, checked by the golden file."""
    folded = unicodedata.normalize("NFKC", text).lower()
    return "".join(char for char in folded if unicodedata.category(char)[0] not in _IGNORED_CATEGORIES)


def phrase_hits(text: str, phrases: Iterable[str]) -> tuple[str, ...]:
    normalized = normalize_for_match(text)
    return tuple(
        phrase for phrase in phrases
        if (needle := normalize_for_match(phrase)) and needle in normalized
    )


def percentile(values: Sequence[float], pct: float) -> float | None:
    """Nearest-rank percentile; None for an empty sequence."""
    if not values:
        return None
    ordered = sorted(values)
    # round() guards against float error such as 7 / 100 * 100 == 7.000000000000001.
    rank = max(1, math.ceil(round(pct * len(ordered) / 100, 9)))
    return ordered[rank - 1]


@dataclass(frozen=True)
class Timing:
    ttft_ms: float | None
    ttfs_ms: float | None
    total_ms: float


class StreamTimer:
    """Records when the first text and the first full sentence arrive.

    The first sentence is what the car can hand to TTS, so time-to-first-sentence
    approximates when the suspect starts talking. A sentence counts once the text after
    its final mark has started (or the stream has ended), as a live splitter would see it.
    """

    def __init__(self, start: float, clock: Callable[[], float]) -> None:
        self._start = start
        self._clock = clock
        self._parts: list[str] = []
        self._first_token: float | None = None
        self._first_sentence: float | None = None

    @property
    def text(self) -> str:
        return "".join(self._parts)

    def on_text(self, chunk: str) -> None:
        now = self._clock()
        if not chunk:
            return
        self._parts.append(chunk)
        if self._first_token is None:
            self._first_token = now
        if self._first_sentence is None and _CONFIRMED_SENTENCE_END.search(self.text):
            self._first_sentence = now

    def finish(self) -> Timing:
        end = self._clock()
        has_text = bool(self.text.strip())
        first_sentence = self._first_sentence if self._first_sentence is not None else end
        return Timing(
            ttft_ms=self._ms(self._first_token) if self._first_token is not None else None,
            ttfs_ms=self._ms(first_sentence) if has_text else None,
            total_ms=self._ms(end),
        )

    def _ms(self, moment: float) -> float:
        return (moment - self._start) * 1000.0


@dataclass(frozen=True)
class AnswerChecks:
    sentence_count: int
    # The app would cut the answer: more than MAX_SENTENCES sentences once short openers are left out.
    over_three_sentences: bool
    char_count: int
    hangul_ratio: float
    low_hangul: bool
    format_violations: tuple[str, ...]
    forbidden_hits: tuple[str, ...]
    # Some sentence looks_like_readout as the model wrote it. The app drops stage directions first,
    # so "[짧게] 어머, 탐정님." is still spoken without them; it stops at the rest.
    reads_instructions: bool


def check_answer(answer: str, case: Case, speaker: str = "") -> AnswerChecks:
    sentences = split_sentences(answer)
    ratio = hangul_ratio(answer)
    return AnswerChecks(
        sentence_count=len(sentences),
        over_three_sentences=counted_sentences(sentences) > MAX_SENTENCES,
        char_count=len(answer),
        hangul_ratio=round(ratio, 3),
        low_hangul=bool(answer.strip()) and ratio < MIN_HANGUL_RATIO,
        format_violations=format_violations(answer),
        forbidden_hits=phrase_hits(answer, case.forbidden_phrases),
        reads_instructions=any(looks_like_readout(sentence, speaker) for sentence in sentences),
    )
