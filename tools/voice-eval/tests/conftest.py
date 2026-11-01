from pathlib import Path

import pytest

from voice_eval.content import Case, Probe, Suspect, load_case, load_probes, load_suspect

TOOL_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = TOOL_ROOT.parents[1]
CASE_DIR = REPO_ROOT / "content" / "cases" / "demo-sapphire"
TEMPLATE_PATH = TOOL_ROOT / "prompts" / "suspect_system.ko.md"
PROBES_PATH = TOOL_ROOT / "probes" / "butler.json"


@pytest.fixture(scope="session")
def case() -> Case:
    return load_case(CASE_DIR)


@pytest.fixture(scope="session")
def butler(case: Case) -> Suspect:
    return load_suspect(CASE_DIR / "suspects" / "butler.json", case)


@pytest.fixture(scope="session")
def template() -> str:
    return TEMPLATE_PATH.read_text(encoding="utf-8")


@pytest.fixture(scope="session")
def probes(case: Case) -> tuple[Probe, ...]:
    return load_probes(PROBES_PATH, case)
