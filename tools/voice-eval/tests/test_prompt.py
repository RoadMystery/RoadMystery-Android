import dataclasses
import itertools

import pytest

from voice_eval.content import Case, Probe, Suspect, UnlockableFact
from voice_eval.metrics import normalize_for_match
from voice_eval.prompt import build_messages, render_system_prompt, unlocked_facts

ALL_CLUES = ("clue-fusebox", "clue-wet-gloves", "clue-cellar-key", "clue-pawn-ticket")


def _render(case: Case, suspect: Suspect, template: str, unlocked=(), variant="cover") -> str:
    return render_system_prompt(case, suspect, frozenset(unlocked), variant, template)


def test_cover_prompt_never_contains_secrets_or_solution(
    case: Case, butler: Suspect, template: str
) -> None:
    prompt = _render(case, butler, template, unlocked=ALL_CLUES)
    normalized = normalize_for_match(prompt)

    for fact in butler.secret:
        assert fact.text not in prompt
    for phrase in case.forbidden_phrases:
        assert normalize_for_match(phrase) not in normalized
    assert case.solution.method not in prompt
    assert case.solution.motive not in prompt


def test_truth_prompt_adds_secret_section(case: Case, butler: Suspect, template: str) -> None:
    prompt = _render(case, butler, template, variant="truth")
    assert "절대 말하지 말 것" in prompt
    for fact in butler.secret:
        assert fact.text in prompt


def test_locked_clues_and_reactions_are_absent(case: Case, butler: Suspect, template: str) -> None:
    prompt = _render(case, butler, template, unlocked=())
    for clue in case.clues:
        assert clue.summary not in prompt
    for fact in butler.unlockable:
        assert fact.text not in prompt


def test_unlocking_one_clue_adds_only_its_reaction(
    case: Case, butler: Suspect, template: str
) -> None:
    prompt = _render(case, butler, template, unlocked=("clue-fusebox",))
    assert case.clue("clue-fusebox").summary in prompt
    reactions = {fact.id: fact.text for fact in butler.unlockable}
    assert reactions["k-fusebox"] in prompt
    assert reactions["k-gloves"] not in prompt
    assert case.clue("clue-wet-gloves").summary not in prompt


def test_reaction_needing_two_clues_waits_for_both(butler: Suspect) -> None:
    combo = UnlockableFact(
        id="k-combo",
        unlocked_by=("clue-fusebox", "clue-cellar-key"),
        text="두 단서를 모두 본 뒤의 반응",
        demeanor=None,
    )
    suspect = dataclasses.replace(butler, unlockable=(combo,))

    assert unlocked_facts(suspect, frozenset({"clue-fusebox"})) == ()
    assert unlocked_facts(suspect, frozenset({"clue-fusebox", "clue-cellar-key"})) == (combo,)


def test_unknown_unlocked_clue_raises(case: Case, butler: Suspect, template: str) -> None:
    with pytest.raises(ValueError, match="clue-nope"):
        _render(case, butler, template, unlocked=("clue-nope",))


def test_unknown_variant_raises(case: Case, butler: Suspect, template: str) -> None:
    with pytest.raises(ValueError, match="variant"):
        _render(case, butler, template, variant="leaky")


@pytest.mark.parametrize("variant", ["cover", "truth"])
def test_every_placeholder_is_filled(
    case: Case, butler: Suspect, template: str, variant: str
) -> None:
    prompt = _render(case, butler, template, unlocked=ALL_CLUES, variant=variant)
    assert "{{" not in prompt and "}}" not in prompt


def test_prompt_carries_identity_style_and_fallback_line(
    case: Case, butler: Suspect, template: str
) -> None:
    prompt = _render(case, butler, template)
    assert butler.name in prompt
    assert butler.register in prompt
    assert butler.fallback_not_understood in prompt
    assert "폭력" in prompt  # family theme rule


def test_build_messages_appends_question_after_history(probes: tuple[Probe, ...]) -> None:
    probe = next(p for p in probes if p.history)
    messages = build_messages(probe)

    assert messages[-1] == {"role": "user", "content": probe.question}
    assert messages[: len(probe.history)] == [dict(turn) for turn in probe.history]
    roles = [message["role"] for message in messages]
    assert all(a != b for a, b in itertools.pairwise(roles))


def test_build_messages_rejects_history_ending_on_user(probes: tuple[Probe, ...]) -> None:
    probe = dataclasses.replace(
        probes[0], history=({"role": "user", "content": "먼저 한 질문"},)
    )
    with pytest.raises(ValueError, match="assistant"):
        build_messages(probe)
