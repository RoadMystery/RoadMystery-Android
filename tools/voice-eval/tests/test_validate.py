"""validate_case reports what a written or generated case gets wrong, as messages and without raising."""

import json
import shutil
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest
from conftest import REPO_ROOT, TEMPLATE_PATH

from voice_eval import __main__ as cli
from voice_eval.validate import NO_EXEMPTIONS, Exemptions, validate_case

JADE = REPO_ROOT / "content" / "cases" / "jade-crown"
SCHEMA_DIR = REPO_ROOT / "content" / "schema"
TEMPLATE = TEMPLATE_PATH.read_text(encoding="utf-8")


@pytest.fixture
def jade_copy(tmp_path: Path) -> Path:
    target = tmp_path / "cases" / "jade-crown"
    shutil.copytree(JADE, target)
    return target


def _edit(path: Path, change: Callable[[Any], None]) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    change(data)
    path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")


def _case_json(case_dir: Path) -> Any:
    return json.loads((case_dir / "case.json").read_text(encoding="utf-8"))


def _problems(case_dir: Path, exemptions: Exemptions = NO_EXEMPTIONS) -> list[str]:
    return validate_case(case_dir, TEMPLATE, exemptions, schema_dir=SCHEMA_DIR)


def test_the_committed_case_passes(jade_copy: Path) -> None:
    assert _problems(jade_copy) == []


def test_a_case_that_does_not_load_is_one_problem_not_an_exception(jade_copy: Path) -> None:
    (jade_copy / "case.json").write_text("{", encoding="utf-8")

    problems = _problems(jade_copy)

    assert len(problems) == 1
    assert "JSON" in problems[0]


def test_a_public_line_naming_a_clue_keyword_is_reported_with_its_fact(jade_copy: Path) -> None:
    keyword = _case_json(jade_copy)["clues"][0]["keywords"][0]
    _edit(
        jade_copy / "suspects" / "son.json",
        lambda data: data["knowledge"]["public"][0].update(line=f"{keyword} 얘기는 저도 몰라요."),
    )

    assert any("k-son-crown" in problem and keyword in problem for problem in _problems(jade_copy))


def test_a_new_case_needs_a_line_for_every_fact_but_a_measured_one_may_leave_it_out(jade_copy: Path) -> None:
    _edit(jade_copy / "suspects" / "son.json", lambda data: data["knowledge"]["public"][0].pop("line"))

    assert any("k-son-crown" in problem for problem in _problems(jade_copy))
    assert not any("k-son-crown" in problem for problem in _problems(jade_copy, Exemptions(measured=True)))


def test_a_line_ending_in_plain_da_reads_as_an_instruction(jade_copy: Path) -> None:
    _edit(
        jade_copy / "suspects" / "son.json",
        lambda data: data["knowledge"]["public"][0].update(line="나는 그날 일찍 잠들었다."),
    )

    assert any("k-son-crown" in problem for problem in _problems(jade_copy))


def test_a_hint_naming_another_clue_is_reported_with_its_clue(jade_copy: Path) -> None:
    clues = _case_json(jade_copy)["clues"]
    _edit(
        jade_copy / "case.json",
        lambda data: data["clues"][0].update(hint=f"탐정님, {clues[1]['title']} 쪽을 눈여겨보세요."),
    )

    assert any(clues[0]["id"] in problem for problem in _problems(jade_copy))


def test_a_case_without_a_key_reaction_is_reported(jade_copy: Path) -> None:
    for path in sorted((jade_copy / "suspects").glob("*.json")):
        _edit(path, lambda data: [fact.pop("key", None) for fact in data["knowledge"]["unlockable"]])

    assert any("핵심 반응" in problem for problem in _problems(jade_copy))


def test_validate_command_exits_0_for_a_passing_case_and_1_with_the_problems_otherwise(
    jade_copy: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    command = ["validate", "--case-dir", str(jade_copy), "--schema-dir", str(SCHEMA_DIR)]
    assert cli.main(command) == 0
    capsys.readouterr()

    _edit(jade_copy / "suspects" / "son.json", lambda data: data["knowledge"]["public"][0].pop("line"))

    assert cli.main(command) == 1
    assert "k-son-crown" in capsys.readouterr().out
