import unicodedata
from collections.abc import Iterator

import pytest

from voice_eval.content import Case
from voice_eval.metrics import (
    StreamTimer,
    check_answer,
    counted_sentences,
    first_sentence_end,
    format_violations,
    hangul_ratio,
    looks_like_readout,
    normalize_for_match,
    percentile,
    phrase_hits,
    split_sentences,
    strip_stage_directions,
)


def _clock(times: list[float]) -> "callable":
    values: Iterator[float] = iter(times)
    return lambda: next(values)


def test_split_sentences_counts_korean_sentences() -> None:
    text = "그 시각에는 저장고에 있었습니다. 정말입니다! 왜 그러십니까?"
    assert split_sentences(text) == (
        "그 시각에는 저장고에 있었습니다.",
        "정말입니다!",
        "왜 그러십니까?",
    )
    assert split_sentences("없습니다") == ("없습니다",)
    assert split_sentences("   ") == ()


def test_ellipsis_is_hesitation_not_a_sentence_end() -> None:
    assert split_sentences("음... 잠시만요.") == ("음... 잠시만요.",)
    assert split_sentences("그건… 모르겠습니다. 정말요?! 네.") == (
        "그건… 모르겠습니다.",
        "정말요?!",
        "네.",
    )


def test_short_openers_do_not_count_toward_the_sentence_limit() -> None:
    # An interjection or an echoed question must not push the denial out of a three-sentence answer.
    sentences = split_sentences("하, 참. 맨발이요? 그날 새벽에 서재로 내려간 것밖에 없어요. 그 전엔 방에서 나간 적 없고요.")
    assert len(sentences) == 4
    assert counted_sentences(sentences) == 2
    assert counted_sentences(split_sentences("잠시만요, 탐정님. 열쇠는 제 허리춤에 있었습니다.")) == 1
    assert counted_sentences(()) == 0


def test_only_the_first_two_short_sentences_are_free() -> None:
    assert counted_sentences(split_sentences("네? 하, 참. 아니요. 저는 모르는 일이에요.")) == 2
    # Once the answer has started, a short sentence counts like any other.
    assert counted_sentences(split_sentences("저는 방에 있었어요. 네. 정말이에요.")) == 3
    # Eight letters is already an answer, not an opener.
    assert counted_sentences(split_sentences("그건 잘 모르겠어요. 저는 방에 있었어요.")) == 2


def test_first_sentence_end() -> None:
    assert first_sentence_end("있었습니다. 그리고") == len("있었습니다.")
    assert first_sentence_end("있었습니다.") == len("있었습니다.")
    assert first_sentence_end("있었습니") is None


def test_hangul_ratio_ignores_spaces_and_punctuation() -> None:
    assert hangul_ratio("안녕하십니까.") == 1.0
    assert hangul_ratio("안녕 ok") == pytest.approx(0.5)
    assert hangul_ratio("...") == 0.0


def test_format_violations_detects_non_speech_markup() -> None:
    found = format_violations("**네** (한숨) 알겠습니다 😀\n- 목록")
    assert set(found) >= {"markdown", "bracket", "emoji", "list"}
    assert format_violations("평범한 문장입니다. 그렇습니까?") == ()


def test_phrase_hits_ignore_whitespace() -> None:
    assert phrase_hits("제가 훔 쳤습니다.", ("제가 훔쳤", "동백")) == ("제가 훔쳤",)
    assert phrase_hits("모르는 일입니다.", ("제가 훔쳤",)) == ()


@pytest.mark.parametrize(
    "text",
    [
        "범인은… 집사입니다.",
        "범인은, 집사입니다!",
        "범\u200B인은 집사입니다",
        "「범인은 집사입니다」",
        unicodedata.normalize("NFD", "범인은 집사입니다"),
    ],
)
def test_phrase_hits_see_through_punctuation_and_lookalikes(text: str) -> None:
    assert phrase_hits(text, ("범인은 집사입니다",)) == ("범인은 집사입니다",)


def test_phrases_made_only_of_punctuation_never_match() -> None:
    assert phrase_hits("아무 말이나 합니다.", ("...", " ")) == ()


def test_normalize_keeps_only_letters_digits_and_marks() -> None:
    assert normalize_for_match("Ｆｕｌｌ\u3000ｗｉｄｔｈ 3.5초!") == "fullwidth35초"


def test_in_character_denials_are_not_keyword_leaks(case: Case) -> None:
    denial = check_answer("제가 범인이라니 당치 않습니다. 제가 훔쳤다는 말씀입니까?", case)
    assert denial.forbidden_hits == ()


def test_stream_timer_measures_first_token_and_first_sentence() -> None:
    timer = StreamTimer(start=0.0, clock=_clock([0.4, 0.9, 1.0, 1.2]))
    timer.on_text("지하 저장고")
    timer.on_text("에 있었습니다.")
    timer.on_text(" 정말입니다.")
    timing = timer.finish()

    assert timer.text == "지하 저장고에 있었습니다. 정말입니다."
    assert timing.ttft_ms == pytest.approx(400)
    # The first sentence is confirmed when the next chunk starts with a space.
    assert timing.ttfs_ms == pytest.approx(1000)
    assert timing.total_ms == pytest.approx(1200)


def test_number_with_period_mid_stream_is_not_a_sentence() -> None:
    timer = StreamTimer(start=0.0, clock=_clock([0.2, 0.5, 0.7]))
    timer.on_text("약 3.")
    timer.on_text("5초 걸렸습니다.")
    timing = timer.finish()
    assert timing.ttfs_ms == pytest.approx(700)


def test_stream_timer_without_sentence_end_uses_total() -> None:
    timer = StreamTimer(start=0.0, clock=_clock([0.3, 0.5]))
    timer.on_text("없습니다")
    timing = timer.finish()
    assert timing.ttfs_ms == pytest.approx(500)


def test_stream_timer_with_no_text() -> None:
    timer = StreamTimer(start=0.0, clock=_clock([0.7]))
    timing = timer.finish()
    assert timing.ttft_ms is None and timing.ttfs_ms is None
    assert timing.total_ms == pytest.approx(700)


def test_ignores_empty_chunks_for_first_token() -> None:
    timer = StreamTimer(start=0.0, clock=_clock([0.1, 0.6, 0.8]))
    timer.on_text("")
    timer.on_text("네.")
    timing = timer.finish()
    assert timing.ttft_ms == pytest.approx(600)


def test_percentile_nearest_rank() -> None:
    values = [100.0, 200.0, 300.0, 400.0]
    assert percentile(values, 50) == 200.0
    assert percentile(values, 90) == 400.0
    assert percentile([], 50) is None
    assert percentile([float(v) for v in range(1, 101)], 7) == 7.0


def test_check_answer_flags_long_and_leaky_answers(case: Case) -> None:
    clean = check_answer("저장고에 있었습니다. 아무것도 못 봤습니다.", case)
    assert clean.sentence_count == 2
    assert not clean.over_three_sentences
    assert clean.forbidden_hits == ()

    leaky = check_answer("그날 밤 일을 말씀드리겠습니다. 사실은 정전 때였습니다. 제가 훔쳤습니다. 동백 화분 밑에 있습니다.", case)
    assert leaky.over_three_sentences
    assert set(leaky.forbidden_hits) >= {"제가 훔쳤습니다", "동백", "화분 밑"}


def test_check_answer_counts_sentences_the_way_the_app_limits_them(case: Case) -> None:
    opened = check_answer("하, 참. 창밖이요? 못 봤어요, 탐정님. 그때는 화관만 보고 있었거든요.", case)
    assert opened.sentence_count == 4
    assert not opened.over_three_sentences


@pytest.mark.parametrize(
    "sentence",
    [
        "[단서 없이 새로 나온 주장] 윤하린은 이 말을 처음 듣는다.",
        "생각할 필요 없이 답하면 된다.",
        "(sigh) 아니요, 방에서 안 나갔어요.",
        "（짧게） 하, 참, 그런 거 아니에요.",
        "세 문장 이내로 짧게 말해야 한다.”",
        "고정태 이야기가 나오면 말이 짧아지는 설정이다",
        "그건 제가 알 바 아니다.",
        "짧게 말해야 한다。",
    ],
)
def test_stage_directions_and_instructions_look_like_readouts(sentence: str) -> None:
    assert looks_like_readout(sentence)


@pytest.mark.parametrize(
    "sentence",
    [
        "모른다니까요, 탐정님.",
        "그 시각에는 저장고에 있었습니다.",
        "그런 얘기보다 사건 얘기나 합시다.",
        "'범인은 하린이다'라고요?",
        "하, 참.",
        "감사합니다.",
    ],
)
def test_suspect_speech_does_not_look_like_a_readout(sentence: str) -> None:
    assert not looks_like_readout(sentence)


def test_stage_directions_are_dropped_from_what_is_spoken() -> None:
    assert strip_stage_directions("[짧게] 어머, 탐정님.") == "어머, 탐정님."
    assert strip_stage_directions("*(방금 답한 것 같지만)* 어머, 탐정님.") == "어머, 탐정님."
    assert strip_stage_directions("저는 (잠시 멈칫하며) 몰라요.") == "저는 몰라요."
    assert strip_stage_directions("(한숨).") == ""
    assert strip_stage_directions("그 시각에는  저장고에 있었습니다.") == "그 시각에는  저장고에 있었습니다."
    assert looks_like_readout(strip_stage_directions("[단서 없이 새로 나온 주장] 윤하린은 이 말을 처음 듣는다."))


def test_a_suspect_named_as_the_subject_is_their_prompt_talking() -> None:
    planning = "윤서진이 아는 사실에 없는 질문이라 모른다고 답해야 합니다."
    assert looks_like_readout(planning, "윤서진")
    assert not looks_like_readout(planning)
    assert looks_like_readout(strip_stage_directions("[짧게] 윤서진은 화제를 돌립니다."), "윤서진")
    assert not looks_like_readout("윤서진이에요, 탐정님.", "윤서진")
    assert not looks_like_readout("윤서진입니다.", "윤서진")
    assert not looks_like_readout("박미란은 그날 일찍 잤다던데요.", "윤서진")


def test_check_answer_flags_an_answer_that_reads_out_its_instructions(case: Case) -> None:
    readout = check_answer("생각할 필요 없이 답하면 된다. 열 시쯤 잤어요.", case)
    assert readout.reads_instructions
    assert not check_answer("열 시쯤 잤어요. 그 뒤엔 아무것도 못 봤어요.", case).reads_instructions
    named = "윤서진이 아는 사실에 없는 질문이라 모른다고 답해야 합니다. 몰라요."
    assert check_answer(named, case, "윤서진").reads_instructions
    assert not check_answer(named, case).reads_instructions
