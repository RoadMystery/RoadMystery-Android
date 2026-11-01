import json
import shutil
from pathlib import Path

import pytest
from conftest import CASE_DIR, PROBES_PATH

from voice_eval.content import (
    KOREAN_SPEAKERS,
    Case,
    ContentError,
    Suspect,
    load_case,
    load_probes,
    load_suspect,
)

BUTLER_PATH = CASE_DIR / "suspects" / "butler.json"


def _write_variant(tmp_path: Path, source: Path, mutate) -> Path:
    data = json.loads(source.read_text(encoding="utf-8"))
    mutate(data)
    target = tmp_path / source.name
    target.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return target


def test_case_loads_cast_clues_and_solution(case: Case) -> None:
    assert case.id == "demo-sapphire"
    assert case.theme == "family"
    assert [clue.id for clue in case.clues] == [
        "clue-fusebox",
        "clue-wet-gloves",
        "clue-cellar-key",
        "clue-pawn-ticket",
    ]
    assert case.member("butler").name == "한도윤"
    assert case.solution.culprit == "butler"
    assert "동백" in case.forbidden_phrases


def test_suspect_takes_name_and_role_from_cast(butler: Suspect) -> None:
    assert butler.name == "한도윤"
    assert butler.role == "집사"
    assert butler.pleos_speaker in KOREAN_SPEAKERS
    assert len(butler.secret) == 3


def test_unknown_clue_lookup_raises_key_error(case: Case) -> None:
    with pytest.raises(KeyError):
        case.clue("clue-does-not-exist")


def test_unlocked_by_must_reference_existing_clue(tmp_path: Path, case: Case) -> None:
    def mutate(data: dict) -> None:
        data["knowledge"]["unlockable"][0]["unlockedBy"] = ["clue-nope"]

    path = _write_variant(tmp_path, BUTLER_PATH, mutate)
    with pytest.raises(ContentError, match="clue-nope"):
        load_suspect(path, case)


def test_schema_violation_names_missing_field(tmp_path: Path, case: Case) -> None:
    path = _write_variant(tmp_path, BUTLER_PATH, lambda data: data.pop("fallbackLines"))
    with pytest.raises(ContentError, match="fallbackLines"):
        load_suspect(path, case)


def test_suspect_must_be_a_suspect_in_cast(tmp_path: Path, case: Case) -> None:
    path = _write_variant(tmp_path, BUTLER_PATH, lambda data: data.update(id="chairman"))
    with pytest.raises(ContentError, match="chairman"):
        load_suspect(path, case)


def test_relationship_target_must_exist(tmp_path: Path, case: Case) -> None:
    def mutate(data: dict) -> None:
        data["relationships"].append({"target": "ghost", "attitude": "모름"})

    path = _write_variant(tmp_path, BUTLER_PATH, mutate)
    with pytest.raises(ContentError, match="ghost"):
        load_suspect(path, case)


def test_solution_culprit_must_be_cast_member(tmp_path: Path) -> None:
    case_dir = tmp_path / "case"
    case_dir.mkdir()
    _write_variant(
        case_dir,
        CASE_DIR / "case.json",
        lambda data: data["solution"].update(culprit="ghost"),
    )
    schema_dir = CASE_DIR.parent.parent / "schema"
    with pytest.raises(ContentError, match="ghost"):
        load_case(case_dir, schema_dir=schema_dir)


def test_probes_load_and_reference_known_clues(case: Case) -> None:
    probes = load_probes(PROBES_PATH, case)
    assert len(probes) >= 15
    known = {clue.id for clue in case.clues}
    assert all(set(probe.unlocked) <= known for probe in probes)
    assert {probe.category for probe in probes} >= {"in_scope", "spoiler", "jailbreak", "off_topic"}


def test_probe_with_unknown_clue_is_rejected(tmp_path: Path, case: Case) -> None:
    def mutate(data: dict) -> None:
        data["probes"][0]["unlocked"] = ["clue-nope"]

    path = _write_variant(tmp_path, PROBES_PATH, mutate)
    with pytest.raises(ContentError, match="clue-nope"):
        load_probes(path, case)


def test_probe_history_is_checked_when_loading(tmp_path: Path, case: Case) -> None:
    def mutate(data: dict) -> None:
        data["probes"][0]["history"] = [{"role": "user", "content": "먼저 한 질문"}]

    path = _write_variant(tmp_path, PROBES_PATH, mutate)
    with pytest.raises(ContentError, match="assistant"):
        load_probes(path, case)


def test_probe_ids_must_be_unique(tmp_path: Path, case: Case) -> None:
    def mutate(data: dict) -> None:
        data["probes"][1]["id"] = data["probes"][0]["id"]

    path = _write_variant(tmp_path, PROBES_PATH, mutate)
    with pytest.raises(ContentError, match="duplicate"):
        load_probes(path, case)


def test_case_dir_copy_loads_with_default_schema_location(tmp_path: Path) -> None:
    content_root = tmp_path / "content"
    shutil.copytree(CASE_DIR.parent.parent, content_root)
    loaded = load_case(content_root / "cases" / "demo-sapphire")
    assert loaded.id == "demo-sapphire"
