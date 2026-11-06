"""semantic_check asks a model what letter comparison cannot see, and reports it like validate_case does."""

import json
from types import SimpleNamespace

import pytest
from conftest import REPO_ROOT

from voice_eval import __main__ as cli
from voice_eval.content import load_case, load_suspect
from voice_eval.semantic_check import (
    SEMANTIC_SCHEMA,
    check_semantics,
    semantic_problems,
    semantic_request,
)

JADE = REPO_ROOT / "content" / "cases" / "jade-crown"


@pytest.fixture(scope="module")
def jade():
    case = load_case(JADE)
    suspects = [load_suspect(JADE / "suspects" / f"{m.id}.json", case) for m in case.cast if m.is_suspect]
    return case, suspects


def _verdicts(suspects, flag=None):
    rows = [
        {"suspect": suspect.id, "id": fact.id, "reveals_solution": False, "line_differs_from_text": False,
         "contradicts_unreplaced_public": False, "hidden_act_without_claim": False, "claim_names_breaking_clue": False,
         "reason": "괜찮다"}
        for suspect in suspects
        for fact in (*suspect.public, *suspect.unlockable)
    ]
    if flag:
        flag(rows)
    return {"facts": rows, "case": {"cover_story_broken": True, "confession_matches_solution": True, "reason": "괜찮다"}}


def test_the_request_carries_the_culprits_confession(jade) -> None:
    case, suspects = jade
    _, user = semantic_request(case, suspects)
    data = json.loads(user)
    niece = next(suspect for suspect in suspects if suspect.id == "niece")

    assert data["culprit"] == {"suspect": "niece", "confession": niece.confession}
    assert all(row["id"] != "confession" for row in data["facts"])


def test_the_whole_case_is_judged_for_a_broken_cover_story_and_a_true_confession(jade) -> None:
    _, suspects = jade
    verdicts = _verdicts(suspects)
    verdicts["case"] = {"cover_story_broken": False, "confession_matches_solution": False, "reason": "없다"}

    problems = semantic_problems(suspects, verdicts)

    assert any("겉 이야기" in problem for problem in problems)
    assert any("자백 대사" in problem for problem in problems)
    assert semantic_problems(suspects, {"facts": verdicts["facts"]}) == [
        "의미 검사가 사건 전체(범인의 겉 이야기와 자백)를 판정하지 않았습니다"
    ]


def test_the_request_carries_the_solution_and_every_line_with_its_answer_key(jade) -> None:
    case, suspects = jade
    system, user = semantic_request(case, suspects)
    data = json.loads(user)

    assert "reveals_solution" in system and "line_differs_from_text" in system
    assert data["solution"]["motive"] == case.solution.motive
    sent = {(row["suspect"], row["id"]): row for row in data["facts"]}
    for suspect in suspects:
        for fact in (*suspect.public, *suspect.unlockable):
            assert sent[(suspect.id, fact.id)]["line"] == fact.prompt_text
            assert sent[(suspect.id, fact.id)]["text"] == fact.text
    shoes = sent[("son", "k-son-shoes")]
    assert shoes["key"] is True
    assert shoes["found_clues"] == [case.clue(clue_id).title for clue_id in ("clue-window", "clue-shoes")]


def test_flags_become_messages_naming_the_fact(jade) -> None:
    _, suspects = jade

    def flag(rows):
        rows[0]["reveals_solution"] = True
        rows[1]["line_differs_from_text"] = True

    problems = semantic_problems(suspects, _verdicts(suspects, flag))

    first, second = _verdicts(suspects)["facts"][:2]
    assert any(f"{first['suspect']}/{first['id']}" in problem and "정답" in problem for problem in problems)
    assert any(f"{second['suspect']}/{second['id']}" in problem and "다른 사실" in problem for problem in problems)
    assert len(problems) == 2


def test_the_request_pairs_each_claim_with_the_reactions_that_take_it_back(jade) -> None:
    case, suspects = jade
    _, user = semantic_request(case, suspects)
    sent = {(row["suspect"], row["id"]): row for row in json.loads(user)["facts"]}

    # jade-crown's son: "never left my room" is taken back by the barefoot and the shoes reactions.
    assert sent[("son", "k-son-barefoot")]["replaces"] == ["k-son-room"]
    claim = sent[("son", "k-son-room")]
    assert claim["replaced_by"] == ["k-son-barefoot", "k-son-shoes"]
    assert claim["breaking_clues"] == [case.clue(c).title for c in ("clue-window", "clue-barefoot", "clue-shoes")]
    assert sent[("son", "k-son-money")]["replaced_by"] == [] and sent[("son", "k-son-money")]["breaking_clues"] == []


def test_a_reaction_that_contradicts_or_reveals_without_taking_a_claim_back_is_a_problem(jade) -> None:
    _, suspects = jade

    def flag(rows):
        for row in rows:
            if row["id"] == "k-maid-back-door":
                row["contradicts_unreplaced_public"] = True
                row["hidden_act_without_claim"] = True

    problems = semantic_problems(suspects, _verdicts(suspects, flag))

    assert any("maid/k-maid-back-door" in p and "replaces로 거두지" in p for p in problems)
    assert any("maid/k-maid-back-door" in p and "덮는 주장" in p for p in problems)
    assert len(problems) == 2


def test_only_a_claim_some_reaction_takes_back_can_name_its_breaking_clue_too_early(jade) -> None:
    _, suspects = jade

    def flag(rows):
        for row in rows:
            if row["id"] in ("k-son-room", "k-son-money", "k-son-barefoot"):
                row["claim_names_breaking_clue"] = True

    problems = semantic_problems(suspects, _verdicts(suspects, flag))

    # k-son-money is no claim a reaction takes back and k-son-barefoot is a reaction: neither is asked about.
    assert problems == [problems[0]] and "son/k-son-room" in problems[0] and "먼저 꺼냅니다" in problems[0]


def test_a_fact_the_model_left_out_is_a_problem_and_one_it_made_up_is_ignored(jade) -> None:
    _, suspects = jade

    def change(rows):
        rows.pop(0)
        rows.append({"suspect": "nobody", "id": "k-x", "reveals_solution": True, "line_differs_from_text": True, "reason": ""})

    problems = semantic_problems(suspects, _verdicts(suspects, change))
    left_out = _verdicts(suspects)["facts"][0]

    assert problems == [f"{left_out['suspect']}/{left_out['id']}: 의미 검사가 이 사실을 판정하지 않았습니다"]


def test_check_semantics_makes_one_structured_call(jade) -> None:
    case, suspects = jade
    calls = []

    class FakeMessages:
        def create(self, **kwargs):
            calls.append(kwargs)
            text = json.dumps(_verdicts(suspects), ensure_ascii=False)
            return SimpleNamespace(stop_reason="end_turn", content=[SimpleNamespace(type="text", text=text)])

    problems = check_semantics(case, suspects, SimpleNamespace(messages=FakeMessages()))

    assert problems == []
    assert len(calls) == 1
    assert calls[0]["output_config"]["format"]["schema"] == SEMANTIC_SCHEMA


def test_validate_semantic_without_credentials_stops_before_any_call(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(cli, "check_semantics", lambda *args: pytest.fail("called the model without credentials"))

    assert cli.main(["validate", "--case-dir", str(JADE), "--semantic"]) == cli.EXIT_USAGE_ERROR
