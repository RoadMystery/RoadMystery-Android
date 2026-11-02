"""Render a suspect's system prompt from case content and the player's unlocked clues."""

from __future__ import annotations

import re
from collections.abc import Iterable, Mapping

from voice_eval.content import Case, Probe, Suspect, UnlockableFact, history_error
from voice_eval.metrics import phrase_hits

VARIANTS = ("cover", "truth")
"""cover: the suspect only knows its cover story. truth: the hidden truth is included
so the model can lie consistently - the harness measures whether that leaks more."""

_PLACEHOLDER = re.compile(r"\{\{(\w+)\}\}")
_HANGUL_BASE = 0xAC00
_HANGUL_LAST = 0xD7A3
_RIEUL_FINAL = 8

_FAMILY_RULE = "- 가족이 함께 듣는 이야기다. 폭력적이거나 잔인하거나 무서운 묘사는 하지 마라."
_SECRET_INTRO = (
    "아래는 네가 거짓말을 일관되게 하도록 알려 주는 배경이다. 이 내용을 인정하거나 암시하지 말고, "
    "플레이어가 아직 찾지 않은 증거를 먼저 꺼내지 마라. 추궁을 받아도 '네가 말할 수 있는 사실'의 "
    "주장을 지켜라."
)
_TOPIC_RULE = (
    "- 플레이어가 지금 꺼낸 {topics} 이야기는 '네가 말할 수 있는 사실'과 '플레이어가 찾은 단서'에 있는 "
    "만큼만 말하라. 누가 무엇을 가졌는지, 언제 어디서 무엇을 했는지 새로 보태지 말고, 나머지는 '인물'에 "
    "적힌 {name}의 성격대로 짧게 받아넘겨라."
)


def _final_consonant(word: str) -> int | None:
    """Index of the last syllable's final consonant (0 = none); None if not Hangul."""
    if not word:
        return None
    code = ord(word[-1])
    if not _HANGUL_BASE <= code <= _HANGUL_LAST:
        return None
    return (code - _HANGUL_BASE) % 28


def with_subject_particle(word: str) -> str:
    """한도윤 -> 한도윤이, 집사 -> 집사가."""
    return word + ("이" if _final_consonant(word) else "가")


def with_instrumental_particle(word: str) -> str:
    """한도윤 -> 한도윤으로, 집사 -> 집사로, 미란 -> 미란으로, 길 -> 길로."""
    final = _final_consonant(word)
    return word + ("으로" if final and final != _RIEUL_FINAL else "로")


def bullets(lines: Iterable[str]) -> str:
    return "\n".join(f"- {line}" for line in lines)


def unlocked_facts(suspect: Suspect, unlocked: frozenset[str]) -> tuple[UnlockableFact, ...]:
    """Reactions whose required clues have all been found."""
    return tuple(fact for fact in suspect.unlockable if set(fact.unlocked_by) <= unlocked)


def _unlocked_section(case: Case, suspect: Suspect, unlocked: frozenset[str]) -> str:
    clues = [clue for clue in case.clues if clue.id in unlocked]
    if not clues:
        return "- 아직 플레이어가 찾은 단서가 없다."
    lines = [f"[단서] {clue.title}: {clue.summary}" for clue in clues]
    for fact in unlocked_facts(suspect, unlocked):
        demeanor = f" (태도: {fact.demeanor})" if fact.demeanor else ""
        lines.append(f"[너의 반응] {fact.text}{demeanor}")
    return bullets(lines)


def _speech_section(suspect: Suspect) -> str:
    examples = "\n".join(f"  - {example}" for example in suspect.speech_examples)
    return "\n".join(
        [
            f"- 말투: {suspect.register}",
            bullets(suspect.speech_rules),
            f"- 예시:\n{examples}",
        ]
    )


def _relationship_section(case: Case, suspect: Suspect) -> str:
    lines = []
    for relationship in suspect.relationships:
        member = case.member(relationship.target)
        lines.append(f"{member.name}({member.role}): {relationship.attitude}")
    return bullets(lines)


def _secret_section(suspect: Suspect, variant: str) -> str:
    if variant != "truth":
        return ""
    facts = bullets(fact.text for fact in suspect.secret)
    return f"\n# 너만 아는 진실 (절대 말하지 말 것)\n{_SECRET_INTRO}\n{facts}"


def raised_topics(case: Case, unlocked: frozenset[str], question: str) -> tuple[str, ...]:
    """Keywords of clues not found yet that the question brings up, matched like the spoiler guard."""
    locked = (keyword for clue in case.clues if clue.id not in unlocked for keyword in clue.keywords)
    return phrase_hits(question, locked)


def _topic_rule(suspect: Suspect, topics: tuple[str, ...]) -> str:
    """Keeps the answer about a raised topic to the listed facts; adds nothing when none is raised.

    {{topic_rule}} sits at the end of the unfound-evidence bullet, so the rule brings its own line
    break and an empty rule leaves that line exactly as it was.
    """
    if not topics:
        return ""
    quoted = ", ".join(f"'{topic}'" for topic in topics)
    return "\n" + _TOPIC_RULE.format(topics=quoted, name=suspect.name)


def render_system_prompt(
    case: Case,
    suspect: Suspect,
    unlocked: frozenset[str],
    variant: str,
    template: str,
    *,
    topics: tuple[str, ...] = (),
) -> str:
    if variant not in VARIANTS:
        raise ValueError(f"unknown variant '{variant}' (expected one of {', '.join(VARIANTS)})")
    unknown = sorted(unlocked - {clue.id for clue in case.clues})
    if unknown:
        raise ValueError(f"unknown clue id(s): {', '.join(unknown)}")

    values = {
        "case_title": case.title,
        "name": suspect.name,
        "name_ga": with_subject_particle(suspect.name),
        "name_ro": with_instrumental_particle(suspect.name),
        "role": suspect.role,
        "not_understood": suspect.fallback_not_understood,
        "theme_rule": theme_rule(case),
        "topic_rule": _topic_rule(suspect, topics),
        "public_facts": case_overview(case),
        "profile": bullets(
            [f"{suspect.age}세. {suspect.background}", *suspect.personality]
        ),
        "speech_style": _speech_section(suspect),
        "public_knowledge": bullets(fact.text for fact in suspect.public),
        "unlocked_knowledge": _unlocked_section(case, suspect, unlocked),
        "relationships": _relationship_section(case, suspect),
        "deflections": bullets(suspect.deflections),
        "secret_section": _secret_section(suspect, variant),
    }
    return fill_template(template, values)


def fill_template(template: str, values: Mapping[str, str]) -> str:
    """Replace {{name}} placeholders; an unknown placeholder is an error, never left in place."""

    def substitute(match: re.Match[str]) -> str:
        key = match.group(1)
        if key not in values:
            raise ValueError(f"template uses unknown placeholder '{{{{{key}}}}}'")
        return values[key]

    return _PLACEHOLDER.sub(substitute, template).strip() + "\n"


def theme_rule(case: Case) -> str:
    return _FAMILY_RULE if case.theme == "family" else ""


def case_overview(case: Case) -> str:
    return f"{case.setting}\n{bullets(case.public_facts)}"


def build_messages(probe: Probe) -> list[dict[str, str]]:
    """Prior turns (alternating, starting with the player) plus the probe question."""
    problem = history_error(probe.history)
    if problem is not None:
        raise ValueError(f"probe '{probe.id}': {problem}")
    return [*(dict(turn) for turn in probe.history), {"role": "user", "content": probe.question}]
