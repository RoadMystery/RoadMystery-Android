import dataclasses
import itertools

import pytest
from conftest import CASE_DIR

from voice_eval.content import Case, Probe, Suspect, UnlockableFact, load_suspect
from voice_eval.metrics import normalize_for_match
from voice_eval.prompt import build_messages, raised_topics, render_system_prompt, unlocked_facts

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


@pytest.mark.parametrize("suspect_id", ["niece", "cook"])
def test_other_suspects_cover_prompts_keep_secrets(
    case: Case, template: str, suspect_id: str
) -> None:
    suspect = load_suspect(CASE_DIR / "suspects" / f"{suspect_id}.json", case)
    prompt = _render(case, suspect, template, unlocked=ALL_CLUES)
    normalized = normalize_for_match(prompt)

    assert suspect.name in prompt
    for fact in suspect.secret:
        assert fact.text not in prompt
    for phrase in case.forbidden_phrases:
        assert normalize_for_match(phrase) not in normalized
    assert "{{" not in prompt


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


def test_raised_topics_are_keywords_of_clues_not_found_yet(case: Case) -> None:
    nothing: frozenset[str] = frozenset()
    question = "지하실 문 열쇠는 누가 갖고 있어요?"

    assert raised_topics(case, nothing, question) == ("열쇠",)
    assert raised_topics(case, frozenset({"clue-cellar-key"}), question) == ()
    assert raised_topics(case, nothing, "정전 때 어디 있었어요?") == ()
    # Matched like the spoiler guard: spacing is ignored and a keyword counts inside a longer word.
    assert raised_topics(case, nothing, "영수증은요? 고무장갑은 쓰세요?") == ("장갑", "영수증")
    assert raised_topics(case, nothing, "지하실 열 쇠, 누가 가졌죠?") == ("열쇠",)


def test_topic_rule_follows_the_unfound_evidence_rule_only_for_raised_topics(
    case: Case, butler: Suspect, template: str
) -> None:
    plain = _render(case, butler, template)
    noted = render_system_prompt(case, butler, frozenset(), "cover", template, topics=("열쇠", "장갑"))

    assert "지금 꺼낸" not in plain
    rule = next(line for line in noted.splitlines() if line.startswith("- 플레이어가 지금 꺼낸"))
    assert "'열쇠', '장갑' 이야기는" in rule
    assert butler.name in rule
    assert f"먼저 꺼내지 마라.\n{rule}\n" in noted
    # Without a raised topic the prompt is exactly what it was before topic rules existed.
    assert noted.replace(f"\n{rule}", "", 1) == plain
    assert "{{" not in noted


def test_the_shipped_template_leaves_before_found_answers_out(
    case: Case, butler: Suspect, template: str
) -> None:
    prompt = _render(case, butler, template)

    assert "{{before_found}}" not in template
    assert not any(line.text in prompt for line in butler.before_found)


def test_before_found_answers_cover_only_clues_not_found_yet(case: Case, butler: Suspect) -> None:
    template = "{{unlocked_knowledge}}{{before_found}}\n\n# 다음"
    answers = {line.clue: line.text for line in butler.before_found}

    none_found = _render(case, butler, template)
    key_found = _render(case, butler, template, unlocked=("clue-cellar-key",))
    all_found = _render(case, butler, template, unlocked=ALL_CLUES)

    heading = "# 플레이어가 아직 찾지 않은 단서를 물을 때 (먼저 꺼내지 말 것)"
    listed = [f"- {answers[clue]}" for clue in ALL_CLUES]
    assert f"\n\n{heading}\n" + "\n".join(listed) + "\n\n# 다음" in none_found
    assert answers["clue-cellar-key"] not in key_found
    assert answers["clue-fusebox"] in key_found
    # With every clue found the placeholder adds nothing, not even a blank line.
    assert all_found == _render(case, butler, "{{unlocked_knowledge}}\n\n# 다음", unlocked=ALL_CLUES)
