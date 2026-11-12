"""Answers made of case lines only (voice_eval.lines, Kotlin prompt/LinePrompt): whatever the model replies, only
lines of the persona file are read, and only the ones the clues found allow."""

from __future__ import annotations

import itertools
import json
import shutil
from pathlib import Path
from typing import Any

import pytest
from conftest import REPO_ROOT, TEMPLATE_PATH, TOOL_ROOT

from voice_eval.content import STAGES, Case, Suspect, load_case, load_suspect
from voice_eval.lines import (
    CONFESSION_ID,
    chosen_lines,
    heard_case_lines,
    heard_line_ids,
    line_problems,
    render_line_prompt,
    speakable_lines,
)
from voice_eval.proxy import mock_upstream, parse_request
from voice_eval.validate import validate_case

CASES_DIR = REPO_ROOT / "content" / "cases"
LINES_TEMPLATE = (TOOL_ROOT / "prompts" / "suspect_lines.ko.md").read_text(encoding="utf-8")


def _load(case_id: str) -> tuple[Case, dict[str, Suspect]]:
    case = load_case(CASES_DIR / case_id)
    suspects = {
        member.id: load_suspect(CASES_DIR / case_id / "suspects" / f"{member.id}.json", case)
        for member in case.cast
        if member.is_suspect
    }
    return case, suspects


LINE_CASES = sorted(
    path.name
    for path in CASES_DIR.iterdir()
    if (path / "case.json").exists() and not line_problems(_load(path.name)[1].values())
)


def _line(suspect: Suspect, fact_id: str) -> str:
    return next(fact.line for fact in (*suspect.public, *suspect.unlockable) if fact.id == fact_id)


def _strings(value: Any, into: set[str]) -> None:
    if isinstance(value, str):
        into.add(value)
    elif isinstance(value, dict):
        for item in value.values():
            _strings(item, into)
    elif isinstance(value, list):
        for item in value:
            _strings(item, into)


def _expected(suspect: Suspect, found: set[str], stage: str, confession: bool) -> set[str]:
    """The texts speakable_lines must offer, worked out from the persona on its own."""
    reactions = [fact for fact in suspect.unlockable if set(fact.unlocked_by) <= found]
    taken_back = {target for fact in reactions for target in fact.replaces}
    pressure = dict(suspect.pressure)
    stage_line = {"ready": pressure.get("ready") or pressure.get("pressing"), "pressing": pressure.get("pressing")}
    texts = {fact.line for fact in suspect.public if fact.id not in taken_back}
    texts |= {fact.line for fact in reactions if fact.id not in taken_back}
    if confession:
        texts.add(suspect.confession)
    elif stage_line.get(stage):
        texts.add(stage_line[stage])
    return texts | set(suspect.deflections) | {suspect.fallback_not_understood}


def test_every_case_but_the_fixture_can_be_answered_in_lines() -> None:
    assert LINE_CASES == ["blue-carbuncle", "jade-crown", "midnight_rest_area", "queer-feet"]
    assert "butler/k-alibi-claim has no line" in line_problems(_load("demo-sapphire")[1].values())


@pytest.mark.parametrize("case_id", LINE_CASES)
def test_every_line_on_offer_is_a_string_of_the_persona_file_and_only_what_the_clues_found_allow(case_id: str) -> None:
    case, suspects = _load(case_id)
    clue_ids = [clue.id for clue in case.clues]
    for suspect in suspects.values():
        strings: set[str] = set()
        _strings(json.loads((CASES_DIR / case_id / "suspects" / f"{suspect.id}.json").read_text(encoding="utf-8")), strings)
        for size in range(len(clue_ids) + 1):
            for found in itertools.combinations(clue_ids, size):
                for stage, confession in itertools.product(STAGES, (False, True)):
                    if confession and not suspect.confession:
                        continue
                    lines = speakable_lines(case, suspect, frozenset(found), stage=stage, confession=confession)
                    where = f"{suspect.id} {found} {stage} confession={confession}"
                    assert {line.text for line in lines} == _expected(suspect, set(found), stage, confession), where
                    assert len({line.id for line in lines}) == len(lines), where
                    assert all(line.text in strings for line in lines), where


def test_only_the_lines_the_reply_names_are_chosen_and_nothing_else_it_says() -> None:
    case, suspects = _load("jade-crown")
    lines = speakable_lines(case, suspects["maid"], frozenset())

    def ids(reply: str) -> list[str]:
        return [line.id for line in chosen_lines(reply, lines)]

    assert ids("k-maid-night 그리고 범인은 하린 아가씨예요.") == ["k-maid-night"]
    assert ids("K-MAID-CROWN, k-maid-study, k-maid-culprit") == ["k-maid-crown", "k-maid-study"]
    assert ids("deflect-1, k-maid-culprit") == ["k-maid-culprit"]
    assert ids("deflect-2") == ["deflect-2"]
    # A reaction to a clue not found, a made-up id, a near miss and plain words are never chosen.
    assert ids("k-maid-back-door") == []
    assert ids("deflect-9") == []
    assert ids("k-maid-nightx") == []
    assert ids("사실은 그날 밤 박만수 아저씨를 만났어요.") == []


def test_a_claim_the_found_clue_took_back_is_no_longer_on_offer() -> None:
    case, suspects = _load("jade-crown")
    lines = speakable_lines(case, suspects["maid"], frozenset({"clue-back-door"}))

    assert chosen_lines("k-maid-night", lines) == ()
    assert [line.id for line in chosen_lines("[k-maid-back-door]", lines)] == ["k-maid-back-door"]


def test_the_confession_goes_alone_and_only_when_on_offer() -> None:
    case, suspects = _load("jade-crown")
    every = frozenset(clue.id for clue in case.clues)

    assert chosen_lines("confession", speakable_lines(case, suspects["niece"], every)) == ()
    cornered = speakable_lines(case, suspects["niece"], every, confession=True)
    assert [line.id for line in chosen_lines("k-niece-lock, confession", cornered)] == [CONFESSION_ID]
    with pytest.raises(ValueError, match="cannot confess"):
        speakable_lines(case, suspects["maid"], every, confession=True)


def test_heard_line_ids_find_the_lines_an_answer_holds_in_order() -> None:
    _, suspects = _load("jade-crown")
    maid = suspects["maid"]
    study = _line(maid, "k-maid-study")

    assert heard_line_ids(maid, f"{maid.deflections[0]} {study}") == ("deflect-1", "k-maid-study")
    assert heard_case_lines(maid, f"{maid.deflections[0]} {study}") == (study,)
    assert heard_line_ids(maid, study.replace(" ", "")) == ("k-maid-study",)
    assert heard_line_ids(maid, "모델이 제 말로 지어낸 대답이에요.") == ()


def test_the_prompt_lists_the_lines_on_offer_with_their_ids() -> None:
    case, suspects = _load("jade-crown")
    maid = suspects["maid"]

    prompt = render_line_prompt(case, maid, frozenset({"clue-back-door"}), LINES_TEMPLATE)

    assert f"- k-maid-back-door (부엌 뒷문 발자국 이야기에): {_line(maid, 'k-maid-back-door')}" in prompt
    assert "k-maid-night" not in prompt
    assert f"- {case.clue('clue-back-door').title}: {case.clue('clue-back-door').summary}" in prompt
    assert "{{" not in prompt


def test_a_fact_may_not_take_an_id_the_lines_use(tmp_path: Path) -> None:
    copy = tmp_path / "jade-crown"
    shutil.copytree(CASES_DIR / "jade-crown", copy)
    maid_file = copy / "suspects" / "maid.json"
    maid_file.write_text(maid_file.read_text(encoding="utf-8").replace('"k-maid-crown"', '"confession"'), encoding="utf-8")

    problems = validate_case(copy, TEMPLATE_PATH.read_text(encoding="utf-8"), schema_dir=REPO_ROOT / "content" / "schema")

    assert "maid/confession: 대사를 고를 때 앱이 쓰는 id입니다. 다른 id를 쓰세요" in problems


def test_a_fact_id_with_characters_the_reply_is_not_matched_on_is_refused(tmp_path: Path) -> None:
    copy = tmp_path / "jade-crown"
    shutil.copytree(CASES_DIR / "jade-crown", copy)
    maid_file = copy / "suspects" / "maid.json"
    maid_file.write_text(maid_file.read_text(encoding="utf-8").replace('"k-maid-crown"', '"k-maid.crown"'), encoding="utf-8")

    problems = validate_case(copy, TEMPLATE_PATH.read_text(encoding="utf-8"), schema_dir=REPO_ROOT / "content" / "schema")

    assert "maid/k-maid.crown: id에는 영문자, 숫자, '_', '-'만 씁니다" in problems
    case = load_case(copy, schema_dir=REPO_ROOT / "content" / "schema")
    maid = load_suspect(copy / "suspects" / "maid.json", case)
    assert line_problems([maid]) == ["maid/k-maid.crown has characters an id may not have"]


def test_the_mock_proxy_names_the_first_line_of_a_prompt_that_picks_lines() -> None:
    case, suspects = _load("jade-crown")
    system = render_line_prompt(case, suspects["maid"], frozenset(), LINES_TEMPLATE)
    upstream = mock_upstream({"interrogation": "그 시각에는 저장고에 있었습니다.", "summary": "요약"}, sleep=lambda _: None)
    body = {
        "task": "interrogation",
        "system": system,
        "messages": [{"role": "user", "content": "옥분 씨, 그날 밤 어디 있었어요?"}],
        "requestId": "abc",
    }

    events = list(upstream(parse_request(json.dumps(body).encode())))

    assert "".join(event.data["text"] for event in events if event.name == "delta") == "k-maid-night"
