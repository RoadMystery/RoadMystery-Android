import json
from pathlib import Path

import pytest

from voice_eval.content import Case, Probe, Suspect
from voice_eval.judge import JudgeError, JudgeVerdict
from voice_eval.prompt import render_system_prompt
from voice_eval.providers import Candidate, ScriptedProvider, StreamResult
from voice_eval.report import render_markdown, summarize
from voice_eval.runner import RunRecord, run_matrix

CANDIDATES = (
    Candidate(id="fast", model="claude-haiku-5-5", effort="low"),
    Candidate(id="slow", model="claude-opus-5-5", effort="low"),
)


class FakeJudge:
    def __init__(self) -> None:
        self.calls = 0

    def evaluate(self, case, suspect, probe, unlocked, answer) -> JudgeVerdict:
        self.calls += 1
        return JudgeVerdict(
            reveals_solution="훔쳤" in answer,
            reveals_locked_clue=False,
            invents_case_facts=False,
            in_character=5,
            answers_question=4,
            rationale="테스트",
        )


class FailingProvider:
    def stream_answer(self, candidate, system, messages) -> StreamResult:
        return StreamResult.failed("RateLimitError: 테스트", total_ms=12.0)


def _leaky_for_slow(candidate: Candidate, system: str, messages: list[dict]) -> str:
    if candidate.id == "slow":
        return "제가 훔쳤습니다."
    return "저장고에 있었습니다. 아무것도 못 봤습니다."


def test_a_question_raising_an_unfound_clue_gets_the_topic_rule(
    case: Case, butler: Suspect, template: str, probes: tuple[Probe, ...]
) -> None:
    systems: dict[str, str] = {}

    def remember(candidate: Candidate, system: str, messages: list[dict]) -> str:
        systems[messages[-1]["content"]] = system
        return "저장고에 있었습니다."

    by_id = {probe.id: probe for probe in probes}
    key, alibi = by_id["locked-key"], by_id["alibi-basic"]
    run_matrix(
        case=case,
        suspect=butler,
        probes=(key, alibi),
        candidates=CANDIDATES[:1],
        variants=("cover",),
        repeats=1,
        template=template,
        provider=ScriptedProvider(remember),
    )

    assert "'열쇠' 이야기는" in systems[key.question]
    # A question without such a topic gets exactly the prompt it got before topic rules existed.
    assert systems[alibi.question] == render_system_prompt(case, butler, frozenset(), "cover", template)


def test_run_matrix_covers_every_combination_and_writes_jsonl(
    tmp_path: Path, case: Case, butler: Suspect, template: str, probes: tuple[Probe, ...]
) -> None:
    out = tmp_path / "run.jsonl"
    judge = FakeJudge()
    with out.open("w", encoding="utf-8") as sink:
        records = run_matrix(
            case=case,
            suspect=butler,
            probes=probes[:2],
            candidates=CANDIDATES,
            variants=("cover", "truth"),
            repeats=2,
            template=template,
            provider=ScriptedProvider(_leaky_for_slow, ms_per_chunk=50.0),
            judge=judge,
            sink=lambda record: sink.write(json.dumps(record.to_json(), ensure_ascii=False) + "\n"),
        )

    assert len(records) == 2 * 2 * 2 * 2
    assert judge.calls == len(records)
    lines = out.read_text(encoding="utf-8").splitlines()
    assert len(lines) == len(records)
    first = json.loads(lines[0])
    assert {"candidate", "variant", "probe", "answer", "timing", "checks", "verdict"} <= set(first)


def test_summary_reports_latency_and_leak_rates(
    case: Case, butler: Suspect, template: str, probes: tuple[Probe, ...]
) -> None:
    records = run_matrix(
        case=case,
        suspect=butler,
        probes=probes[:3],
        candidates=CANDIDATES,
        variants=("cover",),
        repeats=1,
        template=template,
        provider=ScriptedProvider(_leaky_for_slow, ms_per_chunk=50.0),
        judge=FakeJudge(),
    )
    rows = {row.candidate_id: row for row in summarize(records)}

    assert rows["fast"].n == 3
    assert rows["fast"].solution_leak_rate == 0.0
    assert rows["slow"].solution_leak_rate == 1.0
    assert rows["slow"].keyword_leak_rate == 1.0
    assert rows["fast"].ttft_p50_ms is not None

    markdown = render_markdown(summarize(records), records)
    assert "| fast | cover |" in markdown
    assert "slow" in markdown


class RefusingProvider:
    def stream_answer(self, candidate, system, messages) -> StreamResult:
        result = ScriptedProvider(lambda *_: "").stream_answer(candidate, system, messages)
        return StreamResult(text="", timing=result.timing, stop_reason="refusal")


class BrokenJudge:
    def evaluate(self, case, suspect, probe, unlocked, answer) -> JudgeVerdict:
        raise JudgeError("judge refused")


def test_refusals_are_counted_and_not_judged(
    case: Case, butler: Suspect, template: str, probes: tuple[Probe, ...]
) -> None:
    judge = FakeJudge()
    records = run_matrix(
        case=case,
        suspect=butler,
        probes=probes[:1],
        candidates=CANDIDATES[:1],
        variants=("cover",),
        repeats=1,
        template=template,
        provider=RefusingProvider(),
        judge=judge,
    )

    assert judge.calls == 0
    assert summarize(records)[0].refusals == 1
    assert "거절" in render_markdown(summarize(records), records)


@pytest.mark.parametrize(
    "answer",
    ["생각할 필요 없이 답하면 된다. 저장고에 있었습니다.", "{name}은 모르는 일이라고 답해야 합니다. 저장고에 있었습니다."],
)
def test_answers_that_read_out_instructions_are_listed(
    answer: str, case: Case, butler: Suspect, template: str, probes: tuple[Probe, ...]
) -> None:
    def readout(candidate: Candidate, system: str, messages: list[dict]) -> str:
        return answer.format(name=butler.name)

    records = run_matrix(
        case=case,
        suspect=butler,
        probes=probes[:1],
        candidates=CANDIDATES,
        variants=("cover",),
        repeats=1,
        template=template,
        provider=ScriptedProvider(readout),
        judge=None,
    )

    assert all(record.checks.reads_instructions for record in records)
    assert "지시문 읽기" in render_markdown(summarize(records), records)


def test_empty_answers_are_counted_apart_from_quality_rates(
    case: Case, butler: Suspect, template: str, probes: tuple[Probe, ...]
) -> None:
    def empty_for_fast(candidate: Candidate, system: str, messages: list[dict]) -> str:
        return "" if candidate.id == "fast" else "저장고에 있었습니다."

    records = run_matrix(
        case=case,
        suspect=butler,
        probes=probes[:2],
        candidates=CANDIDATES,
        variants=("cover",),
        repeats=1,
        template=template,
        provider=ScriptedProvider(empty_for_fast),
        judge=None,
    )
    rows = {row.candidate_id: row for row in summarize(records)}

    assert rows["fast"].empty == 2
    assert rows["fast"].over_three_rate is None
    assert rows["fast"].ttft_p50_ms is None
    assert rows["slow"].empty == 0
    assert "빈 답변" in render_markdown(summarize(records), records)


def test_worst_failures_are_listed_first(
    case: Case, butler: Suspect, template: str, probes: tuple[Probe, ...]
) -> None:
    def long_for_fast(candidate: Candidate, system: str, messages: list[dict]) -> str:
        if candidate.id == "fast":
            return "저는 저장고에 있었습니다. 와인을 고르고 있었습니다. 불이 나갔습니다. 그 뒤로는 모릅니다."
        return "제가 훔쳤습니다."

    records = run_matrix(
        case=case,
        suspect=butler,
        probes=probes[:1],
        candidates=CANDIDATES,
        variants=("cover",),
        repeats=1,
        template=template,
        provider=ScriptedProvider(long_for_fast),
        judge=FakeJudge(),
    )
    markdown = render_markdown(summarize(records), records)
    assert markdown.index("정답 누설") < markdown.index("4문장")


def test_judge_failures_are_recorded_on_the_record(
    case: Case, butler: Suspect, template: str, probes: tuple[Probe, ...]
) -> None:
    records = run_matrix(
        case=case,
        suspect=butler,
        probes=probes[:1],
        candidates=CANDIDATES[:1],
        variants=("cover",),
        repeats=1,
        template=template,
        provider=ScriptedProvider(_leaky_for_slow),
        judge=BrokenJudge(),
    )

    assert records[0].verdict is None
    assert records[0].judge_error == "judge refused"
    assert records[0].to_json()["judge_error"] == "judge refused"


def test_provider_errors_are_recorded_not_raised(
    case: Case, butler: Suspect, template: str, probes: tuple[Probe, ...]
) -> None:
    judge = FakeJudge()
    records = run_matrix(
        case=case,
        suspect=butler,
        probes=probes[:1],
        candidates=CANDIDATES[:1],
        variants=("cover",),
        repeats=1,
        template=template,
        provider=FailingProvider(),
        judge=judge,
    )

    record: RunRecord = records[0]
    assert record.result.error is not None
    assert record.verdict is None
    assert judge.calls == 0
    assert summarize(records)[0].errors == 1
