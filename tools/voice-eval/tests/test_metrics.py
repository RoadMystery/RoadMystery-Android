import unicodedata
from collections.abc import Iterator

import pytest

from voice_eval.content import Case
from voice_eval.metrics import (
    StreamTimer,
    check_answer,
    first_sentence_end,
    format_violations,
    hangul_ratio,
    normalize_for_match,
    percentile,
    phrase_hits,
    split_sentences,
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

    leaky = check_answer("네. 사실은. 제가 훔쳤습니다. 동백 화분 밑에 있습니다.", case)
    assert leaky.over_three_sentences
    assert set(leaky.forbidden_hits) >= {"제가 훔쳤습니다", "동백", "화분 밑"}
