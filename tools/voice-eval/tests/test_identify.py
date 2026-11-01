import pytest

from voice_eval.content import Case
from voice_eval.identify import decompose_jamo, identify_suspect, suspect_aliases


@pytest.fixture(scope="module")
def aliases(case: Case) -> dict[str, tuple[str, ...]]:
    return suspect_aliases(case)


def test_only_suspects_are_addressable(aliases: dict[str, tuple[str, ...]]) -> None:
    assert set(aliases) == {"butler", "niece", "cook"}


@pytest.mark.parametrize(
    ("utterance", "expected"),
    [
        ("집사한테 어젯밤 어디 있었는지 물어봐", "butler"),
        ("한도윤 씨, 정전 때 뭐 했어요?", "butler"),
        ("서진 씨는 그때 어디 있었어요?", "niece"),
        ("요리사한테 부엌 얘기 좀 물어봐", "cook"),
        ("집사한테 서진 아가씨 얘기 물어봐", "butler"),
        ("서진 아가씨 얘기를 집사한테 물어봐", "butler"),
        ("지브사한테 어제 정전 때 어디 있었는지 물어봐", "butler"),
        ("서진씨한테 전당포 영수증 얘기 물어봐", "niece"),
        ("박여사한테 부엌 얘기 물어봐", "cook"),
        ("집사님께서 그러시는데 박 여사한테 물어봐", "cook"),
        ("오늘 날씨 어때?", None),
        ("회장님은 괜찮으세요?", None),
        # Ordinary words that contain or resemble a name are not suspects.
        ("집사람이 그러는데 정전 됐대", None),
        ("그것도 윤 회장 생일이었죠", None),
        ("요리 사진 좀 보여줘", None),
        ("사진 좀 보여줘", None),
        ("미안해요 다시 말할게요", None),
        # A garbled name without 한테/에게 is not trusted on its own.
        ("지브사 어제 정전 때 어디 이써써요", None),
    ],
)
def test_identify_suspect(
    aliases: dict[str, tuple[str, ...]], utterance: str, expected: str | None
) -> None:
    assert identify_suspect(utterance, aliases) == expected


def test_default_is_used_when_nobody_is_named(aliases: dict[str, tuple[str, ...]]) -> None:
    assert identify_suspect("그때 뭐 하고 있었어요?", aliases, default="butler") == "butler"


@pytest.mark.parametrize(
    "utterance",
    [
        "요리사는 어디 있었어요?",
        # STT output drops punctuation, so a mention cannot be told apart from a vocative.
        "서진식 코트에서 전당포 영수증이 나왔어요 알고 계셨어요",
        "서진 씨 코트에서 전당포 영수증이 나왔어요 알고 계셨어요",
    ],
)
def test_mentioning_another_suspect_keeps_the_current_one(
    aliases: dict[str, tuple[str, ...]], utterance: str
) -> None:
    assert identify_suspect(utterance, aliases, default="butler") == "butler"


def test_explicit_addressee_switches_suspect(aliases: dict[str, tuple[str, ...]]) -> None:
    assert identify_suspect("요리사한테 어디 있었는지 물어봐", aliases, default="butler") == "cook"


def test_misrecognized_addressee_still_switches(aliases: dict[str, tuple[str, ...]]) -> None:
    assert identify_suspect("지브사한테 정전 얘기 물어봐", aliases, default="cook") == "butler"


def test_decompose_jamo_splits_syllables() -> None:
    assert decompose_jamo("집사") == "ㅈㅣㅂㅅㅏ"
    assert decompose_jamo("a집") == "aㅈㅣㅂ"
