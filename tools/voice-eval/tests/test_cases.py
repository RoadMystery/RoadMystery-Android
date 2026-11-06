"""Checks every case under content/cases/ must pass, so a new script is held to the clue-design rules
measured on demo-sapphire (docs/voice-agent/llm-evaluation.md, docs/voice-agent/scenario-jade-crown.md).
The rules live in voice_eval/validate.py, which also checks a case before it is added
(docs/voice-agent/scenario-rules.md)."""

import pytest
from conftest import REPO_ROOT, TEMPLATE_PATH, TOOL_ROOT

from voice_eval import validate
from voice_eval.content import load_case, load_probes
from voice_eval.identify import identify_suspect, suspect_aliases

CASES_DIR = REPO_ROOT / "content" / "cases"
CASE_IDS = sorted(path.name for path in CASES_DIR.iterdir() if (path / "case.json").exists())
TEMPLATE = TEMPLATE_PATH.read_text(encoding="utf-8")
PROBE_DIRS = {
    "demo-sapphire": (TOOL_ROOT / "probes", TOOL_ROOT / "probes" / "locked"),
}
"""Where a case's questions are when not in probes/<case id>/ (demo-sapphire's predate that layout)."""
EXEMPTIONS = {
    # Measured before first-person lines and narrator hints, so its prompts show the answer keys and its
    # suspects deflect. The niece's fusebox reaction names the cellar key; one phase-1 leak grew out of it.
    # Kept so the measured app prompts stay as they were; the real script replaces this case.
    "demo-sapphire": validate.Exemptions(measured=True, cross_clue_lines=frozenset({("niece", "k-niece-fusebox")})),
}


def _subject(case_id: str) -> validate.Subject:
    return validate.load_subject(CASES_DIR / case_id, TEMPLATE, EXEMPTIONS.get(case_id, validate.NO_EXEMPTIONS))


def _probe_dirs(case_id: str) -> tuple:
    return PROBE_DIRS.get(case_id, (TOOL_ROOT / "probes" / case_id,))


@pytest.mark.parametrize("case_id", CASE_IDS)
def test_every_case_has_its_questions(case_id: str) -> None:
    assert _probe_dirs(case_id)[0].is_dir()


def test_exemptions_and_probe_dirs_name_cases_that_exist() -> None:
    assert set(EXEMPTIONS) <= set(CASE_IDS)
    assert set(PROBE_DIRS) <= set(CASE_IDS)


@pytest.mark.parametrize(
    ("case_id", "check"),
    [
        pytest.param(case_id, check, id=f"{case_id}-{check.__name__}")
        for case_id in CASE_IDS
        for check in validate.CHECKS
    ],
)
def test_every_case_keeps_every_rule(case_id: str, check: validate.Check) -> None:
    assert check(_subject(case_id)) == []


@pytest.mark.parametrize("case_id", CASE_IDS)
def test_every_suspect_has_probes_that_load(case_id: str) -> None:
    case = load_case(CASES_DIR / case_id)
    first, *more = _probe_dirs(case_id)

    for member in case.cast:
        if member.is_suspect:
            assert load_probes(first / f"{member.id}.json", case)
    for directory in more:
        for path in sorted(directory.glob("*.json")):
            assert load_probes(path, case)


@pytest.mark.parametrize(
    ("utterance", "current", "expected"),
    [
        ("준기 씨 새벽에 서재엔 왜 갔어요", None, "son"),
        ("하린 아가씨한테 물어봐", None, "niece"),
        ("가정부한테 그날 밤 얘기 물어봐", None, "maid"),
        ("도련님은 왜 화관을 들고 있었어요", None, "son"),
        ("하린씨 문단쏙 돌았어요", None, "niece"),
        ("옥분이 뒷문으로 나갔어요?", "son", "son"),
        ("아버지랑 왜 다퉜어요?", "son", "son"),
        ("전무님은 괜찮으세요?", None, None),
        ("준기씨한테 맨발 자국 얘기 물어봐", "maid", "son"),
    ],
)
def test_jade_crown_questions_reach_the_right_suspect(
    utterance: str, current: str | None, expected: str | None
) -> None:
    case = load_case(CASES_DIR / "jade-crown")

    assert identify_suspect(utterance, suspect_aliases(case), current) == expected
