"""Render a suspect's system prompt from case content and the player's unlocked clues."""

from __future__ import annotations

import re
from collections.abc import Iterable, Mapping

from voice_eval.content import (
    STAGES,
    Case,
    Clue,
    Fact,
    Probe,
    Suspect,
    UnlockableFact,
    history_error,
)
from voice_eval.metrics import phrase_hits

VARIANTS = ("cover", "aware", "truth")
"""cover: the suspect only knows its cover story. aware: the culprit also knows it hides something,
and its confession joins the prompt only once it is cornered (confession_opens); the app's default
(docs/voice-agent/game-rules.md). truth: the hidden truth is included so the model can lie
consistently - the harness measures whether that leaks more."""

_PLACEHOLDER = re.compile(r"\{\{(\w+)\}\}")
_HANGUL_BASE = 0xAC00
_HANGUL_LAST = 0xD7A3
_RIEUL_FINAL = 8

_FAMILY_RULE = "- 가족이 함께 듣는 이야기다. 폭력적이거나 잔인하거나 무서운 묘사는 하지 마라."
_SECRET_INTRO = (
    "아래는 네가 거짓말을 일관되게 하도록 알려 주는 배경이다. 이 내용을 인정하거나 암시하지 말고, "
    "플레이어가 아직 찾지 않은 증거를 먼저 꺼내지 마라. 추궁을 받아도 '네가 말할 수 있는 사실'의 "
    "주장을 지켜라."
)
_TOPIC_RULE = (
    "- 플레이어가 지금 꺼낸 {topics} 이야기는 '네가 말할 수 있는 사실'과 '플레이어가 찾은 단서'에 있는 "
    "만큼만 말하라. 누가 무엇을 가졌는지, 언제 어디서 무엇을 했는지 새로 보태지 말고, 나머지는 '인물'에 "
    "적힌 {name}의 성격대로 짧게 받아넘겨라."
)
_PRESSURE_HEADING = "# 수사가 깊어진 지금 하게 되는 말 (어울릴 때만)"
_BELONGINGS_HEADING = "# 네가 가진 것"
_HIDING_SECTION = (
    "# 너만 아는 것\n"
    "- 너는 이 사건에서 숨기는 일이 있다. '네가 말할 수 있는 사실'의 이야기는 그 일을 감추려는 너의 주장이다. "
    "무엇을 숨기는지는 플레이어가 찾은 단서가 드러낸 만큼만 떠오른다.\n"
    "- 들키면 곤란하니 그 주장을 지켜라. 불리한 이야기는 짧게 넘기되 물은 것에는 답하라. "
    "숨기는 일이 있다는 것은 말로 인정하지 말고, 말투에만 드러나게 하라.\n"
    "- 거짓말은 그 주장 안에서만 하라. 새 알리바이를 만들거나 남에게 덮어씌우지 마라."
)
_CONFESSION_SECTION = (
    "# 다 들킨 지금\n"
    "- 플레이어가 단서를 다 찾아 너의 주장이 무너졌다. 플레이어가 그 증거를 들이밀며 너를 범인으로 몰아붙이면, "
    "더 버티지 말고 '플레이어가 찾은 단서'에서 '너를 범인으로 몰아붙일 때 너의 반응'에 적힌 말대로 털어놓아라. "
    "그 말에 없는 것은 보태지 마라.\n"
    "- 아직 털어놓지 않았고 너를 몰아붙이는 말도 아니면, 지금까지처럼 주장을 지켜라."
)
_CONFESSION_RULE = (
    "다만 플레이어가 찾은 증거를 들이밀며 너를 범인으로 몰아붙이면, 단서에 대한 반응으로 버티지 말고 "
    "아래 '다 들킨 지금'의 말대로 털어놓아라."
)


def _final_consonant(word: str) -> int | None:
    """Index of the last syllable's final consonant (0 = none); None if not Hangul."""
    if not word:
        return None
    code = ord(word[-1])
    if not _HANGUL_BASE <= code <= _HANGUL_LAST:
        return None
    return (code - _HANGUL_BASE) % 28


def with_subject_particle(word: str) -> str:
    """한도윤 -> 한도윤이, 집사 -> 집사가."""
    return word + ("이" if _final_consonant(word) else "가")


def with_instrumental_particle(word: str) -> str:
    """한도윤 -> 한도윤으로, 집사 -> 집사로, 미란 -> 미란으로, 길 -> 길로."""
    final = _final_consonant(word)
    return word + ("으로" if final and final != _RIEUL_FINAL else "로")


def bullets(lines: Iterable[str]) -> str:
    return "\n".join(f"- {line}" for line in lines)


def unlocked_facts(suspect: Suspect, unlocked: frozenset[str]) -> tuple[UnlockableFact, ...]:
    """Reactions whose required clues have all been found."""
    return tuple(fact for fact in suspect.unlockable if set(fact.unlocked_by) <= unlocked)


def _replaced(suspect: Suspect, unlocked: frozenset[str]) -> set[str]:
    return {target for fact in unlocked_facts(suspect, unlocked) for target in fact.replaces}


def shown_facts(suspect: Suspect, unlocked: frozenset[str]) -> tuple[tuple[Fact, ...], tuple[UnlockableFact, ...]]:
    """The public facts and reactions the prompt shows: an unlocked reaction hides the lines it
    [replaces]. allowed_facts leaves those out too, and taken_back lists them for the judge."""
    reactions = unlocked_facts(suspect, unlocked)
    replaced = _replaced(suspect, unlocked)
    return (
        tuple(fact for fact in suspect.public if fact.id not in replaced),
        tuple(fact for fact in reactions if fact.id not in replaced),
    )


def taken_back(suspect: Suspect, unlocked: frozenset[str]) -> tuple[str, ...]:
    """Answer keys of the claims a found reaction took back ([replaces]), public lines first: what the suspect
    said before the clue broke it, which it may not say again (docs/voice-agent/before-found-denials.md)."""
    replaced = _replaced(suspect, unlocked)
    return tuple(fact.text for fact in (*suspect.public, *suspect.unlockable) if fact.id in replaced)


def require_known_clues(case: Case, unlocked: frozenset[str]) -> None:
    unknown = sorted(unlocked - {clue.id for clue in case.clues})
    if unknown:
        raise ValueError(f"unknown clue id(s): {', '.join(unknown)}")


def allowed_facts(case: Case, suspect: Suspect, unlocked: frozenset[str]) -> tuple[str, ...]:
    """What the suspect may state as fact: the case overview, its own profile and relationships,
    its public knowledge and the reactions the found clues unlocked, less the lines those reactions
    took back (taken_back). The judge and the sentence check both treat these as already public.
    Facts come as their answer-key text, not the first-person line the suspect's prompt shows."""
    relationships = (
        f"{case.member(relationship.target).name}에 대해: {relationship.attitude}"
        for relationship in suspect.relationships
    )
    owner = with_subject_particle(suspect.name)
    public, reactions = shown_facts(suspect, unlocked)
    return (
        *case.public_facts,
        f"{suspect.name}의 이력: {suspect.age}세. {suspect.background}",
        *_stance_lines(suspect),
        *relationships,
        *(f"{owner} 가진 것: {item}" for item in suspect.belongings),
        *(fact.text for fact in public),
        *(fact.text for fact in reactions),
    )


def _stance_lines(suspect: Suspect) -> list[str]:
    """What the suspect wants and how it treats the detective (stance), for its profile."""
    lines = []
    if suspect.goal:
        lines.append(f"이 심문에서 바라는 것: {suspect.goal}")
    if suspect.toward_detective:
        lines.append(f"탐정을 대하는 태도: {suspect.toward_detective}")
    return lines


def _unlocked_section(case: Case, suspect: Suspect, unlocked: frozenset[str], confession: bool = False) -> str:
    """Each found clue, then the reactions it brings up. A first-person line does not say when it is
    said, as the answer key's "…이야기에" did, so its label names its clues; a reaction to several
    clues goes once, under the last of them in case order, the clue the narrator's hint names."""
    clues = [clue for clue in case.clues if clue.id in unlocked]
    if not clues:
        return "- 아직 플레이어가 찾은 단서가 없다."
    order = [clue.id for clue in case.clues]
    _, reactions = shown_facts(suspect, unlocked)
    lines: list[str] = []
    for clue in clues:
        lines.append(f"[단서] {clue.title}: {clue.summary}")
        for fact in reactions:
            if max(fact.unlocked_by, key=order.index) == clue.id:
                lines.extend(_reaction_lines(case, suspect, fact, order, confession))
    return bullets(lines)


def _reaction_lines(
    case: Case, suspect: Suspect, fact: UnlockableFact, order: list[str], confession: bool
) -> list[str]:
    """A reaction's line. Once the culprit is cornered (confession_opens), each key reaction splits in two:
    the reaction when it is only asked about, and the confession when that clue is used to accuse it, so the
    line the question's clue points at already holds the confession."""
    titles = "·".join(case.clue(clue_id).title for clue_id in sorted(fact.unlocked_by, key=order.index))
    demeanor = f" (태도: {fact.demeanor})" if fact.demeanor else ""
    if not (confession and fact.key):
        return [f"[{titles} 이야기에 너의 반응] {fact.prompt_text}{demeanor}"]
    return [
        f"[{titles} 이야기에 너의 반응 (몰아붙이지 않을 때)] {fact.prompt_text}{demeanor}",
        f"[{titles} 이야기로 너를 범인으로 몰아붙일 때 너의 반응] {suspect.confession}",
    ]


def _speech_section(suspect: Suspect) -> str:
    # Examples go in as written: a repeated opening exclamation is the app's to drop as it speaks
    # (metrics.drop_repeated_opening), since cutting it here only moved the habit to another one.
    examples = "\n".join(f"  - {example}" for example in suspect.speech_examples)
    return "\n".join(
        [
            f"- 말투: {suspect.register}",
            bullets(suspect.speech_rules),
            f"- 예시:\n{examples}",
        ]
    )


def _relationship_section(case: Case, suspect: Suspect) -> str:
    lines = []
    for relationship in suspect.relationships:
        member = case.member(relationship.target)
        lines.append(f"{member.name}({member.role}): {relationship.attitude}")
    return bullets(lines)


def pressure_line(suspect: Suspect, stage: str) -> str | None:
    """The persona's line for [stage]: "ready" falls back to "pressing", earlier stages have none."""
    lines = dict(suspect.pressure)
    if stage == "ready":
        return lines.get("ready") or lines.get("pressing")
    return lines.get("pressing") if stage == "pressing" else None


def _pressure_section(suspect: Suspect, stage: str) -> str:
    """{{pressure}} follows the found-clue section directly, so the value brings its own blank line;
    without a line for the stage it is empty and the prompt is as before."""
    line = pressure_line(suspect, stage)
    return f"\n\n{_PRESSURE_HEADING}\n- {line}" if line else ""


def _belongings_section(suspect: Suspect) -> str:
    """{{belongings}} follows the public facts directly, so the value brings its own blank line; without
    belongings it is empty and the prompt is as before."""
    return f"\n\n{_BELONGINGS_HEADING}\n{bullets(suspect.belongings)}" if suspect.belongings else ""


def _secret_section(case: Case, suspect: Suspect, variant: str, confession: bool) -> str:
    """truth: the hidden truth. aware: for the culprit only, that it hides something, or once it is
    cornered (confession_opens) when to confess in place of that; the words are under the key clue
    (_reaction_lines). Empty otherwise."""
    if variant == "truth":
        facts = bullets(fact.text for fact in suspect.secret)
        return f"\n# 너만 아는 진실 (절대 말하지 말 것)\n{_SECRET_INTRO}\n{facts}"
    if variant != "aware" or suspect.id != case.solution.culprit:
        return ""
    if confession:
        return f"\n{_CONFESSION_SECTION}"
    return f"\n{_HIDING_SECTION}"


def _confession_rule(confession: bool) -> str:
    """{{confession_rule}} ends the bullet on who did it: a cornered culprit confesses before it would hold out
    with a clue's reaction, as rule 3 comes before rule 6 in docs/voice-agent/game-rules.md. Empty on every
    other turn, which leaves that line exactly as it was."""
    return f" {_CONFESSION_RULE}" if confession else ""


def raised_topics(case: Case, unlocked: frozenset[str], question: str) -> tuple[str, ...]:
    """Keywords of clues not found yet that the question brings up, matched like the spoiler guard."""
    locked = (keyword for clue in case.clues if clue.id not in unlocked for keyword in clue.keywords)
    return phrase_hits(question, locked)


def mentioned_clues(case: Case, question: str) -> tuple[Clue, ...]:
    """The clues, in case order, that [question] brings up by a keyword, the title or a mention, matched
    like the spoiler guard (Kotlin agent/Investigation.mentionedClues)."""
    return tuple(clue for clue in case.clues if phrase_hits(question, (*clue.keywords, clue.title, *clue.mentions)))


def key_clue_ids(suspect: Suspect) -> frozenset[str]:
    """The clues that unlock [suspect]'s key reactions: for the culprit, what broke its cover story."""
    return frozenset(clue_id for fact in suspect.unlockable if fact.key for clue_id in fact.unlocked_by)


def confession_opens(case: Case, suspect: Suspect, variant: str, stage: str, questions: Iterable[str]) -> bool:
    """Whether the culprit's confession joins its prompt (docs/voice-agent/game-rules.md): in the "aware"
    variant, once every clue is found and every key reaction drawn out ("ready"), when one of the player's
    [questions] to it, the one being answered or an earlier one it answered, brings up a clue that unlocks
    one of its key reactions.

    The app opens it only on a question asked at "ready" and keeps it from the first such turn the player
    heard answered (Kotlin agent/Confession, AgentState.cornered). A probe's earlier turns count as asked at
    the probe's stage and heard, so a probe whose history corners the culprit before "ready" would measure a
    prompt the app never sends; probegen's corner questions have no history.
    """
    if variant != "aware" or stage != "ready" or suspect.id != case.solution.culprit or not suspect.confession:
        return False
    cornering = key_clue_ids(suspect)
    return any(clue.id in cornering for question in questions for clue in mentioned_clues(case, question))


def _topic_rule(suspect: Suspect, topics: tuple[str, ...]) -> str:
    """Keeps the answer about a raised topic to the listed facts; adds nothing when none is raised.

    {{topic_rule}} sits at the end of the unfound-evidence bullet, so the rule brings its own line
    break and an empty rule leaves that line exactly as it was.
    """
    if not topics:
        return ""
    quoted = ", ".join(f"'{topic}'" for topic in topics)
    return "\n" + _TOPIC_RULE.format(topics=quoted, name=suspect.name)


def render_system_prompt(
    case: Case,
    suspect: Suspect,
    unlocked: frozenset[str],
    variant: str,
    template: str,
    *,
    topics: tuple[str, ...] = (),
    stage: str = "opening",
    confession: bool = False,
) -> str:
    """[confession]: from confession_opens; only the culprit of the "aware" variant with a confession line can have it."""
    if variant not in VARIANTS:
        raise ValueError(f"unknown variant '{variant}' (expected one of {', '.join(VARIANTS)})")
    if stage not in STAGES:
        raise ValueError(f"unknown stage '{stage}' (expected one of {', '.join(STAGES)})")
    if confession and (variant != "aware" or suspect.id != case.solution.culprit or not suspect.confession):
        raise ValueError(f"'{suspect.id}' cannot confess in the '{variant}' variant")
    require_known_clues(case, unlocked)

    values = {
        "case_title": case.title,
        "name": suspect.name,
        "name_ga": with_subject_particle(suspect.name),
        "name_ro": with_instrumental_particle(suspect.name),
        "role": suspect.role,
        "not_understood": suspect.fallback_not_understood,
        "theme_rule": theme_rule(case),
        "topic_rule": _topic_rule(suspect, topics),
        "confession_rule": _confession_rule(confession),
        "public_facts": case_overview(case),
        "profile": bullets(
            [f"{suspect.age}세. {suspect.background}", *suspect.personality, *_stance_lines(suspect)]
        ),
        "speech_style": _speech_section(suspect),
        "public_knowledge": bullets(fact.prompt_text for fact in shown_facts(suspect, unlocked)[0]),
        "belongings": _belongings_section(suspect),
        "unlocked_knowledge": _unlocked_section(case, suspect, unlocked, confession),
        # Once cornered the confession takes the stage line's place: the culprit's "ready" line holds out.
        "pressure": "" if confession else _pressure_section(suspect, stage),
        "relationships": _relationship_section(case, suspect),
        "deflections": bullets(suspect.deflections),
        "secret_section": _secret_section(case, suspect, variant, confession),
    }
    return fill_template(template, values)


def fill_template(template: str, values: Mapping[str, str]) -> str:
    """Replace {{name}} placeholders; an unknown placeholder is an error, never left in place."""

    def substitute(match: re.Match[str]) -> str:
        key = match.group(1)
        if key not in values:
            raise ValueError(f"template uses unknown placeholder '{{{{{key}}}}}'")
        return values[key]

    return _PLACEHOLDER.sub(substitute, template).strip() + "\n"


def theme_rule(case: Case) -> str:
    return _FAMILY_RULE if case.theme == "family" else ""


def case_overview(case: Case) -> str:
    return f"{case.setting}\n{bullets(case.public_facts)}"


def player_turns(probe: Probe) -> tuple[str, ...]:
    """What the player has said to the suspect in [probe]: earlier turns, then the question."""
    return (*(turn["content"] for turn in probe.history if turn["role"] == "user"), probe.question)


def build_messages(probe: Probe) -> list[dict[str, str]]:
    """Prior turns (alternating, starting with the player) plus the probe question."""
    problem = history_error(probe.history)
    if problem is not None:
        raise ValueError(f"probe '{probe.id}': {problem}")
    return [*(dict(turn) for turn in probe.history), {"role": "user", "content": probe.question}]
