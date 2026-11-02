"""Run F-06 summary probes across candidates and render a Markdown report."""

from __future__ import annotations

import logging
from collections.abc import Callable, Sequence
from dataclasses import asdict, dataclass
from typing import Any

from voice_eval.content import Case
from voice_eval.judge import JudgeError
from voice_eval.metrics import percentile
from voice_eval.providers import AnswerProvider, Candidate, StreamResult
from voice_eval.summary import (
    SummaryChecks,
    SummaryJudge,
    SummaryProbe,
    SummaryVerdict,
    check_summary,
    overview_fallback,
    render_summary_prompt,
    summary_messages,
)

logger = logging.getLogger(__name__)
MAX_FAILURE_EXAMPLES = 20


@dataclass(frozen=True)
class SummaryRecord:
    candidate: Candidate
    probe: SummaryProbe
    repeat: int
    text: str
    result: StreamResult | None
    """None when the empty-notebook fallback was used (no LLM call)."""
    checks: SummaryChecks | None
    verdict: SummaryVerdict | None
    judge_error: str | None = None

    @property
    def fallback(self) -> bool:
        return self.result is None

    @property
    def error(self) -> str | None:
        return self.result.error if self.result else None

    @property
    def refused(self) -> bool:
        return self.result is not None and self.error is None and self.result.stop_reason == "refusal"

    @property
    def empty(self) -> bool:
        return self.result is not None and self.error is None and not self.refused and not self.text.strip()

    def to_json(self) -> dict[str, Any]:
        result = self.result
        return {
            "candidate": self.candidate.id,
            "probe": self.probe.id,
            "repeat": self.repeat,
            "fallback": self.fallback,
            "text": self.text,
            "timing": asdict(result.timing) if result else None,
            "stop_reason": result.stop_reason if result else None,
            "usage": {"input_tokens": result.input_tokens, "output_tokens": result.output_tokens}
            if result else None,
            "served_by": result.served_by if result else None,
            "cost_usd": result.cost_usd if result else None,
            "error": self.error,
            "checks": asdict(self.checks) if self.checks else None,
            "verdict": asdict(self.verdict) if self.verdict else None,
            "judge_error": self.judge_error,
        }


def _judge(
    judge: SummaryJudge | None, case: Case, probe: SummaryProbe, text: str
) -> tuple[SummaryVerdict | None, str | None]:
    if judge is None or not text.strip():
        return None, None
    try:
        return judge.evaluate(case, probe, text), None
    except JudgeError as exc:
        return None, str(exc)


def _one(
    case: Case,
    probe: SummaryProbe,
    candidate: Candidate,
    repeat: int,
    system: str,
    provider: AnswerProvider,
    judge: SummaryJudge | None,
) -> SummaryRecord:
    unlocked = frozenset(probe.unlocked)
    if probe.is_empty:
        text = overview_fallback(case)
        return SummaryRecord(candidate, probe, repeat, text, None, check_summary(text, case, unlocked), None)
    result = provider.stream_answer(candidate, system, summary_messages())
    if result.error is not None:
        return SummaryRecord(candidate, probe, repeat, "", result, None, None)
    if result.stop_reason == "refusal" or not result.text.strip():
        # Counted apart and never judged: a paid verdict on a refusal or nothing says nothing.
        return SummaryRecord(candidate, probe, repeat, result.text, result, None, None)
    verdict, judge_error = _judge(judge, case, probe, result.text)
    checks = check_summary(result.text, case, unlocked)
    return SummaryRecord(candidate, probe, repeat, result.text, result, checks, verdict, judge_error)


def run_summary_matrix(
    *,
    case: Case,
    probes: Sequence[SummaryProbe],
    candidates: Sequence[Candidate],
    repeats: int,
    template: str,
    provider: AnswerProvider,
    judge: SummaryJudge | None = None,
    sink: Callable[[SummaryRecord], Any] | None = None,
) -> tuple[SummaryRecord, ...]:
    systems = {
        probe.id: render_summary_prompt(case, template, frozenset(probe.unlocked), probe.testimonies)
        for probe in probes
    }
    records: list[SummaryRecord] = []
    for repeat in range(1, repeats + 1):
        for probe in probes:
            for candidate in candidates:
                record = _one(case, probe, candidate, repeat, systems[probe.id], provider, judge)
                records.append(record)
                if sink is not None:
                    sink(record)
                logger.info("[summary %d] %s %s -> %s", len(records), candidate.id, probe.id,
                            record.error or ("fallback" if record.fallback else "ok"))
    return tuple(records)


def _rate(records: Sequence[SummaryRecord], predicate: Callable[[SummaryRecord], bool]) -> str:
    if not records:
        return "-"
    return f"{sum(1 for r in records if predicate(r)) / len(records) * 100:.0f}%"


def _avg(values: Sequence[float]) -> str:
    return f"{sum(values) / len(values):.2f}" if values else "-"


def _ms(value: float | None) -> str:
    return "-" if value is None else f"{value:.0f}"


def _row(candidate_id: str, group: Sequence[SummaryRecord]) -> str:
    llm = [r for r in group if not r.fallback]
    errors = sum(1 for r in llm if r.error is not None)
    refused = sum(1 for r in llm if r.refused)
    empty = sum(1 for r in llm if r.empty)
    answered = [r for r in llm if r.error is None and not r.refused and not r.empty and r.checks]
    judged = [r for r in answered if r.verdict]
    ttfs = [r.result.timing.ttfs_ms for r in answered if r.result and r.result.timing.ttfs_ms]
    costs = [r.result.cost_usd for r in answered if r.result and r.result.cost_usd is not None]
    return (
        f"| {candidate_id} | {len(group)} | {errors} | {refused} | {empty} | {_ms(percentile(ttfs, 50))} "
        f"| {_ms(percentile(ttfs, 90))} | {_rate(answered, lambda r: r.checks.over_limit)} "
        f"| {_rate(answered, lambda r: bool(r.checks.format_violations))} "
        f"| {_rate(answered, lambda r: bool(r.checks.forbidden_hits))} "
        f"| {_rate(answered, lambda r: bool(r.checks.missing_clues))} | {len(judged)} "
        f"| {_rate(judged, lambda r: r.verdict.invents_facts)} "
        f"| {_rate(judged, lambda r: r.verdict.implies_culprit)} "
        f"| {_avg([r.verdict.coverage for r in judged])} | {_avg([r.verdict.clarity for r in judged])} "
        f"| {'-' if not costs else f'${sum(costs) / len(costs):.5f}'} |"
    )


def _reasons(record: SummaryRecord) -> list[tuple[int, str]]:
    checks, verdict = record.checks, record.verdict
    reasons: list[tuple[int, str]] = []
    if verdict and verdict.implies_culprit:
        reasons.append((0, "범인 암시"))
    if checks and checks.forbidden_hits:
        reasons.append((1, f"금지어({', '.join(checks.forbidden_hits)})"))
    if verdict and verdict.invents_facts:
        reasons.append((2, "사실 지어냄"))
    if checks and checks.missing_clues:
        reasons.append((3, f"빠진 단서({', '.join(checks.missing_clues)})"))
    if checks and checks.over_limit:
        reasons.append((4, f"{checks.sentence_count}문장"))
    if checks and checks.format_violations:
        reasons.append((4, f"형식({', '.join(checks.format_violations)})"))
    if record.error:
        reasons.append((5, f"오류({record.error})"))
    if record.refused:
        reasons.append((5, "거절"))
    if record.empty:
        reasons.append((5, "빈 요약"))
    if record.judge_error:
        reasons.append((6, f"채점 실패({record.judge_error})"))
    return reasons


def render_summary_markdown(records: Sequence[SummaryRecord]) -> str:
    groups: dict[str, list[SummaryRecord]] = {}
    for record in records:
        groups.setdefault(record.candidate.id, []).append(record)
    lines = [
        "# 수사 수첩 요약(F-06) 비교 결과",
        "",
        (
            "빈 수첩은 LLM 없이 사건 개요를 읽으므로 비율 계산에서 뺍니다. 거절·빈 요약은 채점하지 않고 "
            "비율에서도 뺍니다. TTFS 단위 ms."
        ),
        "",
        (
            "| 후보 | n | 오류 | 거절 | 빈 요약 | TTFS p50 | TTFS p90 | 5문장 초과 | 형식 위반 | 금지어 "
            "| 단서 누락 | 채점 수 | 사실 지어냄 | 범인 암시 | 포괄성(1-5) | 명료성(1-5) | 요약당 비용 |"
        ),
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|",
        *(_row(candidate_id, group) for candidate_id, group in groups.items()),
        "",
    ]
    failures = sorted(
        ((min(rank for rank, _ in reasons), index, record, reasons)
         for index, record in enumerate(records) if (reasons := _reasons(record))),
        key=lambda item: (item[0], item[1]),
    )
    lines += [f"## 문제 사례 ({len(failures)}건, 심각한 것부터 최대 {MAX_FAILURE_EXAMPLES}건)", ""]
    for _, _, record, reasons in failures[:MAX_FAILURE_EXAMPLES]:
        lines.append(
            f"- **{record.candidate.id} / {record.probe.id}** "
            f"({'; '.join(label for _, label in reasons)})\n  - {record.text or '(없음)'}"
        )
    if not failures:
        lines.append("- 없음")
    return "\n".join(lines) + "\n"
