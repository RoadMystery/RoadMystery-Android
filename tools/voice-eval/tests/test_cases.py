"""Checks every case under content/cases/ must pass, so a new script is held to the clue-design rules
measured on demo-sapphire (docs/voice-agent/llm-evaluation.md, docs/voice-agent/scenario-jade-crown.md)."""

import itertools

import pytest
from conftest import REPO_ROOT, TEMPLATE_PATH, TOOL_ROOT

from voice_eval.content import Case, Suspect, load_case, load_probes, load_suspect
from voice_eval.identify import identify_suspect, suspect_aliases
from voice_eval.metrics import phrase_hits
from voice_eval.prompt import allowed_facts, render_system_prompt

CASES_DIR = REPO_ROOT / "content" / "cases"
CASE_IDS = sorted(path.name for path in CASES_DIR.iterdir() if (path / "case.json").exists())
NARRATOR_SPEAKER = "haeun"
"""The app's F-06 narrator voice (AgentConfig.narratorSpeaker), so no suspect may use it."""
PROBE_DIRS = {
    "demo-sapphire": (TOOL_ROOT / "probes", TOOL_ROOT / "probes" / "locked"),
    "jade-crown": (TOOL_ROOT / "probes" / "jade-crown",),
}
KNOWN_CROSS_CLUE_LINES = {
    # The niece's fusebox reaction names the cellar key; one phase-1 leak grew out of it. Kept so the
    # measured app prompts stay as they were; the real script replaces this case.
    ("demo-sapphire", "niece", "k-niece-fusebox"),
}


def _load(case_id: str) -> tuple[Case, dict[str, Suspect]]:
    case = load_case(CASES_DIR / case_id)
    suspects = {
        member.id: load_suspect(CASES_DIR / case_id / "suspects" / f"{member.id}.json", case)
        for member in case.cast
        if member.is_suspect
    }
    return case, suspects


def _shown_before_any_clue(case: Case, suspect: Suspect) -> list[str]:
    """Persona text in every prompt of [suspect], whatever the player has found."""
    return [
        case.setting,
        *case.public_facts,
        suspect.background,
        *suspect.personality,
        suspect.register,
        *suspect.speech_rules,
        *suspect.speech_examples,
        *(fact.text for fact in suspect.public),
        *(relationship.attitude for relationship in suspect.relationships),
        *suspect.deflections,
    ]


def _keywords_except(case: Case, clue_ids: tuple[str, ...]) -> list[str]:
    return [keyword for clue in case.clues if clue.id not in clue_ids for keyword in clue.keywords]


def test_every_case_has_probe_dirs() -> None:
    assert set(PROBE_DIRS) == set(CASE_IDS)


@pytest.mark.parametrize("case_id", CASE_IDS)
def test_every_suspect_loads_with_its_own_voice(case_id: str) -> None:
    _, suspects = _load(case_id)
    voices = [suspect.pleos_speaker for suspect in suspects.values()]

    assert suspects
    assert len(set(voices)) == len(voices)
    assert NARRATOR_SPEAKER not in voices


@pytest.mark.parametrize("case_id", CASE_IDS)
def test_cast_aliases_are_unique(case_id: str) -> None:
    case, _ = _load(case_id)
    aliases = [alias for member in case.cast for alias in member.aliases]

    assert len(aliases) == len(set(aliases))


@pytest.mark.parametrize("case_id", CASE_IDS)
def test_clue_keywords_stay_out_of_text_shown_before_the_clue(case_id: str) -> None:
    case, suspects = _load(case_id)
    every_keyword = _keywords_except(case, ())

    # A found clue's title and summary go into the prompt, so they must not name another clue.
    for clue in case.clues:
        assert not phrase_hits(f"{clue.title} {clue.summary}", _keywords_except(case, (clue.id,))), clue.id
    for suspect in suspects.values():
        for text in _shown_before_any_clue(case, suspect):
            assert not phrase_hits(text, every_keyword), text
        for fact in suspect.unlockable:
            if (case_id, suspect.id, fact.id) in KNOWN_CROSS_CLUE_LINES:
                continue
            assert not phrase_hits(fact.text, _keywords_except(case, fact.unlocked_by)), fact.id
        for line in suspect.before_found:
            assert not phrase_hits(line.text, _keywords_except(case, (line.clue,))), line.clue


@pytest.mark.parametrize("case_id", CASE_IDS)
def test_no_persona_line_trips_the_spoiler_guard(case_id: str) -> None:
    case, suspects = _load(case_id)

    for suspect in suspects.values():
        lines = [
            *_shown_before_any_clue(case, suspect),
            *(fact.text for fact in suspect.unlockable),
            *(line.text for line in suspect.before_found),
        ]
        for text in lines:
            assert not phrase_hits(text, case.forbidden_phrases), text


@pytest.mark.parametrize("case_id", CASE_IDS)
def test_cover_prompts_keep_secrets_and_the_solution_for_every_set_of_found_clues(case_id: str) -> None:
    case, suspects = _load(case_id)
    template = TEMPLATE_PATH.read_text(encoding="utf-8")
    clue_ids = [clue.id for clue in case.clues]
    solution = (case.solution.method, case.solution.motive, case.solution.hiding_place)

    for suspect in suspects.values():
        for size in range(len(clue_ids) + 1):
            for found in itertools.combinations(clue_ids, size):
                prompt = render_system_prompt(case, suspect, frozenset(found), "cover", template)
                assert not any(fact.text in prompt for fact in suspect.secret), (suspect.id, found)
                assert not phrase_hits(prompt, case.forbidden_phrases), (suspect.id, found)
                assert not any(text in prompt for text in solution), (suspect.id, found)


@pytest.mark.parametrize("case_id", CASE_IDS)
def test_hidden_facts_are_not_copies_of_what_any_suspect_may_say(case_id: str) -> None:
    case, suspects = _load(case_id)
    hidden = {fact for clue in case.clues for fact in clue.hidden_facts} | set(case.solution.hidden_facts)

    for suspect in suspects.values():
        assert not hidden & set(allowed_facts(case, suspect, frozenset())), suspect.id


@pytest.mark.parametrize("case_id", CASE_IDS)
def test_every_suspect_has_probes_that_load(case_id: str) -> None:
    case, suspects = _load(case_id)
    first, *more = PROBE_DIRS[case_id]

    for suspect_id in suspects:
        assert load_probes(first / f"{suspect_id}.json", case)
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
