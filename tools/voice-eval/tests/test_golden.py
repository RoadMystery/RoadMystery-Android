import json

from conftest import CASE_DIR, TEMPLATE_PATH, TOOL_ROOT

from voice_eval.golden import build_golden

GOLDEN_PATH = TOOL_ROOT / "golden" / "voice-agent-golden.json"


def _fresh() -> dict:
    return build_golden(
        CASE_DIR,
        TEMPLATE_PATH.read_text(encoding="utf-8"),
        (TOOL_ROOT / "prompts" / "summary_system.ko.md").read_text(encoding="utf-8"),
        TOOL_ROOT / "probes" / "summary.json",
    )


def test_committed_golden_file_matches_current_content() -> None:
    """If this fails, content or a template changed: run `python -m voice_eval golden`."""
    assert json.loads(GOLDEN_PATH.read_text(encoding="utf-8")) == _fresh()


def test_golden_covers_every_suspect_variant_and_routing_case() -> None:
    golden = _fresh()
    assert {entry["suspect"] for entry in golden["prompts"]} == {"butler", "niece", "cook"}
    assert {entry["variant"] for entry in golden["prompts"]} == {"cover", "truth"}
    assert len(golden["prompts"]) == 3 * 6 * 2
    assert any(entry["expected"] is None for entry in golden["identify"])
    assert golden["summaries"] and golden["sentences"]
    ratios = [entry["ratio"] for entry in golden["similarity"]]
    assert any(0.85 <= ratio < 1 for ratio in ratios) and any(0 < ratio < 0.85 for ratio in ratios)
    hits = [entry["hits"] for entry in golden["guard"]]
    assert all(hits[:-1]) and hits[-1] == []
    topics = golden["topics"]
    assert any(entry["topics"] for entry in topics) and any(not entry["topics"] for entry in topics)
    assert all(("지금 꺼낸" in entry["prompt"]) == bool(entry["topics"]) for entry in topics)
