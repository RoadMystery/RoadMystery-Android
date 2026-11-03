"""Golden fixtures the Kotlin VoiceAgent must reproduce exactly.

The evaluation results only carry over to the app if the app renders the same prompts and
routes questions the same way, so the Kotlin tests replay this file. Regenerate it with
`python -m voice_eval golden` whenever content or templates change; a test fails if it is stale.
"""

from __future__ import annotations

import unicodedata
from dataclasses import asdict
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any

from voice_eval.content import load_case, load_suspect
from voice_eval.identify import decompose_jamo, identify_suspect, suspect_aliases
from voice_eval.metrics import counted_sentences, normalize_for_match, phrase_hits, split_sentences
from voice_eval.prompt import VARIANTS, raised_topics, render_system_prompt
from voice_eval.summary import load_summary_probes, overview_fallback, render_summary_prompt

GOLDEN_VERSION = 1

IDENTIFY_UTTERANCES: tuple[tuple[str, str | None], ...] = (
    ("집사한테 어젯밤 어디 있었는지 물어봐", None),
    ("한도윤 씨, 정전 때 뭐 했어요?", None),
    ("서진 씨는 그때 어디 있었어요?", None),
    ("요리사한테 부엌 얘기 좀 물어봐", None),
    ("집사한테 서진 아가씨 얘기 물어봐", None),
    ("서진 아가씨 얘기를 집사한테 물어봐", None),
    ("지브사한테 어제 정전 때 어디 있었는지 물어봐", None),
    ("서진씨한테 전당포 영수증 얘기 물어봐", None),
    ("박여사한테 부엌 얘기 물어봐", None),
    ("집사님께서 그러시는데 박 여사한테 물어봐", None),
    ("오늘 날씨 어때?", None),
    ("회장님은 괜찮으세요?", None),
    ("집사람이 그러는데 정전 됐대", None),
    ("그것도 윤 회장 생일이었죠", None),
    ("요리 사진 좀 보여줘", None),
    ("미안해요 다시 말할게요", None),
    ("지브사 어제 정전 때 어디 이써써요", None),
    ("서진시한테 물어봐", None),
    ("한도연한테 그날 밤 얘기 물어봐", None),
    ("박미랑한테 물어봐", None),
    ("요리싸한테 부엌 얘기 물어봐", None),
    ("요리사님께 여쭤봐", None),
    ("집사람한테 물어봐", "niece"),
    ("그때 뭐 하고 있었어요?", "butler"),
    ("요리사는 어디 있었어요?", "butler"),
    ("서진식 코트에서 전당포 영수증이 나왔어요 알고 계셨어요", "butler"),
    ("요리사한테 어디 있었는지 물어봐", "butler"),
    ("지브사한테 정전 얘기 물어봐", "cook"),
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
    ("abcd", "bcde"),
    ("abab", "baba"),
    ("aaaa", "aa"),
    ("tide", "diet"),
    ("집사", ""),
    ("", ""),
)
"""Jamo-level difflib ratios behind fuzzy addressee matching, the hardest part to port."""

GUARD_SAMPLES: tuple[str, ...] = (
    "범인은… 집사입니다.",
    "제가... 훔쳤습니다.",
    "범인은, 집사입니다!",
    "정원의 동·백나무 아래요.",
    "범\u200B인은 집사입니다",
    "Ｆｕｌｌ\u3000ｗｉｄｔｈ 화분\u3000밑",
    unicodedata.normalize("NFD", "동백꽃이 피었어요"),
    "사실은 제가 훔 쳤어요.",
    "🙂 「화분 밑」에 뒀어요",
    "저는 저장고에 있었습니다.",
)
"""Answers the spoiler guard must catch (or pass) the same way in Python and Kotlin."""

TOPIC_QUESTIONS: tuple[tuple[str, tuple[str, ...], str], ...] = (
    ("butler", (), "지하실 문 열쇠는 누가 갖고 있어요?"),
    ("butler", ("clue-cellar-key",), "지하실 문 열쇠는 누가 갖고 있어요?"),
    ("butler", (), "두꺼비집 차단기는 누가 내렸어요?"),
    ("cook", (), "영수증은요? 고무장갑은 쓰세요?"),
    ("niece", ("clue-fusebox",), "두꺼비집 차단기 내려간 거 알았어요? 열 쇠는요?"),
    ("niece", (), "정전 때 어디 있었어요?"),
)
"""(suspect, clues found, question): questions that do or do not raise a clue not found yet."""


def build_golden(
    case_dir: Path, suspect_template: str, summary_template: str, summary_probes: Path
) -> dict[str, Any]:
    case = load_case(case_dir)
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
