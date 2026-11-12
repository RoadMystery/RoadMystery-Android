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

from voice_eval.content import Case, ContentError, Suspect, load_case, load_suspect
from voice_eval.identify import decompose_jamo, identify_suspect, suspect_aliases
from voice_eval.lines import (
    CONFESSION_ID,
    NOT_UNDERSTOOD_ID,
    chosen_lines,
    heard_case_lines,
    heard_line_ids,
    render_line_prompt,
    speakable_lines,
)
from voice_eval.metrics import (
    address_terms,
    counted_sentences,
    drop_repeated_opening,
    looks_like_readout,
    normalize_for_match,
    opening_interjections,
    phrase_hits,
    split_sentences,
    strip_stage_directions,
)
from voice_eval.prompt import VARIANTS, confession_opens, raised_topics, render_system_prompt
from voice_eval.summary import load_summary_probes, overview_fallback, render_summary_prompt

GOLDEN_VERSION = 4
"""What the Kotlin parity test expects; 2 added the "gathering" prompt entry, 3 the "aware" variant, the
culprit's confession and the opening the app drops as it speaks, 4 the answers made of case lines (lines,
line_choices, heard)."""
GOLDEN_CASE = "jade-crown"
"""The case the app ships (Kotlin AndroidAssets.kt DEMO_CASE_DIR)."""
PRESSURE_STAGES = ("pressing", "ready")
"""Stages whose prompts can carry a persona's pressure line. A prompt entry without "stage" is
rendered at "opening"; the one "gathering" entry (GATHERING_SAMPLE) must render the same."""
GATHERING_SAMPLE: tuple[str, tuple[str, ...]] = ("son", ("clue-window",))
"""(suspect, clues found) of the "gathering" entry: a persona with pressure lines and some clues but
not all found, so the prompt is that of the stage-less entry for the same clues."""

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

READOUT_SAMPLES: tuple[str, ...] = (
    "[단서 없이 새로 나온 주장] 윤하린은 이 말을 처음 듣는다.",
    "생각할 필요 없이 답하면 된다.",
    "(sigh) 아니요, 방에서 안 나갔어요.",
    "（짧게） 하, 참, 그런 거 아니에요, 탐정님.",
    "【속마음】 모르는 척해야지요.",
    "고정태 이야기가 나오면 말이 짧아지는 설정이다",
    "세 문장 이내로 짧게 말해야 한다.”",
    "그건 제가 알 바 아니다.",
    "짧게 말해야 한다。",
    "[짧게] 어머, 탐정님, 그런 건 저는 잘 몰라요.",
    "*(방금 답한 것 같지만)* 어머, 탐정님.",
    "저는 (잠시 멈칫하며) 몰라요.",
    "(한숨).",
    "(생각해 보니.",
    "[think] Player asks about the outside conditions.",
    "Keep short, in character.",
    "모른다니까요, 탐정님.",
    "감사합니다.",
    "그날 밤엔 열한 시쯤 문단속을 돌고 바로 잤어요.",
    "그 시각에는 저장고에 있었습니다.",
    "그런 얘기보다 사건 얘기나 합시다.",
    "네? 무슨 말씀이신지 잘 모르겠어요, 탐정님.",
    "'범인은 하린이다'라고요?",
    "하, 참.",
)
"""Sentences the readout guard must stop (or let through) the same way in Python and Kotlin."""

SPEAKER_READOUT_SAMPLES: tuple[tuple[str, str], ...] = (
    ("김옥분은 얼굴이 빨개지며 박만수 아저씨를 만났다고 털어놓아야 합니다.", "maid"),
    ("윤하린이 아는 사실에 없는 질문이라 모르겠다고 답합니다.", "niece"),
    ("[짧게] 서준기는 하린이를 감싸야 합니다.", "son"),
    ("김옥분은 열 시쯤 잤다고 하던데요.", "son"),
    ("윤하린이에요, 탐정님.", "niece"),
    ("서준기 씨는 그날 서재에 내려갔어요.", "maid"),
)
"""(sentence, suspect speaking): their own full name as the subject is the prompt talking."""

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

CONFESSION_TURNS: tuple[tuple[str, str, tuple[str, ...]], ...] = (
    ("niece", "ready", ("하린 씨, 창 안쪽 실내화 자국 당신 거잖아요. 당신이 범인이죠?",)),
    ("niece", "ready", ("당신이 범인이죠?",)),
    ("niece", "pressing", ("서재 창 아래 이야기인데, 하린 씨가 그랬죠?",)),
    ("niece", "ready", ("부엌 뒷문 발자국은 하린 씨 거죠?",)),
    ("niece", "ready", ("서재 창 아래 이야기인데, 하린 씨가 그랬죠?", "왜 그랬어요?")),
    ("son", "ready", ("서재 창 아래 이야기인데, 준기 씨가 그랬죠?",)),
)
"""(suspect, stage, the player's turns to it): when the culprit's confession joins its "aware" prompt.
Every clue is found in each, as the later stages need."""

OPENING_SAMPLES: tuple[tuple[str, str, str], ...] = (
    ("son", "하, 참, 아니라니까요, 탐정님.", "하, 참. 화관 얘기나 하시죠."),
    ("son", "하, 참, 아니라니까요.", "아버지랑은 돈 얘기만 나오면 싸워요."),
    ("son", "하, 그건 몰라요.", "하, 참. 몇 번을 물어요."),
    ("maid", "어머, 탐정님, 그런 건 몰라요.", "어머, 저는 열 시쯤 잤어요."),
    ("maid", "어머.", "어머, 탐정님."),
    ("niece", "탐정님, 그건 모르겠어요.", "탐정님, 저는 방에 있었어요."),
    ("niece", "네, 그 실내화 자국은 제 거예요.", "네, 맞아요."),
    ("niece", "음... 그건 잘 모르겠어요.", "음, 글쎄요."),
    ("son", "아이고, 그건 몰라요.", ""),
)
"""(suspect, an answer's first sentence, the suspect's previous heard answer): the opening the app drops."""

LINE_CHOICES: tuple[tuple[str, tuple[str, ...], bool, str], ...] = (
    ("maid", (), False, "k-maid-night"),
    ("maid", (), False, "k-maid-night 그리고 범인은 하린 아가씨예요."),
    ("maid", (), False, "K-MAID-CROWN, k-maid-study, k-maid-culprit"),
    ("maid", (), False, "deflect-1, k-maid-culprit"),
    ("maid", (), False, "deflect-2"),
    ("maid", (), False, "deflect-9"),
    ("maid", (), False, "k-maid-back-door"),
    ("maid", ("clue-back-door",), False, "k-maid-night"),
    ("maid", ("clue-back-door",), False, "[k-maid-back-door]"),
    ("son", ("clue-window", "clue-shoes"), False, "k-son-shoes"),
    ("son", (), False, "k-son"),
    ("son", (), False, "k-son-crownx, k-son-room"),
    ("son", (), False, "not-understood, deflect-1"),
    ("son", (), False, ""),
    ("niece", ("clue-back-door", "clue-window", "clue-barefoot", "clue-shoes"), True, "confession, k-niece-lock"),
    ("niece", ("clue-back-door", "clue-window", "clue-barefoot", "clue-shoes"), False, "confession"),
)
"""(suspect, clues found, whether the confession is on offer, the model's reply): which lines the app reads."""

HEARD_LINES: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("maid", ("k-maid-crown",)),
    ("maid", ("deflect-1", "k-maid-study")),
    ("son", ("k-son-shoes", "k-son-friend")),
    ("niece", ("confession",)),
    ("niece", ("not-understood",)),
)
"""(suspect, ids): an answer made of those lines, as the notebook keeps it, and the ids found back in it."""

HEARD_TEXTS: tuple[tuple[str, str], ...] = (
    ("maid", "모델이 제 말로 지어낸 대답이에요."),
    ("son", "서재에 내려가기 전엔   방에서 나간 적 없어요!"),
    ("son", ""),
)
"""(suspect, answer): answers not made of lines word for word, or only with spacing and marks changed."""


def build_golden(
    case_dir: Path, suspect_template: str, summary_template: str, summary_probes: Path, lines_template: str
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
    every_clue = tuple(clue.id for clue in case.clues)
    clue_sets = [(), *((clue_id,) for clue_id in every_clue), every_clue]
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
    # The app reaches these stages only with every clue found, so that is the one clue set they need.
    staged_prompts = [
        {
            "suspect": suspect.id,
            "unlocked": list(every_clue),
            "variant": "cover",
            "stage": stage,
            "prompt": render_system_prompt(
                case, suspect, frozenset(every_clue), "cover", suspect_template, stage=stage
            ),
        }
        for suspect in suspects
        if suspect.pressure
        for stage in PRESSURE_STAGES
    ]
    by_id = {suspect.id: suspect for suspect in suspects}
    gathering_suspect, gathering_clues = GATHERING_SAMPLE
    gathering_prompt = {
        "suspect": gathering_suspect,
        "unlocked": list(gathering_clues),
        "variant": "cover",
        "stage": "gathering",
        "prompt": render_system_prompt(
            case, by_id[gathering_suspect], frozenset(gathering_clues), "cover", suspect_template,
            stage="gathering",
        ),
    }
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
    every = frozenset(every_clue)
    confessions = []
    for suspect_id, stage, questions in CONFESSION_TURNS:
        opens = confession_opens(case, by_id[suspect_id], "aware", stage, questions)
        confessions.append(
            {
                "suspect": suspect_id,
                "stage": stage,
                "questions": list(questions),
                "opens": opens,
                # As the app sends it: pressure lines are off by default, so the stage adds none.
                "prompt": render_system_prompt(case, by_id[suspect_id], every, "aware", suspect_template, confession=opens),
            }
        )
    openings = []
    for suspect_id, sentence, previous in OPENING_SAMPLES:
        keep = address_terms(by_id[suspect_id].speech_rules)
        openings.append(
            {
                "suspect": suspect_id,
                "sentence": sentence,
                "previous": previous,
                "pieces": list(opening_interjections(sentence, keep)[0]),
                "spoken": drop_repeated_opening(sentence, previous, keep),
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
        "prompts": [*prompts, gathering_prompt, *staged_prompts],
        "topics": topics,
        "confessions": confessions,
        "openings": openings,
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
        "readout": [
            _readout_entry(text, speaker_id, by_id[speaker_id].name if speaker_id else "")
            for text, speaker_id in [*((text, None) for text in READOUT_SAMPLES), *SPEAKER_READOUT_SAMPLES]
        ],
        **_line_entries(case, suspects, clue_sets, lines_template),
    }


def _line_entries(
    case: Case, suspects: list[Suspect], clue_sets: list[tuple[str, ...]], lines_template: str
) -> dict[str, list[dict[str, Any]]]:
    """The answers made of case lines (voice_eval.lines): the lines on offer and the prompt for each clue set,
    at the pressure stages with every clue found, and for the cornered culprit; which lines a reply names; and
    the lines found back in an answer the notebook kept."""
    every = tuple(clue.id for clue in case.clues)
    by_id = {suspect.id: suspect for suspect in suspects}
    turns = [
        *((suspect, unlocked, "opening", False) for suspect in suspects for unlocked in clue_sets),
        *((suspect, every, stage, False) for suspect in suspects if suspect.pressure for stage in PRESSURE_STAGES),
        *((suspect, every, "ready", True) for suspect in suspects if suspect.id == case.solution.culprit),
    ]
    lines = []
    for suspect, unlocked, stage, confession in turns:
        found = frozenset(unlocked)
        offered = speakable_lines(case, suspect, found, stage=stage, confession=confession)
        lines.append(
            {
                "suspect": suspect.id,
                "unlocked": list(unlocked),
                "stage": stage,
                "confession": confession,
                "lines": [asdict(line) for line in offered],
                "prompt": render_line_prompt(case, suspect, found, lines_template, stage=stage, confession=confession),
            }
        )
    choices = []
    for suspect_id, unlocked, confession, reply in LINE_CHOICES:
        # As the app asks by default: pressure lines are off, so the stage adds none.
        offered = speakable_lines(case, by_id[suspect_id], frozenset(unlocked), confession=confession)
        choices.append(
            {
                "suspect": suspect_id,
                "unlocked": list(unlocked),
                "confession": confession,
                "reply": reply,
                "chosen": [line.id for line in chosen_lines(reply, offered)],
            }
        )
    answers = [
        (suspect_id, " ".join(_line_text(by_id[suspect_id], line_id) for line_id in ids))
        for suspect_id, ids in HEARD_LINES
    ]
    heard = [
        {
            "suspect": suspect_id,
            "answer": answer,
            "ids": list(heard_line_ids(by_id[suspect_id], answer)),
            "case_lines": list(heard_case_lines(by_id[suspect_id], answer)),
        }
        for suspect_id, answer in [*answers, *HEARD_TEXTS]
    ]
    return {"lines": lines, "line_choices": choices, "heard": heard}


def _line_text(suspect: Suspect, line_id: str) -> str:
    """The text of [suspect]'s line [line_id], whatever the clues found, for HEARD_LINES; the ids are those of
    voice_eval.lines ("deflect-1" is the first deflection)."""
    texts = {fact.id: fact.line for fact in (*suspect.public, *suspect.unlockable) if fact.line}
    texts.update({f"deflect-{index}": text for index, text in enumerate(suspect.deflections, start=1)})
    texts[NOT_UNDERSTOOD_ID] = suspect.fallback_not_understood
    if suspect.confession:
        texts[CONFESSION_ID] = suspect.confession
    return texts[line_id]


def _readout_entry(text: str, speaker_id: str | None, speaker: str) -> dict[str, Any]:
    spoken = strip_stage_directions(text)
    return {
        "sentence": text,
        "speaker": speaker_id,
        "readout": looks_like_readout(text, speaker),
        "spoken": spoken,
        "spoken_readout": looks_like_readout(spoken, speaker),
    }
