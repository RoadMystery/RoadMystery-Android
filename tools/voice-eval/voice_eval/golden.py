"""Golden fixtures the Kotlin VoiceAgent must reproduce exactly.

The evaluation results only carry over to the app if the app renders the same prompts and
routes questions the same way, so the Kotlin tests replay this file. It is built from the case
the app ships (GOLDEN_CASE), and the samples below name that case's suspects, clues and phrases.
Regenerate it with `python -m voice_eval golden` whenever content or templates change; a test
fails if it is stale.
"""

from __future__ import annotations

import unicodedata
from dataclasses import asdict
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any

from voice_eval.content import ContentError, load_case, load_suspect
from voice_eval.identify import decompose_jamo, identify_suspect, suspect_aliases
from voice_eval.metrics import counted_sentences, normalize_for_match, phrase_hits, split_sentences
from voice_eval.prompt import VARIANTS, raised_topics, render_system_prompt
from voice_eval.summary import load_summary_probes, overview_fallback, render_summary_prompt

GOLDEN_VERSION = 1
GOLDEN_CASE = "jade-crown"
"""The case the app ships (Kotlin AndroidAssets.kt DEMO_CASE_DIR)."""

IDENTIFY_UTTERANCES: tuple[tuple[str, str | None], ...] = (
    ("준기한테 새벽에 어디 있었는지 물어봐", None),
    ("서준기 씨, 새벽에 서재엔 왜 갔어요?", None),
    ("하린 씨는 그때 어디 있었어요?", None),
    ("가정부한테 그날 밤 얘기 물어봐", None),
    ("준기한테 하린 아가씨 얘기 물어봐", None),
    ("하린 아가씨 얘기를 가정부한테 물어봐", None),
    ("서중기한테 새벽에 어디 있었는지 물어봐", None),
    ("하린씨한테 문단속 얘기 물어봐", None),
    ("옥분씨한테 부엌 얘기 물어봐", None),
    ("아드님께서 그러시는데 옥분 씨한테 물어봐", None),
    ("오늘 날씨 어때?", None),
    ("전무님은 괜찮으세요?", None),
    ("그럼 가정부터 해 볼게요", None),
    ("고정태는 어떤 사람이에요?", None),
    ("조카사위 얘기 좀 해 봐요", None),
    ("미안해요 다시 말할게요", None),
    ("윤하인 새벽에 어디 이써써요", None),
    ("하린시한테 물어봐", None),
    ("김옥순한테 그날 밤 얘기 물어봐", None),
    ("가전부한테 물어봐", None),
    ("옥이분한테 물어봐", None),
    ("도련님께 여쭤봐", None),
    ("준기 씨 새벽에 서재엔 왜 갔어요", None),
    ("하린 아가씨한테 물어봐", None),
    ("도련님은 왜 화관을 들고 있었어요", None),
    ("하린씨 문단쏙 돌았어요", None),
    ("아들딸 얘기는 됐고요", "maid"),
    ("그때 뭐 하고 있었어요?", "son"),
    ("가정부는 어디 있었어요?", "son"),
    ("옥분이 뒷문으로 나갔어요?", "son"),
    ("아버지랑 왜 다퉜어요?", "son"),
    ("준기씨한테 맨발 자국 얘기 물어봐", "maid"),
    ("중기한테 정태 얘기 물어봐", "niece"),
    ("서중기한테 정태 얘기 물어봐", "niece"),
)

SENTENCE_SAMPLES: tuple[str, ...] = (
    "그 시각에는 저장고에 있었습니다. 정말입니다! 왜 그러십니까?",
    "음... 잠시만요.",
    "그건… 모르겠습니다. 정말요?! 네.",
    "없습니다",
    "약 3.5초 걸렸습니다. 그렇죠?",
    "   ",
    "하, 참. 맨발이요? 그날 새벽에 서재로 내려간 것밖에 없어요. 그 전엔 방에서 나간 적 없고요.",
    "잠시만요, 탐정님. 열쇠는 제 허리춤에 있었습니다.",
    "네? 하, 참. 아니요. 저는 모르는 일이에요.",
    "저는 방에 있었어요. 네. 정말이에요.",
    "그건 잘 모르겠어요. 저는 방에 있었어요.",
    "OK. Fine! 알겠습니다.",
)

SIMILARITY_PAIRS: tuple[tuple[str, str], ...] = (
    ("집사", "지브사"),
    ("요리사", "요리싸"),
    ("서진씨", "서진시"),
    ("한도윤", "한도연"),
    ("서진", "사진"),
    ("박미란", "미란"),
    ("서준기", "서중기"),
    ("윤하린", "윤하인"),
    ("가정부", "가전부"),
    ("준기", "중기"),
    ("abcd", "bcde"),
    ("abab", "baba"),
    ("aaaa", "aa"),
    ("tide", "diet"),
    ("집사", ""),
    ("", ""),
)
"""Jamo-level difflib ratios behind fuzzy addressee matching, the hardest part to port."""

GUARD_SAMPLES: tuple[str, ...] = (
    "범인은… 하린이에요.",
    "제가... 화관을 건넸어요.",
    "범인은, 윤하린입니다!",
    "창·밖으로 건넸어요.",
    "범\u200B인은 접니다",
    "Ｆｕｌｌ\u3000ｗｉｄｔｈ 장물\u3000아비",
    unicodedata.normalize("NFD", "하린이가 범인이에요"),
    "사실은 고정태한테 넘 겼어요.",
    "🙂 「창문으로 건넸」대요",
    "저는 방에서 자고 있었어요.",
)
"""Answers the spoiler guard must catch (or pass) the same way in Python and Kotlin."""

TOPIC_QUESTIONS: tuple[tuple[str, tuple[str, ...], str], ...] = (
    ("maid", (), "부엌 뒷문 발자국은 누구 거예요?"),
    ("maid", ("clue-back-door",), "부엌 뒷문 발자국은 누구 거예요?"),
    ("niece", (), "혹시 실내화 신고 서재 쪽에 간 적 있어요?"),
    ("niece", (), "새벽에 서재 창문 연 적 없어요?"),
    ("son", ("clue-window",), "창틀 눈은 봤어요? 낡은 구 두는요? 맨발로 나갔어요?"),
    ("niece", (), "고정태 하숙방에 가 본 적 있어요?"),
    ("son", (), "새벽에 서재에는 왜 내려갔어요?"),
)
"""(suspect, clues found, question): questions that do or do not raise a clue not found yet."""


def build_golden(
    case_dir: Path, suspect_template: str, summary_template: str, summary_probes: Path
) -> dict[str, Any]:
    case = load_case(case_dir)
    if case.id != GOLDEN_CASE:
        raise ContentError(
            f"{case_dir}: 골든 표본은 {GOLDEN_CASE} 사건용입니다(받은 사건 {case.id})"
        )
    suspects = [
        load_suspect(case_dir / "suspects" / f"{member.id}.json", case)
        for member in case.cast
        if member.is_suspect
    ]
    clue_sets = [(), *((clue.id,) for clue in case.clues), tuple(clue.id for clue in case.clues)]
    prompts = [
        {
            "suspect": suspect.id,
            "unlocked": list(unlocked),
            "variant": variant,
            "prompt": render_system_prompt(case, suspect, frozenset(unlocked), variant, suspect_template),
        }
        for suspect in suspects
        for unlocked in clue_sets
        for variant in VARIANTS
    ]
    by_id = {suspect.id: suspect for suspect in suspects}
    topics = []
    for suspect_id, unlocked, question in TOPIC_QUESTIONS:
        raised = raised_topics(case, frozenset(unlocked), question)
        topics.append(
            {
                "suspect": suspect_id,
                "unlocked": list(unlocked),
                "question": question,
                "topics": list(raised),
                "prompt": render_system_prompt(
                    case, by_id[suspect_id], frozenset(unlocked), "cover", suspect_template, topics=raised
                ),
            }
        )
    summaries = [
        {
            "probe": probe.id,
            "unlocked": list(probe.unlocked),
            "testimonies": [asdict(testimony) for testimony in probe.testimonies],
            "prompt": render_summary_prompt(
                case, summary_template, frozenset(probe.unlocked), probe.testimonies
            ),
        }
        for probe in load_summary_probes(summary_probes, case)
        if not probe.is_empty
    ]
    aliases = suspect_aliases(case)
    return {
        "version": GOLDEN_VERSION,
        "case": case.id,
        "overview": overview_fallback(case),
        "prompts": prompts,
        "topics": topics,
        "summaries": summaries,
        "identify": [
            {"utterance": text, "default": default, "expected": identify_suspect(text, aliases, default)}
            for text, default in IDENTIFY_UTTERANCES
        ],
        "similarity": [
            {"a": a, "b": b, "ratio": SequenceMatcher(None, decompose_jamo(a), decompose_jamo(b)).ratio()}
            for a, b in SIMILARITY_PAIRS
        ],
        "sentences": [
            {"text": text, "sentences": list(split_sentences(text)), "counted": counted_sentences(split_sentences(text))}
            for text in SENTENCE_SAMPLES
        ],
        "guard": [
            {
                "text": text,
                "normalized": normalize_for_match(text),
                "hits": list(phrase_hits(text, case.forbidden_phrases)),
            }
            for text in GUARD_SAMPLES
        ],
    }
