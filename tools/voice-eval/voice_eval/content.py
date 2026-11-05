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
        "secret_pressure",
    }
)
PROBE_KEYS = frozenset({"id", "category", "unlocked", "question", "history", "stage"})
"""What a probe question may hold; anything else, such as a misspelt "stage", is an error rather than
silently dropped."""
STAGES = ("opening", "gathering", "pressing", "ready")
"""수사 진척도, never shown to the player (Kotlin content.InvestigationStage): no clue found, some
found, all found with a key reaction still to draw out, everything drawn out. Prompts change only
from "pressing" on, through a persona's pressure lines."""


class ContentError(ValueError):
    """Content file is malformed or references something that does not exist."""


@dataclass(frozen=True)
class Clue:
    id: str
    title: str
    summary: str
    chapter: int
    keywords: tuple[str, ...]
    hidden_facts: tuple[str, ...] = ()
    """What the clue gives away, one fact a sentence, none of it public. Empty: only the summary."""
    hint: str | None = None
    """What the app's narrator says while this clue is not found and no key reaction is within reach:
    for a leak the spoiler guard cut (the hint of the clue whose keyword leaked, else of the first
    unfound clue that has one) or after a question asking for the culprit or the solution. With no
    hint on any unfound clue, only a turn cut at its first sentence gets a deflection instead. Never
    sent to the LLM."""
    mentions: tuple[str, ...] = ()
    """Words besides the keywords and title by which a question brings the clue up; the app counts
    confrontations with them. Never sent to the LLM."""


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
    hidden_facts: tuple[str, ...] = ()
    """The solution split into facts that leave out what is public, such as the culprit's alibi."""


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
    spoiler_hint: str | None = None
    """The narrator's hint (spoilerGuard.hint) for a leak the spoiler guard cut or after a question
    asking for the culprit or the solution, used only once every clue is found and no key reaction is
    left to point the player to."""

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
    """[text] is the answer key the judge and the sentence check read; [line], when the persona has
    one, is the same claim as the suspect says it, and the prompt shows that instead."""

    id: str
    text: str
    line: str | None = None

    @property
    def prompt_text(self) -> str:
        return self.line or self.text


@dataclass(frozen=True)
class UnlockableFact:
    """A reaction to found clues; [text] and [line] as in Fact."""

    id: str
    unlocked_by: tuple[str, ...]
    text: str
    demeanor: str | None
    line: str | None = None
    key: bool = False
    """Needed to solve the case: drawing out every key reaction makes the stage "ready"."""
    replaces: tuple[str, ...] = ()
    """Ids of the suspect's public facts and earlier reactions this reaction supersedes: once it is
    unlocked the prompt stops showing them, so a cover story and the confession that ends it do not
    sit side by side. The judge still reads every answer key."""

    @property
    def prompt_text(self) -> str:
        return self.line or self.text


@dataclass(frozen=True)
class BeforeFound:
    """How the suspect answers a question about [clue] while the player has not found it."""

    clue: str
    text: str


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
    before_found: tuple[BeforeFound, ...] = ()
    pressure: tuple[tuple[str, str], ...] = ()
    """(stage, first-person line) pairs for "pressing" and "ready", in STAGES order."""


@dataclass(frozen=True)
class Probe:
    id: str
    category: str
    unlocked: tuple[str, ...]
    question: str
    history: tuple[dict[str, str], ...] = ()
    stage: str = "opening"
    """The investigation stage the question is asked at; it only matters for pressure lines."""


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
            hidden_facts=tuple(item.get("hiddenFacts", ())),
            hint=item.get("hint"),
            mentions=tuple(item.get("mentions", ())),
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
            hidden_facts=tuple(solution.get("hiddenFacts", ())),
        ),
        forbidden_phrases=tuple(data["spoilerGuard"]["forbiddenPhrases"]),
        schema_dir=resolved_schema_dir,
        spoiler_hint=data["spoilerGuard"].get("hint"),
    )


def _facts(items: list[dict[str, str]]) -> tuple[Fact, ...]:
    return tuple(Fact(id=item["id"], text=item["text"], line=item.get("line")) for item in items)


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
            line=item.get("line"),
            key=item.get("key", False),
            replaces=tuple(item.get("replaces", ())),
        )
        for item in knowledge["unlockable"]
    )
    for fact in unlockable:
        for clue_id in fact.unlocked_by:
            _require(
                clue_id in clue_ids,
                f"{path}: knowledge '{fact.id}' is unlocked by unknown clue '{clue_id}'",
            )
    before_found = tuple(
        BeforeFound(clue=item["clue"], text=item["text"]) for item in knowledge.get("beforeFound", ())
    )
    for line in before_found:
        _require(line.clue in clue_ids, f"{path}: beforeFound refers to unknown clue '{line.clue}'")
    _unique([line.clue for line in before_found], "beforeFound clue", path)

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
    _check_replaces(path, public, unlockable)

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
        before_found=before_found,
        pressure=tuple(
            (stage, data["pressure"][stage]) for stage in STAGES if stage in data.get("pressure", {})
        ),
    )


def _check_replaces(path: Path, public: tuple[Fact, ...], unlockable: tuple[UnlockableFact, ...]) -> None:
    """A reaction hides only lines the prompt already shows when it unlocks: the suspect's public
    facts and its reactions to a subset of the same clues. A key reaction is never hidden, because
    the investigation counts it as something to draw out."""
    public_ids = {fact.id for fact in public}
    reactions = {fact.id: fact for fact in unlockable}
    for fact in unlockable:
        for target in fact.replaces:
            _require(target != fact.id, f"{path}: knowledge '{fact.id}' replaces itself")
            _require(
                target in public_ids or target in reactions,
                f"{path}: knowledge '{fact.id}' replaces unknown fact '{target}'",
            )
            replaced = reactions.get(target)
            if replaced is None:
                continue
            _require(not replaced.key, f"{path}: knowledge '{fact.id}' replaces key reaction '{target}'")
            _require(
                set(replaced.unlocked_by) <= set(fact.unlocked_by),
                f"{path}: knowledge '{fact.id}' replaces '{target}', which needs clues '{fact.id}' does not",
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
        unknown_keys = sorted(set(item) - PROBE_KEYS)
        _require(
            not unknown_keys,
            f"{path}: probe '{item.get('id')}' has unknown key(s): "
            + ", ".join(f"'{key}'" for key in unknown_keys),
        )
        probe = Probe(
            id=item["id"],
            category=item["category"],
            unlocked=tuple(item.get("unlocked", ())),
            question=item["question"],
            history=tuple(
                {"role": turn["role"], "content": turn["content"]}
                for turn in item.get("history", ())
            ),
            stage=item.get("stage", "opening"),
        )
        _require(
            probe.category in PROBE_CATEGORIES,
            f"{path}: probe '{probe.id}' has unknown category '{probe.category}'",
        )
        _require(probe.stage in STAGES, f"{path}: probe '{probe.id}' has unknown stage '{probe.stage}'")
        # The app reaches these stages only once every clue is found; anything else measures a prompt
        # it never sends.
        _require(
            probe.stage not in ("pressing", "ready") or set(probe.unlocked) == clue_ids,
            f"{path}: probe '{probe.id}' is asked at stage '{probe.stage}' without every clue unlocked",
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
