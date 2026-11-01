"""Load and cross-check scenario content (case, suspect personas, probe questions)."""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator

KOREAN_SPEAKERS = frozenset({"seoyoon", "eunwoo", "haeun", "sanghyeok"})
PROBE_CATEGORIES = frozenset(
    {
        "in_scope",
        "clue_reaction",
        "locked_clue",
        "spoiler",
        "jailbreak",
        "off_topic",
        "stt_noise",
        "multi_turn",
        "content_safety",
    }
)


class ContentError(ValueError):
    """Content file is malformed or references something that does not exist."""


@dataclass(frozen=True)
class Clue:
    id: str
    title: str
    summary: str
    chapter: int
    keywords: tuple[str, ...]


@dataclass(frozen=True)
class CastMember:
    id: str
    name: str
    role: str
    is_suspect: bool
    aliases: tuple[str, ...]
    public_description: str


@dataclass(frozen=True)
class Solution:
    culprit: str
    method: str
    motive: str
    hiding_place: str


@dataclass(frozen=True)
class Case:
    id: str
    title: str
    theme: str
    placeholder: bool
    setting: str
    public_facts: tuple[str, ...]
    cast: tuple[CastMember, ...]
    clues: tuple[Clue, ...]
    solution: Solution
    forbidden_phrases: tuple[str, ...]
    schema_dir: Path = field(compare=False)

    def clue(self, clue_id: str) -> Clue:
        for clue in self.clues:
            if clue.id == clue_id:
                return clue
        raise KeyError(clue_id)

    def member(self, member_id: str) -> CastMember:
        for member in self.cast:
            if member.id == member_id:
                return member
        raise KeyError(member_id)


@dataclass(frozen=True)
class Fact:
    id: str
    text: str


@dataclass(frozen=True)
class UnlockableFact:
    id: str
    unlocked_by: tuple[str, ...]
    text: str
    demeanor: str | None


@dataclass(frozen=True)
class Relationship:
    target: str
    attitude: str


@dataclass(frozen=True)
class Suspect:
    id: str
    name: str
    role: str
    pleos_speaker: str
    age: int
    background: str
    personality: tuple[str, ...]
    register: str
    speech_rules: tuple[str, ...]
    speech_examples: tuple[str, ...]
    public: tuple[Fact, ...]
    unlockable: tuple[UnlockableFact, ...]
    secret: tuple[Fact, ...]
    relationships: tuple[Relationship, ...]
    deflections: tuple[str, ...]
    fallback_offline: str
    fallback_not_understood: str
    filler: str


@dataclass(frozen=True)
class Probe:
    id: str
    category: str
    unlocked: tuple[str, ...]
    question: str
    history: tuple[dict[str, str], ...] = ()


def _read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ContentError(f"{path}: 파일이 없습니다") from exc
    except json.JSONDecodeError as exc:
        raise ContentError(f"{path}: JSON 형식 오류 ({exc})") from exc


def _validate(data: Any, schema_path: Path, source: Path) -> None:
    schema = _read_json(schema_path)
    errors = sorted(
        Draft202012Validator(schema).iter_errors(data), key=lambda error: list(error.path)
    )
    if errors:
        details = "; ".join(
            f"{'/'.join(str(part) for part in error.path) or '(root)'}: {error.message}"
            for error in errors
        )
        raise ContentError(f"{source}: {details}")


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ContentError(message)


def _unique(ids: list[str], what: str, source: Path) -> None:
    seen: set[str] = set()
    for item_id in ids:
        _require(item_id not in seen, f"{source}: duplicate {what} id '{item_id}'")
        seen.add(item_id)


def load_case(case_dir: Path, schema_dir: Path | None = None) -> Case:
    """Load ``case_dir/case.json``. Schemas default to ``<content>/schema``."""
    resolved_schema_dir = schema_dir or case_dir.parent.parent / "schema"
    source = case_dir / "case.json"
    data = _read_json(source)
    _validate(data, resolved_schema_dir / "case.schema.json", source)

    cast = tuple(
        CastMember(
            id=item["id"],
            name=item["name"],
            role=item["role"],
            is_suspect=item["isSuspect"],
            aliases=tuple(item["aliases"]),
            public_description=item["publicDescription"],
        )
        for item in data["cast"]
    )
    clues = tuple(
        Clue(
            id=item["id"],
            title=item["title"],
            summary=item["summary"],
            chapter=item["chapter"],
            keywords=tuple(item["keywords"]),
        )
        for item in data["clues"]
    )
    _unique([member.id for member in cast], "cast", source)
    _unique([clue.id for clue in clues], "clue", source)

    solution = data["solution"]
    cast_ids = {member.id for member in cast}
    _require(
        solution["culprit"] in cast_ids,
        f"{source}: solution.culprit '{solution['culprit']}' is not in cast",
    )

    return Case(
        id=data["id"],
        title=data["title"],
        theme=data["theme"],
        placeholder=data.get("placeholder", False),
        setting=data["setting"],
        public_facts=tuple(data["publicFacts"]),
        cast=cast,
        clues=clues,
        solution=Solution(
            culprit=solution["culprit"],
            method=solution["method"],
            motive=solution["motive"],
            hiding_place=solution["hidingPlace"],
        ),
        forbidden_phrases=tuple(data["spoilerGuard"]["forbiddenPhrases"]),
        schema_dir=resolved_schema_dir,
    )


def _facts(items: list[dict[str, str]]) -> tuple[Fact, ...]:
    return tuple(Fact(id=item["id"], text=item["text"]) for item in items)


def load_suspect(path: Path, case: Case) -> Suspect:
    """Load a suspect persona and check every reference against ``case``."""
    data = _read_json(path)
    _validate(data, case.schema_dir / "suspect.schema.json", path)

    suspect_id = data["id"]
    try:
        member = case.member(suspect_id)
    except KeyError as exc:
        raise ContentError(f"{path}: id '{suspect_id}' is not in the case cast") from exc
    _require(member.is_suspect, f"{path}: cast member '{suspect_id}' is not a suspect")

    clue_ids = {clue.id for clue in case.clues}
    cast_ids = {cast_member.id for cast_member in case.cast}
    knowledge = data["knowledge"]
    unlockable = tuple(
        UnlockableFact(
            id=item["id"],
            unlocked_by=tuple(item["unlockedBy"]),
            text=item["text"],
            demeanor=item.get("demeanor"),
        )
        for item in knowledge["unlockable"]
    )
    for fact in unlockable:
        for clue_id in fact.unlocked_by:
            _require(
                clue_id in clue_ids,
                f"{path}: knowledge '{fact.id}' is unlocked by unknown clue '{clue_id}'",
            )

    relationships = tuple(
        Relationship(target=item["target"], attitude=item["attitude"])
        for item in data["relationships"]
    )
    for relationship in relationships:
        _require(
            relationship.target in cast_ids,
            f"{path}: relationship target '{relationship.target}' is not in the case cast",
        )

    public = _facts(knowledge["public"])
    secret = _facts(knowledge["secret"])
    _unique(
        [fact.id for fact in (*public, *unlockable, *secret)], "knowledge", path
    )

    profile = data["profile"]
    style = data["speechStyle"]
    fallback = data["fallbackLines"]
    return Suspect(
        id=suspect_id,
        name=member.name,
        role=member.role,
        pleos_speaker=data["voice"]["pleosSpeaker"],
        age=profile["age"],
        background=profile["background"],
        personality=tuple(profile["personality"]),
        register=style["register"],
        speech_rules=tuple(style["rules"]),
        speech_examples=tuple(style["examples"]),
        public=public,
        unlockable=unlockable,
        secret=secret,
        relationships=relationships,
        deflections=tuple(data["deflections"]),
        fallback_offline=fallback["offline"],
        fallback_not_understood=fallback["notUnderstood"],
        filler=fallback["filler"],
    )


def history_error(history: Sequence[Mapping[str, str]]) -> str | None:
    """Why a probe's prior turns are unusable, or None.

    Turns alternate player/suspect starting with the player and end with the suspect,
    because the probe question is appended as the next player turn.
    """
    for index, turn in enumerate(history):
        expected = "user" if index % 2 == 0 else "assistant"
        if turn.get("role") != expected:
            return f"history turn {index} should be '{expected}'"
    if history and history[-1].get("role") != "assistant":
        return "history must end with an assistant turn"
    return None


def load_probes(path: Path, case: Case) -> tuple[Probe, ...]:
    """Load probe questions for one suspect and check clue references."""
    data = _read_json(path)
    _require(isinstance(data, dict) and "probes" in data, f"{path}: 'probes' 목록이 없습니다")

    clue_ids = {clue.id for clue in case.clues}
    probes = []
    for item in data["probes"]:
        probe = Probe(
            id=item["id"],
            category=item["category"],
            unlocked=tuple(item.get("unlocked", ())),
            question=item["question"],
            history=tuple(
                {"role": turn["role"], "content": turn["content"]}
                for turn in item.get("history", ())
            ),
        )
        _require(
            probe.category in PROBE_CATEGORIES,
            f"{path}: probe '{probe.id}' has unknown category '{probe.category}'",
        )
        for clue_id in probe.unlocked:
            _require(
                clue_id in clue_ids,
                f"{path}: probe '{probe.id}' unlocks unknown clue '{clue_id}'",
            )
        problem = history_error(probe.history)
        _require(problem is None, f"{path}: probe '{probe.id}': {problem}")
        probes.append(probe)

    _unique([probe.id for probe in probes], "probe", path)
    return tuple(probes)
