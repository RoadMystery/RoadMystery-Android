"""Rules a case must pass before the app runs it (docs/voice-agent/scenario-rules.md).

tests/test_cases.py holds every case under content/cases/ to them, and ``python -m voice_eval validate``
checks a case someone wrote or a generator made before it is added. Each check returns its problems as
messages naming the file part at fault; a case passes when no check returns any.
"""

from __future__ import annotations

import itertools
from collections import Counter
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from difflib import SequenceMatcher
from pathlib import Path

from voice_eval.content import STAGES, Case, ContentError, Suspect, load_case, load_suspect
from voice_eval.metrics import (
    MAX_SENTENCES,
    address_terms,
    counted_sentences,
    looks_like_readout,
    normalize_for_match,
    opening_interjections,
    phrase_hits,
    refuses,
    split_sentences,
)
from voice_eval.prompt import allowed_facts, key_clue_ids, render_system_prompt

NARRATOR_SPEAKER = "haeun"
"""The app's F-06 narrator voice (AgentConfig.narratorSpeaker), so no suspect may use it."""
MAX_SHARED_RUN = 8
"""Letters in a row, after normalize_for_match, that a narrator hint or a line offered to a suspect may
share with a hidden fact or the solution: room for a name, a place or a time, not for the fact."""


@dataclass(frozen=True)
class Exemptions:
    """What a case measured before a rule existed keeps (demo-sapphire)."""

    measured: bool = False
    """Skips the rules for new cases: a hint for every clue and the end, a line for every fact, a key reaction."""
    cross_clue_lines: frozenset[tuple[str, str]] = frozenset()
    """(suspect, reaction) whose answer key may name a clue that does not unlock it."""


NO_EXEMPTIONS = Exemptions()
"""A case written now: every rule applies."""


@dataclass(frozen=True)
class Subject:
    """A loaded case and what every check needs besides it."""

    case: Case
    suspects: dict[str, Suspect]
    template: str
    exemptions: Exemptions


Check = Callable[[Subject], list[str]]


def load_subject(
    case_dir: Path, template: str, exemptions: Exemptions = NO_EXEMPTIONS, schema_dir: Path | None = None
) -> Subject:
    """Load [case_dir] and its suspects; raises ContentError when a file does not load."""
    case = load_case(case_dir, schema_dir)
    suspects = {
        member.id: load_suspect(case_dir / "suspects" / f"{member.id}.json", case)
        for member in case.cast
        if member.is_suspect
    }
    return Subject(case, suspects, template, exemptions)


def validate_case(
    case_dir: Path, template: str, exemptions: Exemptions = NO_EXEMPTIONS, schema_dir: Path | None = None
) -> list[str]:
    """Every problem with [case_dir]: one message when it does not load, else one per broken rule."""
    try:
        subject = load_subject(case_dir, template, exemptions, schema_dir)
    except ContentError as error:
        return [f"불러오지 못함: {error}"]
    return [problem for check in CHECKS for problem in check(subject)]


def _case_text_shown_before_any_clue(case: Case) -> list[tuple[str, str]]:
    """(where, text) of the case text in every suspect prompt, whatever the player has found."""
    return [("사건 개요", case.setting), *(("공개 사실", fact) for fact in case.public_facts)]


def _persona_shown_before_any_clue(suspect: Suspect) -> list[tuple[str, str]]:
    """(where, text) of the persona text in every prompt of [suspect], and the answer keys of its public
    facts, which the judge and the sentence check treat as said."""
    return [
        ("배경", suspect.background),
        *(("성격", trait) for trait in suspect.personality),
        *(("바라는 것", text) for text in (suspect.goal, suspect.toward_detective) if text),
        *(("가진 것", item) for item in suspect.belongings),
        ("말투", suspect.register),
        *(("말투 규칙", rule) for rule in suspect.speech_rules),
        *(("예시 대사", example) for example in suspect.speech_examples),
        *((fact.id, text) for fact in suspect.public for text in dict.fromkeys((fact.text, fact.prompt_text))),
        *(("관계", relationship.attitude) for relationship in suspect.relationships),
        *(("화제 돌리기", deflection) for deflection in suspect.deflections),
    ]


def _keywords_except(case: Case, clue_ids: Iterable[str]) -> list[str]:
    skip = set(clue_ids)
    return [keyword for clue in case.clues if clue.id not in skip for keyword in clue.keywords]


def _hidden_facts_except(case: Case, clue_ids: Iterable[str]) -> list[str]:
    skip = set(clue_ids)
    return [fact for clue in case.clues if clue.id not in skip for fact in clue.hidden_facts]


def _solution_texts(case: Case) -> tuple[str, ...]:
    solution = case.solution
    return (solution.method, solution.motive, solution.hiding_place, *solution.hidden_facts)


def _answer(case: Case) -> tuple[str, ...]:
    """The hidden facts and the solution text, which neither a narrator hint nor a line the prompt offers
    a suspect to say may contain."""
    return (*_hidden_facts_except(case, ()), *_solution_texts(case))


def _shared_run(text: str, other: str) -> str:
    """The longest stretch [text] and [other] have in common once normalized like the spoiler guard."""
    left, right = normalize_for_match(text), normalize_for_match(other)
    # No autojunk: it would skip the letters most common in a long text such as the solution's method.
    match = SequenceMatcher(None, left, right, autojunk=False).find_longest_match(0, len(left), 0, len(right))
    return left[match.a : match.a + match.size]


def overlong_runs(text: str, others: Iterable[str]) -> list[str]:
    """The stretches longer than MAX_SHARED_RUN that [text] shares with any of [others]."""
    return [run for other in others if len(run := _shared_run(text, other)) > MAX_SHARED_RUN]


def voices(subject: Subject) -> list[str]:
    voices = [suspect.pleos_speaker for suspect in subject.suspects.values()]
    problems = [] if subject.suspects else ["등장인물: 용의자가 없습니다"]
    if len(set(voices)) != len(voices):
        problems.append(f"목소리: 용의자 목소리가 겹칩니다 {voices}")
    if NARRATOR_SPEAKER in voices:
        problems.append(f"목소리: '{NARRATOR_SPEAKER}'는 진행자 목소리라 용의자가 쓸 수 없습니다")
    return problems


def aliases(subject: Subject) -> list[str]:
    every = [alias for member in subject.case.cast for alias in member.aliases]
    repeated = sorted({alias for alias in every if every.count(alias) > 1})
    return [f"등장인물: 별칭이 겹칩니다 {repeated}"] if repeated else []


def keywords_before_their_clue(subject: Subject) -> list[str]:
    case, problems = subject.case, []
    every_keyword = _keywords_except(case, ())
    # A found clue's title and summary go into the prompt, so they must not name another clue.
    for clue in case.clues:
        if hits := phrase_hits(f"{clue.title} {clue.summary}", _keywords_except(case, (clue.id,))):
            problems.append(f"단서 {clue.id}: 제목·설명에 다른 단서의 키워드 {list(hits)}가 있습니다")
    for where, text in _case_text_shown_before_any_clue(case):
        if hits := phrase_hits(text, every_keyword):
            problems.append(f"{where}: 단서를 찾기 전에도 보이는 글에 단서 키워드 {list(hits)}가 있습니다: {text}")
    for suspect in subject.suspects.values():
        for where, text in _persona_shown_before_any_clue(suspect):
            if hits := phrase_hits(text, every_keyword):
                problems.append(
                    f"{suspect.id}/{where}: 단서를 찾기 전에도 보이는 글에 단서 키워드 {list(hits)}가 있습니다: {text}"
                )
        for fact in suspect.unlockable:
            if (suspect.id, fact.id) in subject.exemptions.cross_clue_lines:
                continue
            for text in dict.fromkeys((fact.text, fact.prompt_text)):
                if hits := phrase_hits(text, _keywords_except(case, fact.unlocked_by)):
                    problems.append(f"{suspect.id}/{fact.id}: 이 반응을 열지 않는 단서의 키워드 {list(hits)}가 있습니다")
        for line in suspect.before_found:
            if hits := phrase_hits(line.text, _keywords_except(case, (line.clue,))):
                problems.append(f"{suspect.id}/beforeFound {line.clue}: 다른 단서의 키워드 {list(hits)}가 있습니다")
    return problems


def forbidden_phrases_in_persona(subject: Subject) -> list[str]:
    case, problems = subject.case, []
    for where, text in _case_text_shown_before_any_clue(case):
        if hits := phrase_hits(text, case.forbidden_phrases):
            problems.append(f"{where}: 금지 표현 {list(hits)}가 있습니다: {text}")
    for suspect in subject.suspects.values():
        texts = [
            *_persona_shown_before_any_clue(suspect),
            *((fact.id, text) for fact in suspect.unlockable for text in dict.fromkeys((fact.text, fact.prompt_text))),
            *((f"beforeFound {line.clue}", line.text) for line in suspect.before_found),
        ]
        for where, text in texts:
            if hits := phrase_hits(text, case.forbidden_phrases):
                problems.append(f"{suspect.id}/{where}: 금지 표현 {list(hits)}가 있습니다: {text}")
    return problems


def hints(subject: Subject) -> list[str]:
    # The narrator speaks a clue's hint while that clue and perhaps others are not found, and the
    # spoiler guard does not check it. Naming another clue would point at it before the story offers it
    # (the barefoot hint once named the window clue's title); the clue's own mentions are fine.
    case, problems = subject.case, []
    every_keyword = _keywords_except(case, ())
    answer = _answer(case)
    for clue in case.clues:
        if clue.hint is None:
            continue
        other_clues = [
            text for other in case.clues if other.id != clue.id for text in (*other.keywords, other.title, *other.mentions)
        ]
        if hits := phrase_hits(clue.hint, every_keyword):
            problems.append(f"단서 {clue.id}: 힌트에 단서 키워드 {list(hits)}가 있습니다")
        if hits := phrase_hits(clue.hint, other_clues):
            problems.append(f"단서 {clue.id}: 힌트가 다른 단서 {list(hits)}를 가리킵니다")
        problems += _gives_nothing_away(f"단서 {clue.id}: 힌트", clue.hint, case, answer)
    if case.spoiler_hint is not None:
        problems += _gives_nothing_away("사건 힌트(spoilerGuard.hint)", case.spoiler_hint, case, answer)
    return problems


def _gives_nothing_away(where: str, text: str, case: Case, answer: tuple[str, ...]) -> list[str]:
    problems = []
    if hits := phrase_hits(text, case.forbidden_phrases):
        problems.append(f"{where}에 금지 표현 {list(hits)}가 있습니다")
    if any(fact in text for fact in answer) or overlong_runs(text, answer):
        problems.append(f"{where}가 숨긴 사실이나 정답을 옮깁니다")
    return problems


def hint_for_every_clue_and_the_end(subject: Subject) -> list[str]:
    if subject.exemptions.measured:
        return []
    case = subject.case
    problems = [f"단서 {clue.id}: 진행자 힌트(hint)가 없습니다" for clue in case.clues if not clue.hint]
    if not case.spoiler_hint:
        problems.append("사건 힌트(spoilerGuard.hint)가 없습니다")
    return problems


def lines_are_speech(subject: Subject) -> list[str]:
    # Given '~라고 말한다' answer keys, the model sometimes said aloud its plan for turning them into
    # speech (docs/voice-agent/scenario-jade-crown.md). A line the app would take for a read-out would
    # become one whenever the model repeated it word for word.
    if subject.exemptions.measured:
        return []
    problems = []
    for suspect in subject.suspects.values():
        for fact in (*suspect.public, *suspect.unlockable):
            if not fact.line:
                problems.append(f"{suspect.id}/{fact.id}: 1인칭 대사(line)가 없습니다")
                continue
            for sentence in split_sentences(fact.line):
                if looks_like_readout(sentence, suspect.name):
                    problems.append(f"{suspect.id}/{fact.id}: 대사가 말이 아니라 지시문처럼 보입니다: {sentence}")
        if suspect.confession:
            sentences = split_sentences(suspect.confession)
            for sentence in sentences:
                if looks_like_readout(sentence, suspect.name):
                    problems.append(f"{suspect.id}/confession: 자백이 말이 아니라 지시문처럼 보입니다: {sentence}")
            if counted_sentences(sentences) > MAX_SENTENCES:
                problems.append(f"{suspect.id}/confession: 자백이 {MAX_SENTENCES}문장을 넘어 앱이 끝까지 읽지 않습니다")
    return problems


def lines_give_nothing_away(subject: Subject) -> list[str]:
    # The model may repeat a line word for word. A public fact's line is in every prompt and a reaction's
    # once the clues that unlock it are found, so neither may restate a fact still hidden then. A person
    # the answer key does not name would be news the suspect volunteers.
    case, problems = subject.case, []
    names = [name for member in case.cast for name in (member.name, *member.aliases)]
    for suspect in subject.suspects.values():
        facts = [*((fact, ()) for fact in suspect.public), *((fact, fact.unlocked_by) for fact in suspect.unlockable)]
        for fact, unlocked_by in facts:
            if fact.line is None:
                continue
            still_hidden = (*_hidden_facts_except(case, unlocked_by), *_solution_texts(case))
            if overlong_runs(fact.line, still_hidden):
                problems.append(f"{suspect.id}/{fact.id}: 대사가 아직 숨긴 사실이나 정답을 옮깁니다")
            if extra := set(phrase_hits(fact.line, names)) - set(phrase_hits(fact.text, names)):
                problems.append(f"{suspect.id}/{fact.id}: 대사에 답지(text)에 없는 이름 {sorted(extra)}이 있습니다")
    return problems


def pressure_lines(subject: Subject) -> list[str]:
    # The prompt offers a pressure line as something to say, so the model may repeat it word for word.
    # Every clue is found by then, so a clue keyword is fine; a read-out, the answer or the culprit's name
    # is not (the son's lines once kept bringing 하린 up unasked).
    case, problems = subject.case, []
    answer = _answer(case)
    culprit = case.member(case.solution.culprit)
    for suspect in subject.suspects.values():
        for stage, line in suspect.pressure:
            where = f"{suspect.id}/pressure {stage}"
            for sentence in split_sentences(line):
                if looks_like_readout(sentence, suspect.name):
                    problems.append(f"{where}: 대사가 지시문처럼 보입니다: {sentence}")
            if (
                phrase_hits(line, case.forbidden_phrases)
                or any(text in line for text in answer)
                or overlong_runs(line, _solution_texts(case))
            ):
                problems.append(f"{where}: 금지 표현이나 정답을 담고 있습니다")
            if phrase_hits(line, (culprit.name, *culprit.aliases)):
                problems.append(f"{where}: 범인 이름이 있습니다")
    return problems


def mentions(subject: Subject) -> list[str]:
    # The app finds a mention in a question with spacing ignored and inside longer words, so one letter
    # would match far too much (창 in 창고), and naming the solution must not count as bringing a clue up.
    # A question naming another clue's keyword or title must not count as confronting this one too: the
    # shoes clue's "구두" was found in the window clue's "구두 자국" (and in "구두쇠").
    case, problems = subject.case, []
    every = [normalize_for_match(mention) for clue in case.clues for mention in clue.mentions]
    if repeated := sorted({mention for mention in every if every.count(mention) > 1}):
        problems.append(f"mentions가 단서끼리 겹칩니다 {repeated}")
    for clue in case.clues:
        other_clues = [text for other in case.clues if other.id != clue.id for text in (*other.keywords, other.title)]
        for mention in clue.mentions:
            if len(normalize_for_match(mention)) < 2:
                problems.append(f"단서 {clue.id}: mention '{mention}'은 두 글자 이상이어야 합니다")
            if phrase_hits(mention, case.forbidden_phrases):
                problems.append(f"단서 {clue.id}: mention '{mention}'에 금지 표현이 있습니다")
            if [text for text in other_clues if phrase_hits(text, (mention,))]:
                problems.append(f"단서 {clue.id}: mention '{mention}'이 다른 단서의 키워드·제목에서도 찾아집니다")
    return problems


def key_reactions(subject: Subject) -> list[str]:
    # The app keeps the key reactions drawn out as one set of ids for the whole cast (AgentState.confronted).
    # "pressing" lasts from the last clue found until every key reaction is drawn out, so without a key
    # reaction the investigation would go from "gathering" straight to "ready".
    key_ids = [fact.id for suspect in subject.suspects.values() for fact in suspect.unlockable if fact.key]
    problems = []
    if repeated := sorted({key_id for key_id in key_ids if key_ids.count(key_id) > 1}):
        problems.append(f"핵심 반응 id가 용의자끼리 겹칩니다 {repeated}")
    if not key_ids and not subject.exemptions.measured:
        problems.append("핵심 반응(key)이 하나도 없습니다")
    return problems


def prompts_keep_secrets(subject: Subject) -> list[str]:
    # Every stage, because pressure lines join the prompt from "pressing" on, and both variants without
    # the hidden truth: the app's "aware" one adds that the culprit hides something and, once it is
    # cornered, its confession. That one prompt may give the culprit away; no other may hold the confession.
    case, problems = subject.case, []
    clue_ids = [clue.id for clue in case.clues]
    found_sets = [found for size in range(len(clue_ids) + 1) for found in itertools.combinations(clue_ids, size)]
    solution = (case.solution.method, case.solution.motive, case.solution.hiding_place)
    for suspect in subject.suspects.values():
        for found, stage, variant in itertools.product(found_sets, STAGES, ("cover", "aware")):
            prompt = render_system_prompt(case, suspect, frozenset(found), variant, subject.template, stage=stage)
            where = f"{suspect.id} 프롬프트({variant}, 찾은 단서 {list(found)}, {stage})"
            if any(fact.text in prompt for fact in suspect.secret):
                problems.append(f"{where}에 숨길 사실이 들어갑니다")
            if hits := phrase_hits(prompt, case.forbidden_phrases):
                problems.append(f"{where}에 금지 표현 {list(hits)}가 들어갑니다")
            if any(text in prompt for text in solution):
                problems.append(f"{where}에 정답이 들어갑니다")
            if suspect.confession and suspect.confession in prompt:
                problems.append(f"{where}에 자백 대사가 들어갑니다")
        if suspect.id == case.solution.culprit and suspect.confession:
            prompt = render_system_prompt(
                case, suspect, frozenset(clue_ids), "aware", subject.template, stage="ready", confession=True
            )
            where = f"{suspect.id} 자백 프롬프트"
            if any(fact.text in prompt for fact in suspect.secret) or any(text in prompt for text in solution):
                problems.append(f"{where}에 자백 대사 말고도 숨길 사실이나 정답이 들어갑니다")
            if suspect.confession not in prompt:
                problems.append(f"{where}에 자백 대사가 없습니다")
    return problems


def sheet_items(subject: Subject) -> list[str]:
    # What the suspect AI decides with (docs/voice-agent/game-rules.md): without a stance it only reads its
    # lines, without belongings it disowns its own things, and a culprit needs a key reaction to be cornered
    # by and a confession to make then.
    if subject.exemptions.measured:
        return []
    case, problems = subject.case, []
    for suspect in subject.suspects.values():
        if not (suspect.goal and suspect.toward_detective):
            problems.append(f"{suspect.id}: 바라는 것과 태도(stance)가 없습니다")
        if not suspect.belongings:
            problems.append(f"{suspect.id}: 가진 것(knowledge.belongings)이 없습니다")
        is_culprit = suspect.id == case.solution.culprit
        if is_culprit and not suspect.confession:
            problems.append(f"{suspect.id}: 범인의 자백 대사(confession)가 없습니다")
        if is_culprit and not key_clue_ids(suspect):
            problems.append(f"{suspect.id}: 범인의 겉 이야기를 무너뜨릴 핵심 반응(key)이 없습니다")
        if not is_culprit and suspect.confession:
            problems.append(f"{suspect.id}: 범인이 아닌데 자백 대사(confession)가 있습니다")
    return problems


def deflections_do_not_refuse(subject: Subject) -> list[str]:
    # The model says a persona's deflection as it is, so one that refuses ("제 일만 말할 수 있어요") becomes
    # an answer that sounds like hiding, whatever the template says (박서진, 2026-11-06 blind test).
    return [
        f"{suspect.id}/화제 돌리기: 거절하는 말입니다. 사건 이야기로 돌리는 말로 바꿔 주세요: {line}"
        for suspect in subject.suspects.values()
        for line in suspect.deflections
        if refuses(line)
    ]


def example_openings_vary(subject: Subject) -> list[str]:
    # The model copies an opening interjection most of a persona's lines share into nearly every answer.
    # The app drops a repeat as it speaks (metrics.drop_repeated_opening), but then most answers would
    # lose their first word.
    problems = []
    for suspect in subject.suspects.values():
        keep = address_terms(suspect.speech_rules)
        lines = (*suspect.speech_examples, *suspect.deflections)
        counts = Counter(pieces for line in lines if (pieces := opening_interjections(line, keep)[0]))
        problems += [
            f"{suspect.id}: 예시 대사와 화제 돌리기 {len(lines)}개 중 {count}개가 같은 첫마디 '{', '.join(pieces)}'로 시작합니다"
            for pieces, count in counts.items()
            if count >= 2 and count * 2 > len(lines)
        ]
    return problems


def hidden_facts_are_not_allowed_facts(subject: Subject) -> list[str]:
    case = subject.case
    hidden = {fact for clue in case.clues for fact in clue.hidden_facts} | set(case.solution.hidden_facts)
    return [
        f"{suspect.id}: 숨긴 사실이 말해도 되는 사실과 같습니다 {sorted(copied)}"
        for suspect in subject.suspects.values()
        if (copied := hidden & set(allowed_facts(case, suspect, frozenset())))
    ]


CHECKS: tuple[Check, ...] = (
    voices,
    aliases,
    keywords_before_their_clue,
    forbidden_phrases_in_persona,
    hints,
    hint_for_every_clue_and_the_end,
    lines_are_speech,
    lines_give_nothing_away,
    pressure_lines,
    mentions,
    key_reactions,
    prompts_keep_secrets,
    hidden_facts_are_not_allowed_facts,
    sheet_items,
    deflections_do_not_refuse,
    example_openings_vary,
)
