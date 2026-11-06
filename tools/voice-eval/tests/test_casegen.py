"""casegen asks for a case from the authoring rules alone and lays the reply out as files the app loads."""

import json
from pathlib import Path

import pytest
from conftest import REPO_ROOT, TEMPLATE_PATH

from voice_eval.casegen import TIPS_HEADING, case_request, fix_message, parse_case_reply, write_case
from voice_eval.validate import validate_case

JADE = REPO_ROOT / "content" / "cases" / "jade-crown"
SCHEMA_DIR = REPO_ROOT / "content" / "schema"
RULES = (REPO_ROOT / "docs" / "voice-agent" / "scenario-rules.md").read_text(encoding="utf-8")


def _request(style: str) -> tuple[str, str]:
    return case_request(style, "harbor-test", "비 오는 밤 항구 사무실에서 손금고가 사라졌다.", RULES, SCHEMA_DIR)


def _jade_reply() -> str:
    case = json.loads((JADE / "case.json").read_text(encoding="utf-8"))
    suspects = [json.loads(path.read_text(encoding="utf-8")) for path in sorted((JADE / "suspects").glob("*.json"))]
    return "사건입니다.\n```json\n" + json.dumps({"case": case, "suspects": suspects}, ensure_ascii=False) + "\n```"


@pytest.mark.parametrize("style", ["ai", "user"])
def test_the_request_carries_the_rules_the_schemas_and_the_brief_and_no_existing_case(style: str) -> None:
    system, user = _request(style)

    assert "unlockedBy" in system and "forbiddenPhrases" in system
    assert "# 시나리오 작성 규칙" in system
    assert "harbor-test" in user and "손금고" in user
    for word in ("서준기", "윤하린", "김옥분", "화관", "사파이어"):
        assert word not in system + user


def test_a_user_writes_from_the_rules_that_must_be_kept_only() -> None:
    assert TIPS_HEADING in _request("ai")[0]
    assert TIPS_HEADING not in _request("user")[0]
    assert "## 써도 되고 안 써도 되는 칸" in _request("user")[0]


def test_an_unknown_style_is_refused() -> None:
    with pytest.raises(KeyError):
        _request("expert")


def test_the_reply_is_the_json_block_and_anything_else_is_refused() -> None:
    case, suspects = parse_case_reply(_jade_reply())
    assert case["id"] == "jade-crown"
    assert [suspect["id"] for suspect in suspects] == ["maid", "niece", "son"]

    for reply in ("사건을 쓰지 못했습니다.", "```json\n[1, 2]\n```", '```json\n{"case": {}}\n```'):
        with pytest.raises(ValueError):
            parse_case_reply(reply)


def test_the_files_written_load_and_pass_the_rules(tmp_path: Path) -> None:
    case, suspects = parse_case_reply(_jade_reply())

    case_dir = write_case(tmp_path, "jade-copy", case, suspects)

    assert case_dir == tmp_path / "cases" / "jade-copy"
    written = json.loads((case_dir / "case.json").read_text(encoding="utf-8"))
    assert written["id"] == "jade-copy"
    assert written["$schema"] == "../../schema/case.schema.json"
    assert (case_dir / "suspects" / "son.json").read_bytes().count(b"\r") == 0
    assert validate_case(case_dir, TEMPLATE_PATH.read_text(encoding="utf-8"), schema_dir=SCHEMA_DIR) == []


def test_a_suspect_id_that_is_no_file_name_is_refused(tmp_path: Path) -> None:
    case, suspects = parse_case_reply(_jade_reply())
    suspects[0]["id"] = "../maid"

    with pytest.raises(ValueError):
        write_case(tmp_path, "jade-copy", case, suspects)


def test_the_fix_message_lists_every_problem() -> None:
    message = fix_message(["단서 clue-a: 힌트가 없습니다", "maid/k-1: 대사가 없습니다"])

    assert "- 단서 clue-a: 힌트가 없습니다" in message
    assert "- maid/k-1: 대사가 없습니다" in message
