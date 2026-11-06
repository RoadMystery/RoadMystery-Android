"""The authoring rules a model has to read (docs/voice-agent/scenario-rules.md): whether a line or its answer
key gives the solution away in other words, and whether a line says what its answer key says.

voice_eval.validate compares letters, so a paraphrase of the motive, or a line that changes its answer key's
fact, passes it. semantic_request asks a model to read every public fact and reaction against the solution,
and semantic_problems turns the verdicts into validate-style messages. It runs when a case is added
(python -m voice_eval validate --semantic, and the case generator's loop), never in the tests.
"""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from typing import Any

from voice_eval.content import Case, Suspect, UnlockableFact
from voice_eval.judge import DEFAULT_JUDGE_EFFORT, DEFAULT_JUDGE_MODEL, request_structured_json

SEMANTIC_MODEL = DEFAULT_JUDGE_MODEL
"""The judge's model, so giving the solution away means what the measurement counts as a leak."""
SEMANTIC_SYSTEM = """너는 차량용 음성 추리 게임의 시나리오 검수자다. 용의자가 말해도 되는 사실 하나하나를 사건의 정답과 견주어 판정한다.

# 받는 것
- solution: 범인, 수법, 동기, 숨긴 곳
- clues: 단서 제목과 설명
- facts: 용의자가 말해도 되는 사실. public은 처음부터, reaction은 found_clues의 단서를 찾은 뒤 말할 수 있다. line은 용의자가 그대로 말할 대사, text는 채점용 답지다. key는 사건을 풀려면 들어야 하는 반응이다.
- reaction의 replaces는 그 반응이 열리면 거두는(프롬프트에서 빼는) 같은 용의자의 앞선 줄 id다. public의 replaced_by는 그 줄을 거두는 reaction id이고, breaking_clues는 그 reaction을 여는 단서다. 거두는 줄이 있는 public은 단서로 깨질 주장이다.

# 판정 기준
- reveals_solution: 용의자가 이 line이나 text대로 말하면, 플레이어가 더 추리하지 않아도 정답을 알게 되는가. true는 세 가지뿐이다. 범인이 자기 범행, 수법, 동기, 숨긴 곳을 스스로 털어놓는 것. 누구든 범인이 누구라고 단정하는 것. 누구든 없어진 물건이 지금 어디 있다고 단정하는 것. 무엇을 보았다, 무엇이 이상했다, 누가 그 시각 어디에 있었다는 목격담, 범인이 아닌 사람이 자기가 겪은 일을 털어놓는 것, 단서에 대한 변명이나 부인은 범인이나 숨긴 곳을 강하게 가리켜도 플레이어가 추리할 재료라서 false다.
- line_differs_from_text: line과 text가 서로 다른 사건 사실(누가, 무엇을, 언제, 어디서, 왜, 어떻게)을 말하거나, line에만 있는 사건 사실(장소나 그 위치, 시각, 사람, 물건, 까닭 등)이 있는가. 용의자는 line대로 말하고 채점은 text로 하므로, line에만 있는 사실은 지어낸 말로 채점된다. text에만 있는 설명(어떤 이야기가 나오면 어떻게 반응하는지 등), 말투, 감정, 부르는 말, 인사말이나 맞장구는 다름으로 보지 않는다.
- contradicts_unreplaced_public: reaction만 판정하고 public은 false다. 이 reaction이 같은 용의자의 public 줄 가운데 replaces에 없는 줄과 어긋나는 사실(가지 않았다던 곳에 갔다, 잤다던 때 다른 일을 했다 등)을 말하는가. 어긋나는 줄을 거두지 않으면 용의자 프롬프트에 두 말이 함께 남는다.
- hidden_act_without_claim: reaction만 판정하고 public은 false다. 이 reaction이 그 용의자가 지금까지 숨겨 온 자기 행동(간 곳, 한 일, 만진 물건)을 털어놓는데, 그 행동을 한 때의 행적을 말하는 그 용의자의 public 줄이 하나도 없는가. 그런 줄이 없으면 단서를 찾기 전에 그때를 물을 때 용의자가 부인을 지어낸다.
- claim_names_breaking_clue: replaced_by가 있는 public만 판정하고 나머지는 false다. 이 줄이 breaking_clues가 가리키는 장소나 물건을 먼저 꺼내는가(예: 기름 얼룩으로 깨질 주장이 '역무실엔 안 갔어요'처럼 그 장소를 말함). public은 늘 프롬프트에 있어서, 묻지 않아도 그 장소나 물건을 먼저 꺼내게 된다. 그때 어디서 무엇을 했는지만 말하면 false다.
- reason: 한 문장 근거.

facts의 항목마다 suspect와 id를 받은 그대로 적어 하나도 빠뜨리지 말고, 모든 판정 칸을 적는다.

사건 전체로는 culprit을 보고 case에 판정한다.
- cover_story_broken: 범인의 reaction 가운데 적어도 하나가 단서가 드러낸 것을 인정하며, 범인의 public 주장에 없던 사실이나 그 주장과 맞지 않는 사실(가지 않았다던 곳에 갔다, 자기 것이 아니라던 물건이 자기 것이다 등)을 털어놓는가. 변명을 덧붙여도 그 사실을 인정하면 true다. 그래야 플레이어가 단서로 범인의 이야기에 난 틈을 잡을 수 있다.
- confession_matches_solution: confession은 범인이 다 들켰을 때 할 자백이다. 자백이 solution과 어긋나는 사실이나 solution에 없는 사건 사실을 말하지 않으면 true. confession이 null이면 true.
- reason: 한 문장 근거."""
SEMANTIC_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "facts": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "suspect": {"type": "string"},
                    "id": {"type": "string"},
                    "reveals_solution": {"type": "boolean"},
                    "line_differs_from_text": {"type": "boolean"},
                    "contradicts_unreplaced_public": {"type": "boolean"},
                    "hidden_act_without_claim": {"type": "boolean"},
                    "claim_names_breaking_clue": {"type": "boolean"},
                    "reason": {"type": "string"},
                },
                "required": [
                    "suspect", "id", "reveals_solution", "line_differs_from_text", "contradicts_unreplaced_public",
                    "hidden_act_without_claim", "claim_names_breaking_clue", "reason",
                ],
                "additionalProperties": False,
            },
        },
        "case": {
            "type": "object",
            "properties": {
                "cover_story_broken": {"type": "boolean"},
                "confession_matches_solution": {"type": "boolean"},
                "reason": {"type": "string"},
            },
            "required": ["cover_story_broken", "confession_matches_solution", "reason"],
            "additionalProperties": False,
        },
    },
    "required": ["facts", "case"],
    "additionalProperties": False,
}


def replaced_by(suspect: Suspect) -> dict[str, tuple[UnlockableFact, ...]]:
    """Each of [suspect]'s lines a reaction takes back, with the reactions that do, in their order: a claim the
    clues break (docs/voice-agent/before-found-denials.md)."""
    found: dict[str, tuple[UnlockableFact, ...]] = {}
    for fact in suspect.unlockable:
        for target in fact.replaces:
            found[target] = (*found.get(target, ()), fact)
    return found


def semantic_request(case: Case, suspects: Sequence[Suspect]) -> tuple[str, str]:
    """(system, user): the solution, the clues and every public fact and reaction with its line and answer key,
    and which reactions take which lines back."""
    culprit = case.member(case.solution.culprit)
    facts = []
    for suspect in suspects:
        takers = replaced_by(suspect)
        for fact in suspect.public:
            breaking = {clue_id for taker in takers.get(fact.id, ()) for clue_id in taker.unlocked_by}
            facts.append({
                "suspect": suspect.id, "id": fact.id, "kind": "public", "found_clues": [], "key": False,
                "text": fact.text, "line": fact.prompt_text,
                "replaced_by": [taker.id for taker in takers.get(fact.id, ())],
                "breaking_clues": [clue.title for clue in case.clues if clue.id in breaking],
            })
    for suspect in suspects:
        for fact in suspect.unlockable:
            facts.append({
                "suspect": suspect.id, "id": fact.id, "kind": "reaction",
                "found_clues": [clue.title for clue in case.clues if clue.id in fact.unlocked_by], "key": fact.key,
                "text": fact.text, "line": fact.prompt_text, "replaces": list(fact.replaces),
            })
    confession = next((suspect.confession for suspect in suspects if suspect.id == case.solution.culprit), None)
    user = {
        "solution": {"culprit": culprit.name, "method": case.solution.method, "motive": case.solution.motive,
                     "hiding_place": case.solution.hiding_place},
        "clues": [{"title": clue.title, "summary": clue.summary} for clue in case.clues],
        "facts": facts,
        "culprit": {"suspect": case.solution.culprit, "confession": confession},
    }
    return SEMANTIC_SYSTEM, json.dumps(user, ensure_ascii=False)


def semantic_problems(suspects: Sequence[Suspect], verdicts: Mapping[str, Any]) -> list[str]:
    """validate-style messages for the facts the model flagged and for any fact it did not judge."""
    expected = [(suspect.id, fact.id) for suspect in suspects for fact in (*suspect.public, *suspect.unlockable)]
    reactions = {(suspect.id, fact.id) for suspect in suspects for fact in suspect.unlockable}
    claims = {(suspect.id, fact_id) for suspect in suspects for fact_id in replaced_by(suspect)} - reactions
    judged = {(row.get("suspect"), row.get("id")): row for row in verdicts.get("facts", [])}
    problems = []
    for key in expected:
        row = judged.get(key)
        where = f"{key[0]}/{key[1]}"
        if row is None:
            problems.append(f"{where}: 의미 검사가 이 사실을 판정하지 않았습니다")
            continue
        reason = row.get("reason", "")
        if row.get("reveals_solution"):
            problems.append(f"{where}: 대사나 답지가 정답을 뜻으로 드러냅니다 ({reason})")
        if row.get("line_differs_from_text"):
            problems.append(f"{where}: 대사와 답지가 다른 사실을 말하거나 대사에만 있는 사실이 있습니다 ({reason})")
        if key in reactions and row.get("contradicts_unreplaced_public"):
            problems.append(f"{where}: 같은 사람의 공개 진술과 어긋나는데 그 줄을 replaces로 거두지 않습니다 ({reason})")
        if key in reactions and row.get("hidden_act_without_claim"):
            problems.append(
                f"{where}: 숨겨 온 자기 일을 털어놓는 반응인데, 그때의 행적을 말하며 덮는 주장이 공개 진술에 없습니다. "
                f"주장을 적고 이 반응의 replaces로 거두세요 ({reason})"
            )
        if key in claims and row.get("claim_names_breaking_clue"):
            problems.append(f"{where}: 단서로 깨질 주장이 그 단서의 장소나 물건을 먼저 꺼냅니다 ({reason})")
    whole = verdicts.get("case")
    if whole is None:
        problems.append("의미 검사가 사건 전체(범인의 겉 이야기와 자백)를 판정하지 않았습니다")
    else:
        if not whole.get("cover_story_broken"):
            problems.append(f"범인의 겉 이야기를 단서로 무너뜨리는 반응이 없습니다 ({whole.get('reason', '')})")
        if not whole.get("confession_matches_solution"):
            problems.append(f"범인의 자백 대사가 정답과 다른 사실이나 정답에 없는 사실을 말합니다 ({whole.get('reason', '')})")
    return problems


def check_semantics(case: Case, suspects: Sequence[Suspect], client: Any, model: str = SEMANTIC_MODEL) -> list[str]:
    """One structured call to [model]; the problems it finds, as semantic_problems words them."""
    system, user = semantic_request(case, suspects)
    verdicts = request_structured_json(
        client, model=model, effort=DEFAULT_JUDGE_EFFORT, system=system, prompt=user, schema=SEMANTIC_SCHEMA
    )
    return semantic_problems(suspects, verdicts)
