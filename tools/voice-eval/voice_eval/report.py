"""Aggregate run records per (candidate, variant) and render a Markdown report."""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass

from voice_eval.metrics import percentile
from voice_eval.runner import RunRecord

MAX_FAILURE_EXAMPLES = 30


@dataclass(frozen=True)
class SummaryRow:
    candidate_id: str
    model: str
    variant: str
    n: int
    errors: int
    refusals: int
    empty: int
    fallbacks: int
    ttft_p50_ms: float | None
    ttft_p90_ms: float | None
    ttfs_p50_ms: float | None
    ttfs_p90_ms: float | None
    total_p50_ms: float | None
    over_three_rate: float | None
    format_rate: float | None
    low_hangul_rate: float | None
    keyword_leak_rate: float | None
    judged: int
    judge_errors: int
    solution_leak_rate: float | None
    locked_leak_rate: float | None
    invented_rate: float | None
    in_character_avg: float | None
    answers_avg: float | None
    avg_cost_usd: float | None


def _is_answer(record: RunRecord) -> bool:
    """Counted in latency and quality rates: no error, not a refusal, and some text."""
    result = record.result
    return (
        result.error is None
        and result.stop_reason != "refusal"
        and bool(result.text.strip())
        and record.checks is not None
    )


def _rate(records: Sequence[RunRecord], predicate: Callable[[RunRecord], bool]) -> float | None:
    if not records:
        return None
    return sum(1 for record in records if predicate(record)) / len(records)


def _mean(values: Sequence[float]) -> float | None:
    return sum(values) / len(values) if values else None


def _summarize_group(group: Sequence[RunRecord]) -> SummaryRow:
    first = group[0]
    no_error = [record for record in group if record.result.error is None]
    answered = [record for record in group if _is_answer(record)]
    judged = [record for record in answered if record.verdict]
    ttft = [r.result.timing.ttft_ms for r in answered if r.result.timing.ttft_ms is not None]
    ttfs = [r.result.timing.ttfs_ms for r in answered if r.result.timing.ttfs_ms is not None]
    costs = [r.result.cost_usd for r in no_error if r.result.cost_usd is not None]
    refusals = sum(1 for r in no_error if r.result.stop_reason == "refusal")
    return SummaryRow(
        candidate_id=first.candidate.id,
        model=first.candidate.model,
        variant=first.variant,
        n=len(group),
        errors=len(group) - len(no_error),
        refusals=refusals,
        empty=len(no_error) - refusals - len(answered),
        fallbacks=sum(1 for r in no_error if r.result.fell_back),
        ttft_p50_ms=percentile(ttft, 50),
        ttft_p90_ms=percentile(ttft, 90),
        ttfs_p50_ms=percentile(ttfs, 50),
        ttfs_p90_ms=percentile(ttfs, 90),
        total_p50_ms=percentile([r.result.timing.total_ms for r in answered], 50),
        over_three_rate=_rate(answered, lambda r: r.checks.over_three_sentences),
        format_rate=_rate(answered, lambda r: bool(r.checks.format_violations)),
        low_hangul_rate=_rate(answered, lambda r: r.checks.low_hangul),
        keyword_leak_rate=_rate(answered, lambda r: bool(r.checks.forbidden_hits)),
        judged=len(judged),
        judge_errors=sum(1 for r in group if r.judge_error),
        solution_leak_rate=_rate(judged, lambda r: r.verdict.reveals_solution),
        locked_leak_rate=_rate(judged, lambda r: r.verdict.reveals_locked_clue),
        invented_rate=_rate(judged, lambda r: r.verdict.invents_case_facts),
        in_character_avg=_mean([r.verdict.in_character for r in judged]),
        answers_avg=_mean([r.verdict.answers_question for r in judged]),
        avg_cost_usd=_mean(costs),
    )


def summarize(records: Sequence[RunRecord]) -> tuple[SummaryRow, ...]:
    groups: dict[tuple[str, str], list[RunRecord]] = {}
    for record in records:
        groups.setdefault((record.candidate.id, record.variant), []).append(record)
    return tuple(_summarize_group(group) for group in groups.values())


def _ms(value: float | None) -> str:
    return "-" if value is None else f"{value:.0f}"


def _pct(value: float | None) -> str:
    return "-" if value is None else f"{value * 100:.0f}%"


def _avg(value: float | None) -> str:
    return "-" if value is None else f"{value:.2f}"


def _usd(value: float | None) -> str:
    return "-" if value is None else f"${value:.5f}"


def _failure_reasons(record: RunRecord) -> list[tuple[int, str]]:
    """(severity, label) pairs; lower severity numbers are worse and listed first."""
    result, checks, verdict = record.result, record.checks, record.verdict
    reasons: list[tuple[int, str]] = []
    if verdict and verdict.reveals_solution:
        reasons.append((0, "정답 누설"))
    if verdict and verdict.reveals_locked_clue:
        reasons.append((1, "미획득 단서 누설"))
    if checks and checks.forbidden_hits:
        reasons.append((2, f"금지어({', '.join(checks.forbidden_hits)})"))
    if checks and checks.reads_instructions:
        reasons.append((2, "지시문 읽기"))
    if verdict and verdict.invents_case_facts:
        reasons.append((3, "사실 지어냄"))
    if result.stop_reason == "refusal":
        reasons.append((4, "거절"))
    elif result.error is None and not result.text.strip():
        reasons.append((4, "빈 답변"))
    if result.fell_back:
        reasons.append((4, f"대체 모델 응답({result.served_by})"))
    if result.error:
        reasons.append((5, f"오류({result.error})"))
    if checks and checks.over_three_sentences:
        reasons.append((6, f"{checks.sentence_count}문장"))
    if checks and checks.format_violations:
        reasons.append((6, f"형식({', '.join(checks.format_violations)})"))
    if record.judge_error:
        reasons.append((7, f"채점 실패({record.judge_error})"))
    return reasons


def _speed_table(rows: Sequence[SummaryRow]) -> list[str]:
    lines = [
        (
            "| 후보 | 변형 | 모델 | n | 오류 | 거절 | 빈 답변 | 대체 응답 | TTFT p50 | TTFT p90 "
            "| TTFS p50 | TTFS p90 | 전체 p50 | 답변당 비용 |"
        ),
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for row in rows:
        lines.append(
            f"| {row.candidate_id} | {row.variant} | {row.model} | {row.n} | {row.errors} "
            f"| {row.refusals} | {row.empty} | {row.fallbacks} | {_ms(row.ttft_p50_ms)} "
            f"| {_ms(row.ttft_p90_ms)} | {_ms(row.ttfs_p50_ms)} | {_ms(row.ttfs_p90_ms)} "
            f"| {_ms(row.total_p50_ms)} | {_usd(row.avg_cost_usd)} |"
        )
    return lines


def _quality_table(rows: Sequence[SummaryRow]) -> list[str]:
    lines = [
        (
            "| 후보 | 변형 | 3문장 초과 | 형식 위반 | 한글 비율 낮음 | 금지어 | 채점 수 | 채점 실패 "
            "| 정답 누설 | 미획득 단서 누설 | 사실 지어냄 | 캐릭터(1-5) | 응답성(1-5) |"
        ),
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for row in rows:
        lines.append(
            f"| {row.candidate_id} | {row.variant} | {_pct(row.over_three_rate)} "
            f"| {_pct(row.format_rate)} | {_pct(row.low_hangul_rate)} "
            f"| {_pct(row.keyword_leak_rate)} | {row.judged} | {row.judge_errors} "
            f"| {_pct(row.solution_leak_rate)} | {_pct(row.locked_leak_rate)} "
            f"| {_pct(row.invented_rate)} | {_avg(row.in_character_avg)} | {_avg(row.answers_avg)} |"
        )
    return lines


def _failure_section(records: Sequence[RunRecord]) -> list[str]:
    failures = [
        (min(rank for rank, _ in reasons), index, record, reasons)
        for index, record in enumerate(records)
        if (reasons := _failure_reasons(record))
    ]
    failures.sort(key=lambda item: (item[0], item[1]))
    lines = [f"## 문제 사례 ({len(failures)}건, 심각한 것부터 최대 {MAX_FAILURE_EXAMPLES}건)", ""]
    for _, _, record, reasons in failures[:MAX_FAILURE_EXAMPLES]:
        lines.append(
            f"- **{record.candidate.id} / {record.variant} / {record.probe.id}** "
            f"({'; '.join(label for _, label in reasons)})\n  - Q: {record.probe.question}\n"
            f"  - A: {record.result.text or '(없음)'}"
        )
    if not failures:
        lines.append("- 없음")
    return lines


def render_markdown(
    rows: Sequence[SummaryRow], records: Sequence[RunRecord], title: str = "VoiceAgent 비교 결과"
) -> str:
    lines = [
        f"# {title}",
        "",
        (
            "TTFT = 첫 글자까지, TTFS = 첫 문장 완성까지(차가 말을 시작할 수 있는 시점). 단위 ms. "
            "속도와 품질 비율은 오류·거절·빈 답변을 뺀 정상 답변 기준입니다. "
            "금지어는 단순 문자열 검사라 사람이 확인할 표시이고, 누설 판정은 LLM 채점을 보세요."
        ),
        "",
        "## 속도와 비용",
        "",
        *_speed_table(rows),
        "",
        "## 품질",
        "",
        *_quality_table(rows),
        "",
        *_failure_section(records),
    ]
    return "\n".join(lines) + "\n"
