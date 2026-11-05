"""Checks every case under content/cases/ must pass, so a new script is held to the clue-design rules
measured on demo-sapphire (docs/voice-agent/llm-evaluation.md, docs/voice-agent/scenario-jade-crown.md)."""

import itertools
from collections.abc import Iterable
from difflib import SequenceMatcher

import pytest
from conftest import REPO_ROOT, TEMPLATE_PATH, TOOL_ROOT

from voice_eval.content import STAGES, Case, Suspect, load_case, load_probes, load_suspect
from voice_eval.identify import identify_suspect, suspect_aliases
from voice_eval.metrics import looks_like_readout, normalize_for_match, phrase_hits, split_sentences
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
CASES_KEPT_AS_MEASURED = {
    # Measured before first-person lines and narrator hints, so its prompts show the answer keys and its
    # suspects deflect; the real script replaces this case.
    "demo-sapphire",
}
MAX_SHARED_RUN = 8
"""Letters in a row, after normalize_for_match, that a narrator hint or a line offered to a suspect may
share with a hidden fact or the solution: room for a name, a place or a time, not for the fact."""


def _load(case_id: str) -> tuple[Case, dict[str, Suspect]]:
    case = load_case(CASES_DIR / case_id)
    suspects = {
        member.id: load_suspect(CASES_DIR / case_id / "suspects" / f"{member.id}.json", case)
        for member in case.cast
        if member.is_suspect
    }
    return case, suspects


def _shown_before_any_clue(case: Case, suspect: Suspect) -> list[str]:
    """Persona text in every prompt of [suspect], whatever the player has found, and the answer keys of
    its public facts, which the judge and the sentence check treat as said."""
    return [
        case.setting,
        *case.public_facts,
        suspect.background,
        *suspect.personality,
        suspect.register,
        *suspect.speech_rules,
        *suspect.speech_examples,
        *(fact.text for fact in suspect.public),
        *(fact.prompt_text for fact in suspect.public),
        *(relationship.attitude for relationship in suspect.relationships),
        *suspect.deflections,
    ]


def _keywords_except(case: Case, clue_ids: tuple[str, ...]) -> list[str]:
    return [keyword for clue in case.clues if clue.id not in clue_ids for keyword in clue.keywords]


def _hidden_facts_except(case: Case, clue_ids: tuple[str, ...]) -> list[str]:
    return [fact for clue in case.clues if clue.id not in clue_ids for fact in clue.hidden_facts]


def _solution_texts(case: Case) -> tuple[str, ...]:
    solution = case.solution
    return (solution.method, solution.motive, solution.hiding_place, *solution.hidden_facts)


def _answer(case: Case) -> tuple[str, ...]:
    """The hidden facts and the solution text, which neither a narrator hint nor a line the prompt offers
    a suspect to say may contain."""
    return (*_hidden_facts_except(case, ()), *_solution_texts(case))


def _shared_run(text: str, other: str) -> str:
    """The longest stretch [text] and [other] have in common once normalized like the spoiler guard."""
    left, right = normalize_for_match(text), normalize_for_match(other)
    # No autojunk: it would skip the letters most common in a long text such as the solution's method.
    match = SequenceMatcher(None, left, right, autojunk=False).find_longest_match(0, len(left), 0, len(right))
    return left[match.a : match.a + match.size]


def _overlong_runs(text: str, others: Iterable[str]) -> list[str]:
    """The stretches longer than MAX_SHARED_RUN that [text] shares with any of [others]."""
    return [run for other in others if len(run := _shared_run(text, other)) > MAX_SHARED_RUN]


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
            for text in (fact.text, fact.prompt_text):
                assert not phrase_hits(text, _keywords_except(case, fact.unlocked_by)), fact.id
        for line in suspect.before_found:
            assert not phrase_hits(line.text, _keywords_except(case, (line.clue,))), line.clue


@pytest.mark.parametrize("case_id", CASE_IDS)
def test_no_persona_line_trips_the_spoiler_guard(case_id: str) -> None:
    case, suspects = _load(case_id)

    for suspect in suspects.values():
        lines = [
            *_shown_before_any_clue(case, suspect),
            *(fact.text for fact in suspect.unlockable),
            *(fact.prompt_text for fact in suspect.unlockable),
            *(line.text for line in suspect.before_found),
        ]
        for text in lines:
            assert not phrase_hits(text, case.forbidden_phrases), text


@pytest.mark.parametrize("case_id", CASE_IDS)
def test_hints_name_no_clue_and_give_nothing_away(case_id: str) -> None:
    # The narrator speaks a clue's hint while that clue and perhaps others are not found, and the
    # spoiler guard does not check it. Naming another clue would point at it before the story offers it
    # (the barefoot hint once named the window clue's title); the clue's own mentions are fine.
    case, _ = _load(case_id)
    every_keyword = _keywords_except(case, ())
    answer = _answer(case)

    for clue in case.clues:
        if clue.hint is None:
            continue
        other_clues = [
            text
            for other in case.clues
            if other.id != clue.id
            for text in (*other.keywords, other.title, *other.mentions)
        ]
        assert not phrase_hits(clue.hint, every_keyword), clue.id
        assert not phrase_hits(clue.hint, other_clues), clue.id
        assert not phrase_hits(clue.hint, case.forbidden_phrases), clue.id
        assert not any(text in clue.hint for text in answer), clue.id
        assert not _overlong_runs(clue.hint, answer), clue.id
    if case.spoiler_hint is not None:
        assert not phrase_hits(case.spoiler_hint, case.forbidden_phrases)
        assert not any(text in case.spoiler_hint for text in answer)
        assert not _overlong_runs(case.spoiler_hint, answer)


@pytest.mark.parametrize("case_id", [case_id for case_id in CASE_IDS if case_id not in CASES_KEPT_AS_MEASURED])
def test_the_narrator_has_a_hint_for_every_clue_and_for_the_end(case_id: str) -> None:
    case, _ = _load(case_id)

    assert all(clue.hint for clue in case.clues)
    assert case.spoiler_hint


@pytest.mark.parametrize("case_id", [case_id for case_id in CASE_IDS if case_id not in CASES_KEPT_AS_MEASURED])
def test_the_prompt_gives_every_fact_as_a_line_the_suspect_could_say_aloud(case_id: str) -> None:
    # Given '~라고 말한다' answer keys, the model sometimes said aloud its plan for turning them into
    # speech (docs/voice-agent/scenario-jade-crown.md). A line the app would take for a read-out would
    # become one whenever the model repeated it word for word.
    _, suspects = _load(case_id)

    for suspect in suspects.values():
        for fact in (*suspect.public, *suspect.unlockable):
            assert fact.line, fact.id
            for sentence in split_sentences(fact.line):
                assert not looks_like_readout(sentence, suspect.name), (fact.id, sentence)


@pytest.mark.parametrize("case_id", CASE_IDS)
def test_fact_lines_give_no_hidden_fact_away_and_name_nobody_their_answer_key_leaves_out(case_id: str) -> None:
    # The model may repeat a line word for word. A public fact's line is in every prompt and a reaction's
    # once the clues that unlock it are found, so neither may restate a fact still hidden then. A person
    # the answer key does not name would be news the suspect volunteers.
    case, suspects = _load(case_id)
    names = [name for member in case.cast for name in (member.name, *member.aliases)]

    for suspect in suspects.values():
        facts = [
            *((fact, ()) for fact in suspect.public),
            *((fact, fact.unlocked_by) for fact in suspect.unlockable),
        ]
        for fact, unlocked_by in facts:
            if fact.line is None:
                continue
            still_hidden = (*_hidden_facts_except(case, unlocked_by), *_solution_texts(case))
            assert not _overlong_runs(fact.line, still_hidden), fact.id
            extra_names = set(phrase_hits(fact.line, names)) - set(phrase_hits(fact.text, names))
            assert not extra_names, (fact.id, extra_names)


@pytest.mark.parametrize("case_id", CASE_IDS)
def test_pressure_lines_are_speech_that_gives_nothing_away(case_id: str) -> None:
    # The prompt offers a pressure line as something to say, so the model may repeat it word for word.
    # Every clue is found by then, so a clue keyword is fine; a read-out, the answer or the culprit's name
    # is not (the son's lines once kept bringing 하린 up unasked).
    case, suspects = _load(case_id)
    answer = _answer(case)
    culprit = case.member(case.solution.culprit)

    for suspect in suspects.values():
        for stage, line in suspect.pressure:
            for sentence in split_sentences(line):
                assert not looks_like_readout(sentence, suspect.name), (suspect.id, stage, sentence)
            assert not phrase_hits(line, case.forbidden_phrases), (suspect.id, stage)
            assert not any(text in line for text in answer), (suspect.id, stage)
            assert not _overlong_runs(line, _solution_texts(case)), (suspect.id, stage)
            assert not phrase_hits(line, (culprit.name, *culprit.aliases)), (suspect.id, stage)


@pytest.mark.parametrize("case_id", CASE_IDS)
def test_mentions_are_two_letters_or_more_and_no_forbidden_phrase(case_id: str) -> None:
    # The app finds a mention in a question with spacing ignored and inside longer words, so one letter
    # would match far too much (창 in 창고), and naming the solution must not count as bringing a clue up.
    case, _ = _load(case_id)

    for clue in case.clues:
        for mention in clue.mentions:
            assert len(normalize_for_match(mention)) >= 2, (clue.id, mention)
            assert not phrase_hits(mention, case.forbidden_phrases), (clue.id, mention)


@pytest.mark.parametrize("case_id", CASE_IDS)
def test_a_mention_brings_up_only_its_own_clue(case_id: str) -> None:
    # A question naming another clue's keyword or title must not count as confronting this one too: the
    # shoes clue's "구두" was found in the window clue's "구두 자국" (and in "구두쇠").
    case, _ = _load(case_id)
    mentions = [normalize_for_match(mention) for clue in case.clues for mention in clue.mentions]

    assert len(mentions) == len(set(mentions))
    for clue in case.clues:
        other_clues = [text for other in case.clues if other.id != clue.id for text in (*other.keywords, other.title)]
        for mention in clue.mentions:
            assert not [text for text in other_clues if phrase_hits(text, (mention,))], (clue.id, mention)


@pytest.mark.parametrize("case_id", CASE_IDS)
def test_key_reaction_ids_are_unique_across_suspects(case_id: str) -> None:
    # The app keeps the key reactions drawn out as one set of ids for the whole cast (AgentState.confronted).
    _, suspects = _load(case_id)
    key_ids = [fact.id for suspect in suspects.values() for fact in suspect.unlockable if fact.key]

    assert len(key_ids) == len(set(key_ids))


@pytest.mark.parametrize("case_id", [case_id for case_id in CASE_IDS if case_id not in CASES_KEPT_AS_MEASURED])
def test_a_new_case_has_a_key_reaction_so_the_investigation_reaches_pressing(case_id: str) -> None:
    # "pressing" lasts from the last clue found until every key reaction is drawn out, so without a key
    # reaction the investigation would go from "gathering" straight to "ready". The schema already keeps
    # pressure lines to "pressing" and "ready".
    _, suspects = _load(case_id)

    assert any(fact.key for suspect in suspects.values() for fact in suspect.unlockable)


@pytest.mark.parametrize("case_id", CASE_IDS)
def test_cover_prompts_keep_secrets_and_the_solution_at_every_stage_and_set_of_found_clues(case_id: str) -> None:
    # Every stage, because pressure lines join the prompt from "pressing" on.
    case, suspects = _load(case_id)
    template = TEMPLATE_PATH.read_text(encoding="utf-8")
    clue_ids = [clue.id for clue in case.clues]
    found_sets = [found for size in range(len(clue_ids) + 1) for found in itertools.combinations(clue_ids, size)]
    solution = (case.solution.method, case.solution.motive, case.solution.hiding_place)

    for suspect in suspects.values():
        for found, stage in itertools.product(found_sets, STAGES):
            prompt = render_system_prompt(case, suspect, frozenset(found), "cover", template, stage=stage)
            assert not any(fact.text in prompt for fact in suspect.secret), (suspect.id, found, stage)
            assert not phrase_hits(prompt, case.forbidden_phrases), (suspect.id, found, stage)
            assert not any(text in prompt for text in solution), (suspect.id, found, stage)


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
