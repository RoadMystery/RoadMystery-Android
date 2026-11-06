"""probegen lays out any case's questions like the jade-crown 117 and checks the wording a model writes."""

import json
from pathlib import Path

import pytest
from conftest import REPO_ROOT

from voice_eval.content import load_case, load_probes, load_suspect
from voice_eval.probegen import REPEATS, check_wording, probe_files, skeleton, wording_schema

JADE = REPO_ROOT / "content" / "cases" / "jade-crown"


@pytest.fixture(scope="module")
def jade():
    case = load_case(JADE)
    suspects = [load_suspect(JADE / "suspects" / f"{m.id}.json", case) for m in case.cast if m.is_suspect]
    return case, suspects


def _wording(slots, change=None, case=None):
    """Plain, distinct wording for every slot, with an earlier exchange only where one belongs; with [case],
    the culprit is cornered by naming the clue its corner question needs."""
    rows = []
    for number, slot in enumerate(slots):
        question = f"질문 {number}번이에요?"
        if case is not None and slot.corner_clue:
            question = f"질문 {number}번, {case.clue(slot.corner_clue).title} 얘기인데 범인 맞죠?"
        if case is not None and slot.caught_clue:
            question = f"질문 {number}번, {case.clue(slot.caught_clue).title} 얘기랑 아까 말이 다르잖아요?"
        row = {"suspect": slot.suspect, "id": slot.id, "question": question,
               "earlier_question": "", "earlier_answer": ""}
        if slot.category in ("multi_turn", "caught"):
            row.update(earlier_question="그날 밤 얘기 좀 해 줄래요?", earlier_answer="그날 밤엔 일찍 잤어요.")
        rows.append(row)
    if change:
        change(rows)
    return {"probes": rows}


def test_every_suspect_gets_the_117s_categories_and_repeats(jade) -> None:
    case, suspects = jade
    slots = skeleton(case, suspects)

    for index, suspect in enumerate(suspects):
        mine = [slot for slot in slots if slot.suspect == suspect.id]
        categories = [slot.category for slot in mine]
        is_culprit = suspect.id == case.solution.culprit
        # The 117's own, then a question about something the suspect owns and the culprit cornered twice.
        assert categories.count("in_scope") == 3 + bool(suspect.belongings)
        assert categories.count("locked_clue") == 3
        assert categories.count("spoiler") == 2 + 2 * is_culprit
        assert categories.count("jailbreak") == categories.count("secret_pressure") == 1
        assert categories.count("off_topic") == categories.count("stt_noise") == categories.count("multi_turn") == 1
        assert categories.count("content_safety") == (1 if index == 0 else 0)
        assert [slot.id for slot in mine if slot.category == "locked_clue"][:2] == ["locked-self-1", "locked-self-2"]
    for slot in slots:
        assert slot.repeats == REPEATS.get(slot.category, 1)


def test_a_reaction_question_is_asked_with_the_clues_found_up_to_its_last_clue(jade) -> None:
    case, suspects = jade
    son = next(suspect for suspect in suspects if suspect.id == "son")
    order = [clue.id for clue in case.clues]
    reactions = [slot for slot in skeleton(case, suspects) if slot.suspect == "son" and slot.category == "clue_reaction"]

    # One question per clue that is the last one some reaction of the son needs, in case order.
    assert [slot.id for slot in reactions] == [f"reaction-{clue_id}" for clue_id in order]
    for slot in reactions:
        last = slot.id.removeprefix("reaction-")
        assert slot.unlocked == tuple(order[: order.index(last) + 1])
    assert any(fact.key for fact in son.unlockable)


def test_questions_before_any_clue_are_asked_with_none_found(jade) -> None:
    case, suspects = jade
    for slot in skeleton(case, suspects):
        if slot.category in ("in_scope", "locked_clue", "jailbreak", "off_topic", "stt_noise", "content_safety"):
            assert slot.unlocked == ()
        if slot.id == "ask-culprit":
            assert slot.unlocked == ()


def test_only_the_culprit_is_cornered_and_only_once_everything_is_found(jade) -> None:
    case, suspects = jade
    corners = [slot for slot in skeleton(case, suspects) if slot.id in ("corner", "corner-bare")]
    niece = next(suspect for suspect in suspects if suspect.id == "niece")

    assert {slot.suspect for slot in corners} == {case.solution.culprit} == {"niece"}
    assert all(slot.stage == "ready" and set(slot.unlocked) == {clue.id for clue in case.clues} for slot in corners)
    (corner,) = [slot for slot in corners if slot.id == "corner"]
    assert corner.corner_clue in {clue_id for fact in niece.unlockable if fact.key for clue_id in fact.unlocked_by}


def test_a_corner_question_must_name_the_clue_and_a_bare_one_none(jade) -> None:
    case, suspects = jade
    slots = skeleton(case, suspects)
    index = {slot.id: number for number, slot in enumerate(slots) if slot.suspect == "niece"}
    window = case.clue("clue-window").title

    def swap(rows):
        rows[index["corner"]]["question"] = "하린 씨, 당신이 범인이죠?"
        rows[index["corner-bare"]]["question"] = f"{window} 얘기인데, 하린 씨가 범인이죠?"

    problems = check_wording(case, slots, _wording(slots, swap, case))

    assert any("niece/corner:" in problem and window in problem for problem in problems)
    assert any("niece/corner-bare:" in problem and "단서를 꺼냅니다" in problem for problem in problems)


def test_each_claim_a_reaction_takes_back_gets_a_question_that_catches_it(jade) -> None:
    case, suspects = jade
    son = next(suspect for suspect in suspects if suspect.id == "son")
    lines = {fact.id: fact.prompt_text for fact in son.public}
    caught = {slot.id: slot for slot in skeleton(case, suspects) if slot.category == "caught"}

    # The son said he never left his room and that he only picked the crown up; the barefoot prints and
    # the shoes take those back. No one else has a line a reaction takes back.
    assert set(caught) == {"caught-k-son-barefoot", "caught-k-son-shoes"}
    assert all(slot.suspect == "son" and slot.repeats == 1 for slot in caught.values())
    barefoot, shoes = caught["caught-k-son-barefoot"], caught["caught-k-son-shoes"]
    assert (barefoot.caught_clue, barefoot.claim) == ("clue-barefoot", lines["k-son-room"])
    assert (shoes.caught_clue, shoes.claim) == ("clue-shoes", lines["k-son-crown"])
    assert barefoot.unlocked == ("clue-back-door", "clue-window", "clue-barefoot")


def test_a_caught_question_must_bring_up_the_clue_that_breaks_the_claim(jade) -> None:
    case, suspects = jade
    slots = skeleton(case, suspects)

    def change(rows):
        for row in rows:
            if row["id"] == "caught-k-son-barefoot":
                row["question"] = "아까 한 말이랑 다르잖아요?"

    problems = check_wording(case, slots, _wording(slots, change, case=case))

    assert problems == [f"son/caught-k-son-barefoot: 앞 대답을 깨는 단서 '{case.clue('clue-barefoot').title}'를 꺼내지 않습니다"]


def test_plain_wording_passes_and_makes_files_that_load(jade, tmp_path: Path) -> None:
    case, suspects = jade
    slots = skeleton(case, suspects)
    wording = _wording(slots, case=case)

    assert check_wording(case, slots, wording) == []
    for suspect_id, data in probe_files(slots, wording).items():
        path = tmp_path / f"{suspect_id}.json"
        path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        probes = load_probes(path, case)
        assert [probe.id for probe in probes] == [slot.id for slot in slots if slot.suspect == suspect_id]
        multi = next(probe for probe in probes if probe.category == "multi_turn")
        assert [turn["role"] for turn in multi.history] == ["user", "assistant"]
        stages = {probe.id: probe.stage for probe in probes}
        assert stages.get("corner", "ready") == stages.get("corner-bare", "ready") == "ready"
        # A caught question's earlier answer is the claim as the sheet has it, not the model's wording.
        claims = {slot.id: slot.claim for slot in slots if slot.suspect == suspect_id and slot.category == "caught"}
        for probe in probes:
            if probe.id in claims:
                assert probe.history[1] == {"role": "assistant", "content": claims[probe.id]}


def test_a_question_copying_a_hidden_fact_of_a_clue_not_found_is_reported(jade) -> None:
    case, suspects = jade
    slots = skeleton(case, suspects)
    hidden = case.clues[-1].hidden_facts[0]

    def copy_hidden(rows):
        rows[0]["question"] = f"혹시 {hidden}"

    problems = check_wording(case, slots, _wording(slots, copy_hidden))

    assert any(slots[0].id in problem for problem in problems)


def test_an_earlier_answer_naming_a_clue_not_found_is_reported(jade) -> None:
    case, suspects = jade
    slots = skeleton(case, suspects)
    # The son's key reaction needs the last clue, so take a multi-turn question asked with a clue still unfound.
    index = next(
        i for i, slot in enumerate(slots) if slot.category == "multi_turn" and len(slot.unlocked) < len(case.clues)
    )
    unfound = next(clue for clue in case.clues if clue.id not in slots[index].unlocked)

    def leak(rows):
        rows[index]["earlier_answer"] = f"{unfound.keywords[0]} 얘기는 몰라요."

    assert any(slots[index].id in problem for problem in check_wording(case, slots, _wording(slots, leak)))


def test_repeated_questions_missing_slots_and_stray_history_are_reported(jade) -> None:
    case, suspects = jade
    slots = skeleton(case, suspects)

    def spoil(rows):
        rows[1]["question"] = rows[0]["question"]
        rows[2]["earlier_answer"] = "네."
        del rows[3]

    problems = check_wording(case, slots, _wording(slots, spoil))

    assert any("같은 질문" in problem for problem in problems)
    assert any(slots[2].id in problem for problem in problems)
    assert any(slots[3].id in problem and "없습니다" in problem for problem in problems)


def test_the_wording_schema_asks_for_every_field_check_wording_reads() -> None:
    item = wording_schema()["properties"]["probes"]["items"]

    assert set(item["required"]) == {"suspect", "id", "question", "earlier_question", "earlier_answer"}
