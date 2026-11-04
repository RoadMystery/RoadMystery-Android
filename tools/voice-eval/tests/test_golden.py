import json
from pathlib import Path

import pytest
from conftest import CASE_DIR, REPO_ROOT, TEMPLATE_PATH, TOOL_ROOT

from voice_eval.content import ContentError
from voice_eval.golden import GOLDEN_CASE, build_golden
from voice_eval.summary import MAX_TESTIMONIES

GOLDEN_PATH = TOOL_ROOT / "golden" / "voice-agent-golden.json"
GOLDEN_CASE_DIR = REPO_ROOT / "content" / "cases" / GOLDEN_CASE


def _fresh(case_dir: Path = GOLDEN_CASE_DIR) -> dict:
    return build_golden(
        case_dir,
        TEMPLATE_PATH.read_text(encoding="utf-8"),
        (TOOL_ROOT / "prompts" / "summary_system.ko.md").read_text(encoding="utf-8"),
        TOOL_ROOT / "probes" / GOLDEN_CASE / "summary.json",
    )


def test_committed_golden_file_matches_current_content() -> None:
    """If this fails, content or a template changed: run `python -m voice_eval golden`."""
    assert json.loads(GOLDEN_PATH.read_text(encoding="utf-8")) == _fresh()


def test_golden_samples_only_build_for_the_case_the_app_ships() -> None:
    with pytest.raises(ContentError, match=GOLDEN_CASE):
        _fresh(CASE_DIR)


def test_golden_covers_every_suspect_variant_and_routing_case() -> None:
    golden = _fresh()
    assert {entry["suspect"] for entry in golden["prompts"]} == {"son", "niece", "maid"}
    assert {entry["variant"] for entry in golden["prompts"]} == {"cover", "truth"}
    assert len(golden["prompts"]) == 3 * 6 * 2
    assert {entry["expected"] for entry in golden["identify"]} == {"son", "niece", "maid", None}
    assert golden["summaries"] and golden["sentences"]
    assert any(len(entry["testimonies"]) > MAX_TESTIMONIES for entry in golden["summaries"])
    ratios = [entry["ratio"] for entry in golden["similarity"]]
    assert any(0.85 <= ratio < 1 for ratio in ratios) and any(0 < ratio < 0.85 for ratio in ratios)
    hits = [entry["hits"] for entry in golden["guard"]]
    assert all(hits[:-1]) and hits[-1] == []
    topics = golden["topics"]
    assert any(entry["topics"] for entry in topics) and any(not entry["topics"] for entry in topics)
    assert all(("지금 꺼낸" in entry["prompt"]) == bool(entry["topics"]) for entry in topics)
