import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from conftest import TOOL_ROOT

from voice_eval.content import Case, ContentError
from voice_eval.judge import JudgeError
from voice_eval.metrics import Timing
from voice_eval.providers import Candidate, ScriptedProvider, StreamResult
from voice_eval.summary import (
    EMPTY_NOTEBOOK_PREFIX,
    MAX_TESTIMONIES,
    SUMMARY_JUDGE_SCHEMA,
    ClaudeSummaryJudge,
    SummaryProbe,
    SummaryVerdict,
    build_summary_judge_prompt,
    check_summary,
    load_summary_probes,
    parse_summary_verdict,
    render_summary_prompt,
)
from voice_eval.summary_eval import render_summary_markdown, run_summary_matrix

PROBES = TOOL_ROOT / "probes" / "summary.json"
TEMPLATE = (TOOL_ROOT / "prompts" / "summary_system.ko.md").read_text(encoding="utf-8")
CANDIDATE = Candidate(id="fast", model="claude-haiku-5-5", effort="low")


@pytest.fixture(scope="module")
def probes(case: Case) -> dict[str, SummaryProbe]:
    return {probe.id: probe for probe in load_summary_probes(PROBES, case)}


def _render(case: Case, probe: SummaryProbe) -> str:
    return render_summary_prompt(case, TEMPLATE, frozenset(probe.unlocked), probe.testimonies)


class NeverCalled:
    def stream_answer(self, candidate, system, messages) -> StreamResult:
        raise AssertionError("the empty notebook must not reach the LLM")


class FakeSummaryJudge:
    def evaluate(self, case, probe, summary) -> SummaryVerdict:
        return SummaryVerdict(
            invents_facts=False,
            implies_culprit="집사" in summary and "범인" in summary,
            coverage=4,
            clarity=5,
            rationale="테스트",
        )


def test_prompt_lists_found_clues_and_testimonies_only(
    case: Case, probes: dict[str, SummaryProbe]
) -> None:
    prompt = _render(case, probes["mid-game-tempting"])

    assert case.clue("clue-wet-gloves").summary in prompt
    assert case.clue("clue-cellar-key").summary not in prompt
    assert "한 집사가 은식기 닦을 때 쓰는 거랑 같은 종류예요" in prompt
    assert "박미란(요리사)" in prompt
    assert "폭력" in prompt
    assert "{{" not in prompt


def test_only_recent_testimonies_are_included(case: Case, probes: dict[str, SummaryProbe]) -> None:
    probe = probes["many-testimonies"]
    prompt = _render(case, probe)
    assert len(probe.testimonies) > MAX_TESTIMONIES
    dropped = probe.testimonies[: len(probe.testimonies) - MAX_TESTIMONIES]
    assert all(testimony.answer not in prompt for testimony in dropped)
    assert probe.testimonies[-1].answer in prompt


def test_empty_sections_say_nothing_yet(case: Case, probes: dict[str, SummaryProbe]) -> None:
    prompt = _render(case, probes["testimonies-only"])
    assert "## 찾은 단서\n- 아직 없음" in prompt


def test_unknown_clue_is_rejected(case: Case) -> None:
    with pytest.raises(ValueError, match="clue-nope"):
        render_summary_prompt(case, TEMPLATE, frozenset({"clue-nope"}), ())


def test_probe_file_rejects_unknown_suspect(tmp_path: Path, case: Case) -> None:
    path = tmp_path / "summary.json"
    path.write_text(
        json.dumps({"probes": [{"id": "x", "testimonies": [
            {"suspect": "ghost", "question": "q", "answer": "a"}]}]}),
        encoding="utf-8",
    )
    with pytest.raises(ContentError, match="ghost"):
        load_summary_probes(path, case)


def test_check_summary_flags_length_and_missing_clues(case: Case) -> None:
    unlocked = frozenset({"clue-fusebox", "clue-pawn-ticket"})
    checks = check_summary("하나. 둘. 셋. 넷. 다섯. 여섯. 두꺼비집 차단기가 내려가 있었어요.", case, unlocked)
    assert checks.over_limit
    assert checks.missing_clues == ("전당포 영수증",)


def test_empty_notebook_replays_the_overview_without_llm(
    case: Case, probes: dict[str, SummaryProbe]
) -> None:
    records = run_summary_matrix(
        case=case,
        probes=[probes["empty-notebook"]],
        candidates=[CANDIDATE],
        repeats=1,
        template=TEMPLATE,
        provider=NeverCalled(),
    )
    assert records[0].fallback
    assert records[0].text.startswith(EMPTY_NOTEBOOK_PREFIX)
    assert case.setting in records[0].text


def test_report_lists_worst_cases_first(case: Case, probes: dict[str, SummaryProbe]) -> None:
    def script(candidate, system, messages) -> str:
        return "두꺼비집 차단기가 내려가 있었어요. 아무래도 집사가 범인 같아요."

    records = run_summary_matrix(
        case=case,
        probes=[probes["one-clue"], probes["empty-notebook"]],
        candidates=[CANDIDATE],
        repeats=1,
        template=TEMPLATE,
        provider=ScriptedProvider(script),
        judge=FakeSummaryJudge(),
    )
    markdown = render_summary_markdown(records)

    assert "| fast | 2 |" in markdown
    assert markdown.index("범인 암시") < markdown.index("## 문제 사례") + 200
    assert records[0].to_json()["verdict"]["implies_culprit"] is True


def test_probe_file_must_be_an_object_with_complete_testimonies(tmp_path: Path, case: Case) -> None:
    listed = tmp_path / "list.json"
    listed.write_text("[]", encoding="utf-8")
    partial = tmp_path / "partial.json"
    probe = {"id": "p", "testimonies": [{"suspect": "cook", "question": "어디 있었어요?"}]}
    partial.write_text(json.dumps({"probes": [probe]}, ensure_ascii=False), encoding="utf-8")

    for path in (listed, partial):
        with pytest.raises(ContentError):
            load_summary_probes(path, case)


def test_refusals_and_empty_summaries_are_counted_apart_and_not_judged(
    case: Case, probes: dict[str, SummaryProbe]
) -> None:
    outcomes = iter([("죄송하지만 도와드릴 수 없어요", "refusal"), ("", "max_tokens")])

    class Declining:
        def stream_answer(self, candidate, system, messages) -> StreamResult:
            text, stop_reason = next(outcomes)
            return StreamResult(text=text, timing=Timing(100.0, None, 200.0), stop_reason=stop_reason,
                                served_by="claude-haiku-5-5", input_tokens=10, output_tokens=2)

    class NeverJudges:
        def evaluate(self, case, probe, summary) -> SummaryVerdict:
            raise AssertionError("refused or empty summaries must not be judged")

    records = run_summary_matrix(
        case=case,
        probes=[probes["one-clue"], probes["mid-game-tempting"]],
        candidates=[CANDIDATE],
        repeats=1,
        template=TEMPLATE,
        provider=Declining(),
        judge=NeverJudges(),
    )
    row = next(line for line in render_summary_markdown(records).splitlines() if line.startswith("| fast |"))

    assert row.startswith("| fast | 2 | 0 | 1 | 1 |")
    assert records[0].to_json()["stop_reason"] == "refusal"
    assert records[0].to_json()["usage"] == {"input_tokens": 10, "output_tokens": 2}


def test_summary_judge_sees_the_case_overview_the_narrator_was_given(
    case: Case, probes: dict[str, SummaryProbe]
) -> None:
    prompt = build_summary_judge_prompt(case, probes["one-clue"], "요약")

    # The narrator's prompt carries the overview, so repeating it is not inventing facts.
    assert case.setting in prompt
    assert all(fact in prompt for fact in case.public_facts)
    assert "사건 개요" in prompt[prompt.index("invents_facts"):]


def test_summary_judge_parses_structured_output(case: Case, probes: dict[str, SummaryProbe]) -> None:
    payload = {"invents_facts": False, "implies_culprit": False, "coverage": 5, "clarity": 4,
               "rationale": "좋음"}
    calls: list[dict] = []

    def create(**params):
        calls.append(params)
        return SimpleNamespace(
            stop_reason="end_turn",
            content=[SimpleNamespace(type="text", text=json.dumps(payload))],
        )

    judge = ClaudeSummaryJudge(client=SimpleNamespace(messages=SimpleNamespace(create=create)))
    verdict = judge.evaluate(case, probes["one-clue"], "두꺼비집 차단기가 내려가 있었어요.")

    assert verdict.coverage == 5
    assert calls[0]["output_config"]["format"]["schema"] == SUMMARY_JUDGE_SCHEMA
    with pytest.raises(JudgeError):
        parse_summary_verdict({**payload, "coverage": "5"})
