import pytest

from voice_eval.clue_check import (
    SOLUTION,
    CheckError,
    build_check_message,
    build_check_system,
    parse_check,
)
from voice_eval.content import Case, Probe, Suspect
from voice_eval.judge import build_judge_prompt
from voice_eval.proxy import MAX_MESSAGE_CHARS, MAX_SYSTEM_CHARS

QUESTION = "지하실 문 열쇠는 누가 갖고 있어요?"
FUSEBOX = frozenset({"clue-fusebox"})


def _section(prompt: str, title: str) -> str:
    start = prompt.index(title)
    end = prompt.find("\n\n# ", start)
    return prompt[start : end if end != -1 else None]


def test_clues_not_found_are_listed_with_their_ids_and_found_ones_as_public(
    case: Case, butler: Suspect
) -> None:
    prompt = build_check_system(case, butler, FUSEBOX, (), QUESTION)

    hidden = _section(prompt, "# 아직 찾지 않은 단서")
    assert "- [clue-cellar-key] 지하실 열쇠: 지하실 문은 늘 잠겨 있고" in hidden
    assert "clue-fusebox" not in hidden
    assert "- 지하실 두꺼비집: 정전 직후" in _section(prompt, "# 플레이어가 찾은 단서")
    assert "- 범인: 한도윤(집사)" in _section(prompt, "# 사건 정답")
    assert prompt.endswith("none만 적는다.")
    assert "{{" not in prompt


def test_with_nothing_found_the_found_section_says_so(case: Case, butler: Suspect) -> None:
    prompt = build_check_system(case, butler, frozenset(), (), QUESTION)

    assert _section(prompt, "# 플레이어가 찾은 단서") == "# 플레이어가 찾은 단서\n- 없음"


def test_the_check_and_the_judge_treat_the_same_facts_as_public(
    case: Case, butler: Suspect, probes: tuple[Probe, ...]
) -> None:
    unlocked = frozenset({"clue-cellar-key", "clue-fusebox"})
    check = build_check_system(case, butler, unlocked, (), QUESTION)
    judge = build_judge_prompt(case, butler, probes[0], unlocked, "모릅니다.")

    title = "# 용의자가 말해도 되는 사실"
    assert _section(check, title) == _section(judge, title)


def test_the_dialogue_keeps_the_last_three_exchanges_and_ends_with_the_question(
    case: Case, butler: Suspect
) -> None:
    history = [
        {"role": role, "content": f"{word} {n}"}
        for n in range(1, 6)
        for role, word in (("user", "질문"), ("assistant", "대답"))
    ]

    dialogue = _section(build_check_system(case, butler, frozenset(), history, QUESTION), "# 지금까지의 대화")

    assert "질문 2" not in dialogue
    assert "플레이어: 질문 3\n용의자: 대답 3" in dialogue
    assert dialogue.endswith(f"용의자: 대답 5\n플레이어: {QUESTION}")


def test_the_largest_check_fits_the_proxy_limits(case: Case, butler: Suspect) -> None:
    longest = "가" * MAX_MESSAGE_CHARS
    history = [{"role": role, "content": longest} for _ in range(10) for role in ("user", "assistant")]

    assert len(build_check_system(case, butler, frozenset(), history, longest)) <= MAX_SYSTEM_CHARS
    assert len(build_check_message([longest, longest], longest)) == MAX_MESSAGE_CHARS


def test_the_message_shows_what_was_already_said_then_the_new_sentence() -> None:
    assert build_check_message([], "열쇠 말씀이십니까.") == (
        "# 이번 대답에서 앞서 한 말\n없음\n\n# 새 문장\n열쇠 말씀이십니까."
    )
    assert build_check_message(["하나입니다.", "둘입니다."], "셋입니다.") == (
        "# 이번 대답에서 앞서 한 말\n하나입니다. 둘입니다.\n\n# 새 문장\n셋입니다."
    )


def test_an_overlong_message_drops_the_start_of_what_came_before_then_the_end_of_the_sentence() -> None:
    message = build_check_message(["앞" * 990, "끝."], "새 문장입니다.")
    assert len(message) == MAX_MESSAGE_CHARS
    assert message.endswith("앞 끝.\n\n# 새 문장\n새 문장입니다.")

    message = build_check_message([], "말" * 2000)
    assert len(message) == MAX_MESSAGE_CHARS
    assert message.startswith("# 이번 대답에서 앞서 한 말\n없음\n\n# 새 문장\n말")


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("none", ()),
        ("  None.\n", ()),
        ("없음", ()),
        ("clue-cellar-key", ("clue-cellar-key",)),
        ("clue-cellar-key, solution", ("clue-cellar-key", SOLUTION)),
        ("`solution`", (SOLUTION,)),
        ("clue-cellar-key,clue-cellar-key", ("clue-cellar-key",)),
    ],
)
def test_parse_check_reads_none_or_the_ids_it_was_shown(case: Case, text: str, expected: tuple[str, ...]) -> None:
    assert parse_check(text, case, FUSEBOX) == expected


@pytest.mark.parametrize(
    "text",
    ["", "clue-fusebox", "clue-unknown", "none, clue-cellar-key", "열쇠를 드러냈습니다"],
)
def test_parse_check_rejects_anything_else(case: Case, text: str) -> None:
    # clue-fusebox is already found, so it is not among the ids the check was shown.
    with pytest.raises(CheckError):
        parse_check(text, case, FUSEBOX)


def test_unknown_clue_ids_are_rejected(case: Case, butler: Suspect) -> None:
    with pytest.raises(ValueError, match="clue-nope"):
        build_check_system(case, butler, frozenset({"clue-nope"}), (), QUESTION)
