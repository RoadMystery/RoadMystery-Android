"""Answers made of case lines only, the app's default (Kotlin prompt/LinePrompt, docs/voice-agent/line-answers.md).

The model only picks a suspect's lines by id and the app reads the lines as the persona file has them, so an
answer holds nothing the case file does not, and a reaction to a clue not found yet is never on the list to pick.
The golden file pins this module and the Kotlin port to the same lists, prompts and choices.
"""

from __future__ import annotations

import re
from collections.abc import Iterable, Sequence
from dataclasses import dataclass

from voice_eval.content import STAGES, Case, Suspect
from voice_eval.metrics import normalize_for_match
from voice_eval.prompt import (
    bullets,
    case_overview,
    fill_template,
    pressure_line,
    require_known_clues,
    shown_facts,
    with_subject_particle,
)

CONFESSION_ID = "confession"
PRESSURE_ID = "pressure"
NOT_UNDERSTOOD_ID = "not-understood"
_DEFLECTION_PREFIX = "deflect-"
MAX_LINES = 2
"""Two lines answer a question that asks two things; more would run long in a car."""

_PUBLIC_LABEL = "처음부터 할 수 있는 말"
_PRESSURE_LABEL = "수사가 깊어진 지금, 어울릴 때만"
_CONFESSION_LABEL = "범인으로 몰릴 때"
_DEFLECTION_LABEL = "맞는 대사가 없을 때"
_NOT_UNDERSTOOD_LABEL = "무슨 말인지 전혀 알 수 없을 때"
_NO_CLUES = "- 아직 없음"
_ID_CHARS = "A-Za-z0-9_-"
"""What an id is made of: ids are matched whole, so "k-son" never matches inside "k-son-shoes"."""
_ID = re.compile(f"[{_ID_CHARS}]+")
"""What a fact id may be, so a whole-id match cannot stop at a "." or a Hangul letter inside one."""


@dataclass(frozen=True)
class Line:
    """One line a suspect may say this turn: [text] is a string of its persona file, word for word, and [id] is
    how the model names it (Kotlin SpeakableLine). The car reads [text] and nothing else."""

    id: str
    label: str
    text: str


def speakable_lines(
    case: Case, suspect: Suspect, unlocked: frozenset[str], *, stage: str = "opening", confession: bool = False
) -> tuple[Line, ...]:
    """What [suspect] may say this turn, in the order the prompt lists them: its public lines and the reactions
    the found clues unlock, less the lines those reactions take back (shown_facts); the pressure line for
    [stage], unless [confession] takes its place; the confession; its deflections; its line for a question it did
    not catch. [stage] and [confession] as for render_system_prompt."""
    if confession and (suspect.id != case.solution.culprit or not suspect.confession):
        raise ValueError(f"'{suspect.id}' cannot confess")
    if stage not in STAGES:
        raise ValueError(f"unknown stage '{stage}' (expected one of {', '.join(STAGES)})")
    require_known_clues(case, unlocked)
    public, reactions = shown_facts(suspect, unlocked)
    order = [clue.id for clue in case.clues]
    lines = [Line(fact.id, _PUBLIC_LABEL, _line_of(suspect, fact.id, fact.line)) for fact in public]
    # A reaction to several clues goes under the last of them in case order, as in the free prompt.
    for clue in case.clues:
        if clue.id not in unlocked:
            continue
        for fact in reactions:
            if max(fact.unlocked_by, key=order.index) == clue.id:
                titles = "·".join(case.clue(clue_id).title for clue_id in sorted(fact.unlocked_by, key=order.index))
                lines.append(Line(fact.id, f"{titles} 이야기에", _line_of(suspect, fact.id, fact.line)))
    pressure = None if confession else pressure_line(suspect, stage)
    if pressure:
        lines.append(Line(PRESSURE_ID, _PRESSURE_LABEL, pressure))
    if confession and suspect.confession:
        lines.append(Line(CONFESSION_ID, _CONFESSION_LABEL, suspect.confession))
    lines.extend(
        Line(f"{_DEFLECTION_PREFIX}{index}", _DEFLECTION_LABEL, text)
        for index, text in enumerate(suspect.deflections, start=1)
    )
    lines.append(Line(NOT_UNDERSTOOD_ID, _NOT_UNDERSTOOD_LABEL, suspect.fallback_not_understood))
    return tuple(lines)


def render_line_prompt(
    case: Case,
    suspect: Suspect,
    unlocked: frozenset[str],
    template: str,
    *,
    stage: str = "opening",
    confession: bool = False,
) -> str:
    """The system prompt asking the model to pick [suspect]'s lines by id, from speakable_lines."""
    lines = speakable_lines(case, suspect, unlocked, stage=stage, confession=confession)
    values = {
        "case_title": case.title,
        "name": suspect.name,
        "name_ga": with_subject_particle(suspect.name),
        "role": suspect.role,
        "confession_rule": _confession_rule(suspect) if confession else "",
        "public_facts": case_overview(case),
        "clues": _clue_section(case, unlocked),
        "lines": "\n".join(f"- {line.id} ({line.label}): {line.text}" for line in lines),
    }
    return fill_template(template, values)


def chosen_lines(reply: str, lines: Sequence[Line]) -> tuple[Line, ...]:
    """The lines [reply] names, from [lines], to be spoken in this order. Ids count in the order the reply names
    them, each once; anything else in the reply is ignored, so it is never spoken. The confession goes alone;
    otherwise up to MAX_LINES case lines, and only when there is none, the first deflection or not-caught line
    named. Empty when the reply names none."""
    if not lines:
        return ()
    by_id = {line.id.lower(): line for line in lines}
    alternatives = "|".join(re.escape(line.id) for line in sorted(lines, key=lambda line: len(line.id), reverse=True))
    pattern = re.compile(f"(?<![{_ID_CHARS}])(?:{alternatives})(?![{_ID_CHARS}])", re.IGNORECASE)
    named: list[Line] = []
    for match in pattern.finditer(reply):
        line = by_id.get(match.group(0).lower())
        if line is not None and line not in named:
            named.append(line)
    confession = next((line for line in named if line.id == CONFESSION_ID), None)
    if confession is not None:
        return (confession,)
    content = [line for line in named if not is_stock(line.id)]
    return tuple(content[:MAX_LINES]) if content else tuple(named[:1])


def heard_line_ids(suspect: Suspect, answer: str) -> tuple[str, ...]:
    """Ids of [suspect]'s lines that [answer], something it was heard to say, holds word for word (normalized
    like the spoiler guard), in the order it says them. Longer lines claim their stretch first, so a deflection
    inside a longer line is not counted twice."""
    text = normalize_for_match(answer)
    claimed: list[tuple[int, int]] = []
    found: list[tuple[int, str]] = []
    for line_id, line in sorted(_every_line(suspect), key=lambda item: len(item[1]), reverse=True):
        start = text.find(line)
        while start >= 0:
            end = start + len(line)
            if not any(begin < end and start < finish for begin, finish in claimed):
                claimed.append((start, end))
                found.append((start, line_id))
                break
            start = text.find(line, start + 1)
    ordered = [line_id for _, line_id in sorted(found, key=lambda item: item[0])]
    return tuple(dict.fromkeys(ordered))


def heard_case_lines(suspect: Suspect, answer: str) -> tuple[str, ...]:
    """The case lines (public lines, reactions, the confession) of heard_line_ids, as written: what the notebook
    reads back as the suspect's words, without deflections and the like."""
    texts = _case_line_texts(suspect)
    return tuple(texts[line_id] for line_id in heard_line_ids(suspect, answer) if line_id in texts)


def line_problems(suspects: Iterable[Suspect]) -> list[str]:
    """What keeps a case from being answered in lines: "suspect/fact" for each public line or reaction without a
    line, for each whose id has characters other than A-Z, a-z, 0-9, "_" and "-", and for each that takes an id
    the lines' own ids use. Empty when it can be."""
    problems = []
    for suspect in suspects:
        for fact in (*suspect.public, *suspect.unlockable):
            if not fact.line:
                problems.append(f"{suspect.id}/{fact.id} has no line")
            elif not _ID.fullmatch(fact.id):
                problems.append(f"{suspect.id}/{fact.id} has characters an id may not have")
            elif is_reserved(fact.id):
                problems.append(f"{suspect.id}/{fact.id} uses a reserved id")
    return problems


def is_reserved(line_id: str) -> bool:
    """An id the lines themselves use, which a public fact or reaction may not take."""
    return line_id in (CONFESSION_ID, PRESSURE_ID) or is_stock(line_id)


def is_line_id(fact_id: str) -> bool:
    """Whether [fact_id] is made only of what the model's reply is matched on: A-Z, a-z, 0-9, "_" and "-"."""
    return bool(_ID.fullmatch(fact_id))


def is_stock(line_id: str) -> bool:
    """A deflection or the line for a question not caught: what the suspect says when no case line fits."""
    return line_id == NOT_UNDERSTOOD_ID or _is_deflection(line_id)


def _is_deflection(line_id: str) -> bool:
    number = line_id.removeprefix(_DEFLECTION_PREFIX)
    return line_id.startswith(_DEFLECTION_PREFIX) and number.isascii() and number.isdigit()


def _line_of(suspect: Suspect, fact_id: str, line: str | None) -> str:
    if not line:
        raise ValueError(f"{suspect.id}/{fact_id} has no line")
    return line


def _confession_rule(suspect: Suspect) -> str:
    return (
        f"\n- 플레이어가 찾은 증거를 들이밀며 {with_subject_particle(suspect.name)} 범인이라고 몰아붙이면 "
        f"'{_CONFESSION_LABEL}' 대사를 골라라."
    )


def _clue_section(case: Case, unlocked: frozenset[str]) -> str:
    clues = [clue for clue in case.clues if clue.id in unlocked]
    return bullets(f"{clue.title}: {clue.summary}" for clue in clues) if clues else _NO_CLUES


def _case_line_texts(suspect: Suspect) -> dict[str, str]:
    """id to text of the lines [suspect] says about the case, whatever has been found."""
    texts = {fact.id: fact.line for fact in (*suspect.public, *suspect.unlockable) if fact.line}
    if suspect.confession:
        texts[CONFESSION_ID] = suspect.confession
    return texts


def _every_line(suspect: Suspect) -> list[tuple[str, str]]:
    """(id, normalized text) of every line [suspect] could have said in any turn."""
    lines = [
        *_case_line_texts(suspect).items(),
        *((PRESSURE_ID, text) for _, text in suspect.pressure),
        *((f"{_DEFLECTION_PREFIX}{index}", text) for index, text in enumerate(suspect.deflections, start=1)),
        (NOT_UNDERSTOOD_ID, suspect.fallback_not_understood),
    ]
    normalized = [(line_id, normalize_for_match(text)) for line_id, text in lines]
    return [(line_id, text) for line_id, text in normalized if text]
