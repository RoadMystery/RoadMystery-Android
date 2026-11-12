import json
from pathlib import Path

import pytest
from conftest import CASE_DIR, REPO_ROOT, TEMPLATE_PATH, TOOL_ROOT

import voice_eval.__main__ as cli
from voice_eval.content import ContentError, load_case, load_suspect
from voice_eval.golden import GOLDEN_CASE, GOLDEN_VERSION, PRESSURE_STAGES, build_golden
from voice_eval.prompt import pressure_line
from voice_eval.summary import MAX_TESTIMONIES

GOLDEN_PATH = TOOL_ROOT / "golden" / "voice-agent-golden.json"
GOLDEN_CASE_DIR = REPO_ROOT / "content" / "cases" / GOLDEN_CASE
PRESSURE_HEADING = "# 수사가 깊어진 지금 하게 되는 말 (어울릴 때만)"


def _fresh(case_dir: Path = GOLDEN_CASE_DIR) -> dict:
    return build_golden(
        case_dir,
        TEMPLATE_PATH.read_text(encoding="utf-8"),
        (TOOL_ROOT / "prompts" / "summary_system.ko.md").read_text(encoding="utf-8"),
        TOOL_ROOT / "probes" / GOLDEN_CASE / "summary.json",
        (TOOL_ROOT / "prompts" / "suspect_lines.ko.md").read_text(encoding="utf-8"),
    )


def test_committed_golden_file_matches_current_content() -> None:
    """If this fails, content or a template changed: run `python -m voice_eval golden`."""
    assert json.loads(GOLDEN_PATH.read_text(encoding="utf-8")) == _fresh()


def test_the_golden_command_writes_the_committed_file_byte_for_byte(tmp_path: Path) -> None:
    # Through the parser rather than main(), so nothing reads a .env file.
    out = tmp_path / "golden.json"
    args = cli._parser().parse_args(["golden", "--out", str(out)])

    assert args.handler(args) == 0
    written = out.read_bytes()
    assert b"\r" not in written
    # Git may check the committed file out with CRLF (core.autocrlf); that is not the command's doing.
    assert written == GOLDEN_PATH.read_bytes().replace(b"\r\n", b"\n")


def test_the_golden_version_is_the_one_the_kotlin_port_checks() -> None:
    # Kotlin GoldenParityTest checks the same number; version 2 added the "gathering" prompt, version 3
    # the "aware" variant, the confession and the dropped openings, version 4 the answers made of lines.
    assert json.loads(GOLDEN_PATH.read_text(encoding="utf-8"))["version"] == GOLDEN_VERSION == 4


def test_golden_covers_the_lines_on_offer_their_prompts_and_choices() -> None:
    golden = _fresh()
    # Suspects x (no clue, each clue alone, every clue), the pressure stages, and the cornered culprit.
    assert len(golden["lines"]) == 3 * 6 + 3 * len(PRESSURE_STAGES) + 1
    cornered = [entry for entry in golden["lines"] if entry["confession"]]
    assert [(entry["suspect"], entry["stage"]) for entry in cornered] == [("niece", "ready")]
    assert "confession" in [line["id"] for line in cornered[0]["lines"]]
    chosen = {entry["reply"]: entry["chosen"] for entry in golden["line_choices"] if entry["suspect"] == "maid"}
    assert chosen["k-maid-night 그리고 범인은 하린 아가씨예요."] == ["k-maid-night"]
    assert chosen["k-maid-back-door"] == []
    assert next(entry["ids"] for entry in golden["heard"] if entry["suspect"] == "son") == ["k-son-shoes", "k-son-friend"]


def test_golden_samples_only_build_for_the_case_the_app_ships() -> None:
    with pytest.raises(ContentError, match=GOLDEN_CASE):
        _fresh(CASE_DIR)


def test_golden_covers_every_suspect_variant_and_routing_case() -> None:
    golden = _fresh()
    assert {entry["suspect"] for entry in golden["prompts"]} == {"son", "niece", "maid"}
    assert {entry["variant"] for entry in golden["prompts"]} == {"cover", "aware", "truth"}
    # Suspects x (no clue, each clue alone, every clue) x variants; staged entries come on top.
    assert len([entry for entry in golden["prompts"] if "stage" not in entry]) == 3 * 6 * 3
    assert {entry["expected"] for entry in golden["identify"]} == {"son", "niece", "maid", None}
    assert golden["summaries"] and golden["sentences"]
    assert any(len(entry["testimonies"]) > MAX_TESTIMONIES for entry in golden["summaries"])
    ratios = [entry["ratio"] for entry in golden["similarity"]]
    assert any(0.85 <= ratio < 1 for ratio in ratios) and any(0 < ratio < 0.85 for ratio in ratios)
    hits = [entry["hits"] for entry in golden["guard"]]
    assert all(hits[:-1]) and hits[-1] == []
    readouts = [entry["readout"] for entry in golden["readout"]]
    assert any(readouts) and not all(readouts)
    assert any(entry["readout"] and not entry["spoken_readout"] for entry in golden["readout"])
    assert any(entry["spoken"] == "" for entry in golden["readout"])
    named = [entry for entry in golden["readout"] if entry["speaker"]]
    assert any(entry["readout"] for entry in named) and any(not entry["readout"] for entry in named)
    topics = golden["topics"]
    assert any(entry["topics"] for entry in topics) and any(not entry["topics"] for entry in topics)
    assert all(("지금 꺼낸" in entry["prompt"]) == bool(entry["topics"]) for entry in topics)


def test_golden_has_the_late_stage_prompts_of_every_suspect_with_pressure_lines() -> None:
    golden = _fresh()
    case = load_case(GOLDEN_CASE_DIR)
    every_clue = [clue.id for clue in case.clues]
    staged = [entry for entry in golden["prompts"] if entry.get("stage") in PRESSURE_STAGES]
    every_clue_cover = {
        entry["suspect"]: entry["prompt"]
        for entry in golden["prompts"]
        if "stage" not in entry and entry["unlocked"] == every_clue and entry["variant"] == "cover"
    }

    assert {(entry["suspect"], entry["stage"]) for entry in staged} == {
        (suspect, stage) for suspect in ("son", "niece", "maid") for stage in ("pressing", "ready")
    }
    assert not any(
        PRESSURE_HEADING in entry["prompt"]
        for entry in golden["prompts"]
        if entry.get("stage") not in PRESSURE_STAGES
    )
    for entry in staged:
        suspect = load_suspect(GOLDEN_CASE_DIR / "suspects" / f"{entry['suspect']}.json", case)
        section = f"\n\n{PRESSURE_HEADING}\n- {pressure_line(suspect, entry['stage'])}"
        # The app gets this far only with every clue found, and the stage adds nothing but its line.
        assert (entry["unlocked"], entry["variant"]) == (every_clue, "cover")
        assert section in entry["prompt"]
        assert entry["prompt"].replace(section, "", 1) == every_clue_cover[entry["suspect"]]


def test_golden_has_the_culprit_cornered_and_not_cornered() -> None:
    # Only jade-crown's culprit, at "ready", by a question about the clue that broke her story, and from
    # then on in the same conversation; the app sends that prompt without pressure lines.
    golden = _fresh()
    case = load_case(GOLDEN_CASE_DIR)
    niece = load_suspect(GOLDEN_CASE_DIR / "suspects" / "niece.json", case)
    entries = golden["confessions"]

    assert [entry["opens"] for entry in entries] == [True, False, False, False, True, False]
    for entry in entries:
        cornered = entry["opens"]
        assert (niece.confession in entry["prompt"]) is cornered
        assert ("# 다 들킨 지금" in entry["prompt"]) is cornered
        assert ("# 너만 아는 것" in entry["prompt"]) is (entry["suspect"] == "niece" and not cornered)
        assert PRESSURE_HEADING not in entry["prompt"]


def test_golden_has_openings_dropped_and_kept() -> None:
    spoken = [(entry["sentence"], entry["spoken"]) for entry in _fresh()["openings"]]

    assert any(sentence != said for sentence, said in spoken)
    assert any(sentence == said for sentence, said in spoken)
    assert ("어머.", "") in spoken


def test_golden_has_one_gathering_prompt_and_it_is_the_stage_less_one() -> None:
    # A persona's pressure lines stay out until "pressing", so with some clues but not all found the stage
    # changes nothing; the Kotlin port renders this entry at its stage too.
    golden = _fresh()
    case = load_case(GOLDEN_CASE_DIR)
    every_clue = {clue.id for clue in case.clues}
    (entry,) = [entry for entry in golden["prompts"] if entry.get("stage") == "gathering"]
    suspect = load_suspect(GOLDEN_CASE_DIR / "suspects" / f"{entry['suspect']}.json", case)
    stage_less = [
        other["prompt"]
        for other in golden["prompts"]
        if "stage" not in other
        and (other["suspect"], other["unlocked"], other["variant"]) == (entry["suspect"], entry["unlocked"], "cover")
    ]

    assert suspect.pressure and entry["variant"] == "cover"
    assert entry["unlocked"] and set(entry["unlocked"]) < every_clue
    assert stage_less == [entry["prompt"]]
    assert PRESSURE_HEADING not in entry["prompt"]
