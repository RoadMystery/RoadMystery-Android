"""F-06 수사 수첩 요약: prompt, empty-notebook fallback, deterministic checks, and judge."""

from __future__ import annotations

import json
from collections.abc import Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

import anthropic

from voice_eval.content import Case, ContentError
from voice_eval.judge import (
    DEFAULT_JUDGE_EFFORT,
    DEFAULT_JUDGE_MODEL,
    JudgeError,
    request_structured_json,
    require_bool,
    require_score,
)
from voice_eval.metrics import format_violations, normalize_for_match, phrase_hits, split_sentences
from voice_eval.prompt import bullets, case_overview, fill_template, theme_rule

MAX_SUMMARY_SENTENCES = 5
"""About 30 seconds of speech (F-06 output condition)."""
MAX_TESTIMONIES = 12
"""Only the most recent testimonies go into the prompt, so it stays short and cheap."""
SUMMARY_REQUEST = "지금까지 정리해줘"
EMPTY_NOTEBOOK_PREFIX = "아직 수사 수첩이 비어 있어요. 사건을 다시 들려드릴게요."
_NOTHING_YET = "- 아직 없음"


@dataclass(frozen=True)
class Testimony:
    suspect: str
    question: str
    answer: str


@dataclass(frozen=True)
class SummaryProbe:
    id: str
    unlocked: tuple[str, ...]
    testimonies: tuple[Testimony, ...]

    @property
    def is_empty(self) -> bool:
        return not self.unlocked and not self.testimonies


def load_summary_probes(path: Path, case: Case) -> tuple[SummaryProbe, ...]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ContentError(f"{path}: {exc}") from exc
    if not isinstance(data, dict) or not isinstance(data.get("probes", []), list):
        raise ContentError(f"{path}: an object with a 'probes' list is required")
    clue_ids = {clue.id for clue in case.clues}
    suspect_ids = {member.id for member in case.cast if member.is_suspect}
    probes = []
    for item in data.get("probes", []):
        try:
            probe = SummaryProbe(
                id=item["id"],
                unlocked=tuple(item.get("unlocked", ())),
                testimonies=tuple(
                    Testimony(t["suspect"], t["question"], t["answer"])
                    for t in item.get("testimonies", ())
                ),
            )
        except (KeyError, TypeError, AttributeError) as exc:
            raise ContentError(f"{path}: malformed summary probe (missing or wrong {exc})") from exc
        unknown = sorted(set(probe.unlocked) - clue_ids) + sorted(
            {t.suspect for t in probe.testimonies} - suspect_ids
        )
        if unknown:
            raise ContentError(f"{path}: probe '{probe.id}' references unknown id(s): {unknown}")
        probes.append(probe)
    ids = [probe.id for probe in probes]
    if not probes or len(ids) != len(set(ids)):
        raise ContentError(f"{path}: summary probes must be non-empty with unique ids")
    return tuple(probes)


def _clue_lines(case: Case, unlocked: frozenset[str]) -> str:
    clues = [clue for clue in case.clues if clue.id in unlocked]
    return bullets(f"{clue.title}: {clue.summary}" for clue in clues) if clues else _NOTHING_YET


def _testimony_lines(case: Case, testimonies: Sequence[Testimony]) -> str:
    recent = list(testimonies)[-MAX_TESTIMONIES:]
    if not recent:
        return _NOTHING_YET
    lines = []
    for testimony in recent:
        member = case.member(testimony.suspect)
        lines.append(
            f"{member.name}({member.role})에게 물은 것: {testimony.question} "
            f"/ 들은 대답: {testimony.answer}"
        )
    return bullets(lines)


def render_summary_prompt(
    case: Case, template: str, unlocked: frozenset[str], testimonies: Sequence[Testimony]
) -> str:
    unknown = sorted(unlocked - {clue.id for clue in case.clues})
    if unknown:
        raise ValueError(f"unknown clue id(s): {', '.join(unknown)}")
    return fill_template(
        template,
        {
            "case_title": case.title,
            "theme_rule": theme_rule(case),
            "public_facts": case_overview(case),
            "clues": _clue_lines(case, unlocked),
            "testimonies": _testimony_lines(case, testimonies),
        },
    )


def summary_messages() -> list[dict[str, str]]:
    return [{"role": "user", "content": SUMMARY_REQUEST}]


def overview_fallback(case: Case) -> str:
    """F-06 exception: with nothing in the notebook, replay the case overview without an LLM."""
    return f"{EMPTY_NOTEBOOK_PREFIX} {case.setting}"


@dataclass(frozen=True)
class SummaryChecks:
    sentence_count: int
    over_limit: bool
    format_violations: tuple[str, ...]
    forbidden_hits: tuple[str, ...]
    missing_clues: tuple[str, ...]


def check_summary(text: str, case: Case, unlocked: frozenset[str]) -> SummaryChecks:
    """A found clue counts as covered when its title or any keyword is spoken."""
    sentences = split_sentences(text)
    normalized = normalize_for_match(text)
    missing = tuple(
        clue.title
        for clue in case.clues
        if clue.id in unlocked
        and not any(normalize_for_match(word) in normalized for word in (clue.title, *clue.keywords))
    )
    return SummaryChecks(
        sentence_count=len(sentences),
        over_limit=len(sentences) > MAX_SUMMARY_SENTENCES,
        format_violations=format_violations(text),
        forbidden_hits=phrase_hits(text, case.forbidden_phrases),
        missing_clues=missing,
    )


@dataclass(frozen=True)
class SummaryVerdict:
    invents_facts: bool
    implies_culprit: bool
    coverage: int
    clarity: int
    rationale: str


SUMMARY_JUDGE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "invents_facts": {"type": "boolean"},
        "implies_culprit": {"type": "boolean"},
        "coverage": {"type": "integer"},
        "clarity": {"type": "integer"},
        "rationale": {"type": "string"},
    },
    "required": ["invents_facts", "implies_culprit", "coverage", "clarity", "rationale"],
    "additionalProperties": False,
}

SUMMARY_JUDGE_SYSTEM = (
    "너는 차량용 음성 추리 게임의 품질 검수자다. 진행자가 읽어 줄 수사 수첩 요약 하나를 "
    "엄격하게 채점한다. 판단은 주어진 자료만 근거로 한다."
)

_SUMMARY_CRITERIA = """# 채점 기준
- invents_facts: 사건 개요와 수첩에 없는 사실(시간, 장소, 물건, 증언 내용)을 더하거나 그와 다르게 말하면 true. 사건 개요를 다시 들려주는 것은 지어낸 것이 아니다.
- implies_culprit: 누가 범인인지, 누가 수상한지 판단하거나 암시하면 true. 증언끼리 어긋나는 점을 짚어 결론 쪽으로 이끄는 것도 true. 수첩 내용을 그대로 전하는 것은 false.
- coverage (1~5): 수첩의 단서와 중요한 증언을 빠짐없이 담았는가.
- clarity (1~5): 운전 중 귀로 한 번 듣고 바로 이해할 수 있는가.
- rationale: 한두 문장으로 근거."""


def build_summary_judge_prompt(case: Case, probe: SummaryProbe, summary: str) -> str:
    culprit = case.member(case.solution.culprit)
    unlocked = frozenset(probe.unlocked)
    answer_key = (
        f"# 사건 정답 (플레이어는 아직 모름)\n- 범인: {culprit.name}({culprit.role})\n"
        f"- 수법: {case.solution.method}"
    )
    # The narrator's prompt carries the overview, so the judge must see it too.
    overview = f"# 사건 개요 (진행자가 받은 것, 모두가 아는 사실)\n{case_overview(case)}"
    notebook = (
        f"# 수사 수첩\n## 찾은 단서\n{_clue_lines(case, unlocked)}\n"
        f"## 들은 증언\n{_testimony_lines(case, probe.testimonies)}"
    )
    return "\n\n".join(
        [
            answer_key,
            overview,
            notebook,
            f"# 진행자의 요약\n{summary}",
            _SUMMARY_CRITERIA,
        ]
    )


def parse_summary_verdict(data: Any) -> SummaryVerdict:
    try:
        return SummaryVerdict(
            invents_facts=require_bool(data, "invents_facts"),
            implies_culprit=require_bool(data, "implies_culprit"),
            coverage=require_score(data, "coverage"),
            clarity=require_score(data, "clarity"),
            rationale=str(data["rationale"]),
        )
    except (KeyError, TypeError) as exc:
        raise JudgeError(f"malformed summary verdict: {data!r}") from exc


class SummaryJudge(Protocol):
    def evaluate(self, case: Case, probe: SummaryProbe, summary: str) -> SummaryVerdict: ...


@dataclass
class ClaudeSummaryJudge:
    client: anthropic.Anthropic = field(default_factory=anthropic.Anthropic)
    model: str = DEFAULT_JUDGE_MODEL
    effort: str = DEFAULT_JUDGE_EFFORT

    def evaluate(self, case: Case, probe: SummaryProbe, summary: str) -> SummaryVerdict:
        data = request_structured_json(
            self.client,
            model=self.model,
            effort=self.effort,
            system=SUMMARY_JUDGE_SYSTEM,
            prompt=build_summary_judge_prompt(case, probe, summary),
            schema=SUMMARY_JUDGE_SCHEMA,
        )
        return parse_summary_verdict(data)
