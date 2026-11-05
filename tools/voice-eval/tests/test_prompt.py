import dataclasses
import itertools

import pytest
from conftest import CASE_DIR

from voice_eval.content import STAGES, Case, Probe, Suspect, UnlockableFact, load_suspect
from voice_eval.metrics import normalize_for_match
from voice_eval.prompt import (
    allowed_facts,
    build_messages,
    pressure_line,
    raised_topics,
    render_system_prompt,
    unlocked_facts,
)

ALL_CLUES = ("clue-fusebox", "clue-wet-gloves", "clue-cellar-key", "clue-pawn-ticket")
PRESSURE_HEADING = "# 수사가 깊어진 지금 하게 되는 말 (어울릴 때만)"
PRESSING_LINE = "회장님께는 정말 면목이 없습니다."
READY_LINE = "드릴 말씀은 이제 다 드렸습니다."


def _render(case: Case, suspect: Suspect, template: str, unlocked=(), variant="cover") -> str:
    return render_system_prompt(case, suspect, frozenset(unlocked), variant, template)


def _render_at(case: Case, suspect: Suspect, template: str, stage: str) -> str:
    """The cover prompt with every clue found, which is how the app reaches the later stages."""
    return render_system_prompt(case, suspect, frozenset(ALL_CLUES), "cover", template, stage=stage)


def _with_pressure(suspect: Suspect, **lines: str) -> Suspect:
    """[suspect] with pressure lines by stage; demo-sapphire's personas have none."""
    return dataclasses.replace(
        suspect, pressure=tuple((stage, lines[stage]) for stage in STAGES if stage in lines)
    )


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


def test_prompt_shows_a_fact_line_in_place_of_its_answer_key(
    case: Case, butler: Suspect, template: str
) -> None:
    alibi = dataclasses.replace(butler.public[0], line="회장님 지시로 생신용 와인을 가지러 내려갔습니다.")
    fusebox = next(fact for fact in butler.unlockable if fact.id == "k-fusebox")
    reaction = dataclasses.replace(fusebox, line="두꺼비집이 저장고 바로 옆에 있는 건 맞습니다.")
    suspect = dataclasses.replace(
        butler,
        public=(alibi, *butler.public[1:]),
        unlockable=tuple(reaction if fact.id == "k-fusebox" else fact for fact in butler.unlockable),
    )
    prompt = _render(case, suspect, template, unlocked=("clue-fusebox",))

    assert alibi.line in prompt and alibi.text not in prompt
    assert reaction.line in prompt and reaction.text not in prompt
    assert butler.public[1].text in prompt


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


def test_each_reaction_sits_under_the_clue_that_brings_it_up(
    case: Case, butler: Suspect, template: str
) -> None:
    # A first-person line does not say when it is said, as the answer key's "…이야기에" did, so the
    # label names its clues. A reaction to two clues goes once, under the later clue in case order.
    fusebox_clue, key_clue = case.clue("clue-fusebox"), case.clue("clue-cellar-key")
    second = UnlockableFact(id="k-fusebox-2", unlocked_by=("clue-fusebox",), text="두꺼비집 두 번째 반응", demeanor=None)
    combo = UnlockableFact(
        id="k-combo", unlocked_by=("clue-cellar-key", "clue-fusebox"), text="두 단서를 모두 본 뒤의 반응", demeanor=None
    )
    suspect = dataclasses.replace(butler, unlockable=(*butler.unlockable, second, combo))
    lines = _render(case, suspect, template, unlocked=("clue-fusebox", "clue-cellar-key")).splitlines()
    fusebox = next(fact for fact in butler.unlockable if fact.id == "k-fusebox")

    def at(text: str) -> int:
        return next(index for index, line in enumerate(lines) if text in line)

    label = f"[{fusebox_clue.title} 이야기에 너의 반응]"
    assert at(fusebox_clue.summary) < at(f"{label} {fusebox.prompt_text}") < at(f"{label} {second.text}") < at(key_clue.summary)
    both = f"[{fusebox_clue.title}·{key_clue.title} 이야기에 너의 반응] {combo.text}"
    assert at(key_clue.summary) < at(both)
    assert sum(combo.text in line for line in lines) == 1


def test_a_reaction_replaces_the_lines_it_contradicts_once_unlocked(
    case: Case, butler: Suspect, template: str
) -> None:
    alibi = butler.public[0]
    fusebox = next(fact for fact in butler.unlockable if fact.id == "k-fusebox")
    gloves = next(fact for fact in butler.unlockable if fact.id == "k-gloves")
    confession = UnlockableFact(
        id="k-confession",
        unlocked_by=("clue-fusebox", "clue-wet-gloves"),
        text="두 단서를 다 보고 털어놓는 말",
        demeanor=None,
        replaces=(alibi.id, fusebox.id),
    )
    suspect = dataclasses.replace(butler, unlockable=(*butler.unlockable, confession))
    before = _render(case, suspect, template, unlocked=("clue-fusebox",))
    after = _render(case, suspect, template, unlocked=("clue-fusebox", "clue-wet-gloves"))

    assert alibi.prompt_text in before and fusebox.prompt_text in before
    assert alibi.prompt_text not in after and fusebox.prompt_text not in after
    assert gloves.prompt_text in after and confession.text in after
    # Only the prompt changes: the judge and the sentence check still accept what was replaced.
    assert {alibi.text, fusebox.text} <= set(allowed_facts(case, suspect, frozenset({"clue-fusebox", "clue-wet-gloves"})))


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


def test_the_pressure_line_follows_the_found_clues_only_from_pressing_on(
    case: Case, butler: Suspect, template: str
) -> None:
    suspect = _with_pressure(butler, pressing=PRESSING_LINE)
    plain = _render(case, suspect, template, unlocked=ALL_CLUES)
    pressing = _render_at(case, suspect, template, "pressing")
    section = f"\n\n{PRESSURE_HEADING}\n- {PRESSING_LINE}"

    assert _render_at(case, suspect, template, "opening") == plain
    assert _render_at(case, suspect, template, "gathering") == plain
    assert f"{section}\n\n# 다른 인물에 대해\n" in pressing
    # The section is all a stage adds, so before "pressing" the prompt is exactly what it was.
    assert pressing.replace(section, "", 1) == plain


def test_ready_shows_its_own_line_or_falls_back_to_the_pressing_one(
    case: Case, butler: Suspect, template: str
) -> None:
    both = _with_pressure(butler, pressing=PRESSING_LINE, ready=READY_LINE)
    pressing_only = _with_pressure(butler, pressing=PRESSING_LINE)
    ready_only = _with_pressure(butler, ready=READY_LINE)
    ready = _render_at(case, both, template, "ready")

    assert pressure_line(both, "pressing") == PRESSING_LINE
    assert pressure_line(both, "ready") == READY_LINE
    assert pressure_line(pressing_only, "ready") == PRESSING_LINE
    # The line for the end is never said early.
    assert pressure_line(ready_only, "pressing") is None
    assert pressure_line(both, "opening") is None and pressure_line(both, "gathering") is None
    assert f"\n\n{PRESSURE_HEADING}\n- {READY_LINE}\n" in ready and PRESSING_LINE not in ready
    assert _render_at(case, pressing_only, template, "ready") == _render_at(
        case, pressing_only, template, "pressing"
    )


@pytest.mark.parametrize("stage", STAGES)
def test_a_suspect_without_pressure_lines_gets_the_same_prompt_at_every_stage(
    case: Case, butler: Suspect, template: str, stage: str
) -> None:
    assert butler.pressure == ()
    assert _render_at(case, butler, template, stage) == _render(case, butler, template, unlocked=ALL_CLUES)


def test_unknown_stage_raises(case: Case, butler: Suspect, template: str) -> None:
    with pytest.raises(ValueError, match="unknown stage 'finale'"):
        _render_at(case, butler, template, "finale")
