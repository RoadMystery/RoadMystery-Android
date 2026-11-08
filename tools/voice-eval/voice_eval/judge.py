"""LLM-as-judge: does a suspect answer leak the solution, leak locked clues, or invent facts?"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Protocol

import anthropic

from voice_eval.content import Case, Probe, Suspect
from voice_eval.prompt import allowed_facts, pressure_line, taken_back
from voice_eval.providers import raise_if_missing_credentials

DEFAULT_JUDGE_MODEL = "claude-opus-5-5"
DEFAULT_JUDGE_EFFORT = "medium"
_SCORE_RANGE = range(1, 6)

JUDGE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "reveals_solution": {"type": "boolean"},
        "reveals_locked_clue": {"type": "boolean"},
        "invents_case_facts": {"type": "boolean"},
        "in_character": {"type": "integer"},
        "answers_question": {"type": "integer"},
        "confesses": {"type": "boolean"},
        "rationale": {"type": "string"},
    },
    "required": [
        "reveals_solution",
        "reveals_locked_clue",
        "invents_case_facts",
        "in_character",
        "answers_question",
        "confesses",
        "rationale",
    ],
    "additionalProperties": False,
}

JUDGE_SYSTEM = (
    "너는 차량용 음성 추리 게임의 품질 검수자다. 용의자 NPC가 한 답변 하나를 아래 기준으로 "
    "엄격하게 채점한다. 판단은 주어진 자료만 근거로 한다."
)

_CRITERIA = """# 채점 기준
- reveals_solution: 범인이 누구인지, 수법, 동기, 숨긴 곳 중 하나라도 답변이 밝히거나 강하게 암시하면 true. 범인인 용의자가 자기 잘못이나 숨기는 일이 있다는 것을 말로 인정해도 true. 단, '자백할 수 있는 턴'이 '예'일 때 범인이 자백한 것은 false. 단순히 부인하거나 다른 사람을 의심하는 말은 false.
- reveals_locked_clue: 플레이어가 아직 찾지 않은 단서의 내용을 답변이 먼저 드러내면 true. 플레이어 질문에 이미 나온 말을 되받는 것은 false.
- invents_case_facts: '말해도 되는 사실'이나 찾은 단서와 어긋나거나, 거기 없는 사건 사실(시간, 장소, 사람이 한 일, 물건의 주인과 행방, 목격 내용 등)을 새로 지어내면 true. 남의 알리바이를 대신 대주는 말도 사건 사실이다. 감정 표현, 의견, 일반적인 집안일 묘사, 사건과 상관없는 일상, 모른다는 말은 지어낸 것으로 보지 않는다. 부인과 한정도 사건 사실이다. 자기가 어디에 가지 않았다, 무엇을 하지 않았다, 그런 일이 없다, 무엇만 했다, 그것밖에 없다처럼 자기 행동을 부정하거나 좁히는 말은 '말해도 되는 사실'이 이미 그렇게 말하고 있을 때만 괜찮다. 사실이 그 시간 내내 어디서 무엇을 했는지 말하면 그 밖의 곳에 가지 않았다는 말은 괜찮지만, 사실이 그 시간의 일부만 말하거나 덜 좁게 말하는데 나머지를 부정하거나 더 좁혀 말하면 지어낸 것이다. 찾은 단서에 대한 반응에서 인정한 일과 어긋나는 말은 그 일을 직접 부인하지 않고 '일만 했다', '거기에만 있었다', '그런 일 없다'처럼 빼 버려도 true다. 되묻거나 반어로 한 부인도 뜻으로 판단한다. 범행 자체(훔쳤다, 범인이다)를 부인하는 말과 기억이 안 난다는 말은 지어낸 것이 아니다. 남이 한 일이나 플레이어가 아직 찾지 않은 증거 쪽 이야기에 못 봤다, 모르겠다, 처음 듣는다고 하는 말은 지어낸 것이 아니다. 말해도 되는 사실에 있는 진술이 그 시간 자기가 어디 있었는지 말하면, 그 진술과 같은 뜻의 부인(거기 말고 다른 데는 가지 않았다, 그때 아무도 만나지 않았다)은 지어낸 것이 아니다.
- in_character (1~5): 정해진 말투와 성격을 지키고, 자신이 AI나 게임 캐릭터라는 사실을 드러내지 않았는가.
- answers_question (1~5): 캐릭터와 비공개 원칙을 지키는 선에서 질문에 자연스럽게 반응했는가. 사건과 무관한 질문을 정중히 사건으로 돌린 것은 높게 준다.
- confesses: 범인인 용의자가 답변에서 자기가 범인이라고 털어놓으면 true. 범인이 아니거나 털어놓지 않으면 false.
- rationale: 한두 문장으로 근거."""


class JudgeError(RuntimeError):
    """The judge could not produce a usable verdict."""


@dataclass(frozen=True)
class JudgeVerdict:
    reveals_solution: bool
    reveals_locked_clue: bool
    invents_case_facts: bool
    in_character: int
    answers_question: int
    rationale: str
    confesses: bool = False
    """The culprit owned up; expected only where confession_opens (docs/voice-agent/game-rules.md)."""


class Judge(Protocol):
    def evaluate(
        self,
        case: Case,
        suspect: Suspect,
        probe: Probe,
        unlocked: frozenset[str],
        answer: str,
        *,
        confession: bool = False,
    ) -> JudgeVerdict: ...


def _bullets(lines: list[str]) -> str:
    return "\n".join(f"- {line}" for line in lines) if lines else "- 없음"


def build_judge_prompt(
    case: Case, suspect: Suspect, probe: Probe, unlocked: frozenset[str], answer: str, *, confession: bool = False
) -> str:
    """[confession]: the culprit's prompt had its confession this turn (prompt.confession_opens)."""
    culprit = case.member(case.solution.culprit)
    # What the suspect may state without inventing case facts: the case overview, its profile, stance,
    # relationships and belongings (part of the character) and the answer keys of its public facts and
    # unlocked reactions, not the first-person lines its prompt shows in their place; then the stage's
    # pressure line, which the prompt of a probe asked late in the investigation also carries, and the
    # confession once the culprit may make it.
    allowed = list(allowed_facts(case, suspect, unlocked))
    pressure = pressure_line(suspect, probe.stage)
    if pressure:
        allowed.append(pressure)
    if confession and suspect.confession:
        allowed.append(f"자백할 때 할 말: {suspect.confession}")
    is_culprit = suspect.id == case.solution.culprit
    found = [f"{clue.title}: {clue.summary}" for clue in case.clues if clue.id in unlocked]
    hidden = [f"{clue.title}: {clue.summary}" for clue in case.clues if clue.id not in unlocked]
    # A claim a found reaction took back is shown apart, so saying it again counts against the reaction.
    retracted = taken_back(suspect, unlocked)
    speaker = {"user": "플레이어", "assistant": "용의자"}
    dialogue = [f"{speaker[turn['role']]}: {turn['content']}" for turn in probe.history]
    dialogue += [f"플레이어: {probe.question}", f"용의자: {answer}"]

    return "\n\n".join(
        [
            "# 사건 정답 (플레이어는 아직 모름)\n"
            + _bullets(
                [
                    f"범인: {culprit.name}({culprit.role})",
                    f"수법: {case.solution.method}",
                    f"동기: {case.solution.motive}",
                    f"숨긴 곳: {case.solution.hiding_place}",
                ]
            ),
            f"# 답변한 용의자\n- {suspect.name}({suspect.role}), 말투: {suspect.register}\n"
            f"- 범인 여부: {'범인' if is_culprit else '범인 아님'}\n"
            f"- 자백할 수 있는 턴: {'예' if confession else '아니오'}\n"
            + _bullets(list(suspect.personality)),
            "# 용의자가 말해도 되는 사실\n" + _bullets(allowed),
            *(
                ["# 단서를 찾아 거둔 예전 주장 (지금 다시 하면 찾은 단서에 대한 반응과 어긋나는 지어냄)\n" + _bullets(retracted)]
                if retracted
                else []
            ),
            "# 플레이어가 찾은 단서\n" + _bullets(found),
            "# 플레이어가 아직 찾지 않은 단서 (용의자가 먼저 드러내면 안 됨)\n" + _bullets(hidden),
            "# 대화\n" + "\n".join(dialogue),
            _CRITERIA,
        ]
    )


def require_bool(data: Any, key: str) -> bool:
    value = data[key]
    if not isinstance(value, bool):  # bool("false") would be True
        raise JudgeError(f"'{key}' is not a boolean: {data!r}")
    return value


def require_score(data: Any, key: str) -> int:
    value = data[key]
    if isinstance(value, bool) or not isinstance(value, int) or value not in _SCORE_RANGE:
        raise JudgeError(f"'{key}' is not an integer 1-5: {data!r}")
    return value


def parse_verdict(data: Any) -> JudgeVerdict:
    try:
        return JudgeVerdict(
            reveals_solution=require_bool(data, "reveals_solution"),
            reveals_locked_clue=require_bool(data, "reveals_locked_clue"),
            invents_case_facts=require_bool(data, "invents_case_facts"),
            in_character=require_score(data, "in_character"),
            answers_question=require_score(data, "answers_question"),
            rationale=str(data["rationale"]),
            confesses=require_bool(data, "confesses"),
        )
    except (KeyError, TypeError) as exc:
        raise JudgeError(f"malformed verdict: {data!r}") from exc


def request_structured_json(
    client: anthropic.Anthropic,
    *,
    model: str,
    effort: str,
    system: str,
    prompt: str,
    schema: dict[str, Any],
) -> Any:
    """One structured-output call shared by every judge; failures become JudgeError."""
    try:
        response = client.messages.create(
            model=model,
            max_tokens=16000,
            system=system,
            messages=[{"role": "user", "content": prompt}],
            output_config={"effort": effort, "format": {"type": "json_schema", "schema": schema}},
        )
    except (
        anthropic.AuthenticationError,
        anthropic.PermissionDeniedError,
        anthropic.CredentialsError,
    ):
        raise
    except TypeError as exc:
        raise_if_missing_credentials(exc)
        raise
    except anthropic.APIError as exc:
        raise JudgeError(f"{type(exc).__name__}: {exc}") from exc

    if response.stop_reason == "refusal":
        raise JudgeError("judge refused")
    text = next((block.text for block in response.content if block.type == "text"), None)
    if text is None:
        raise JudgeError(f"no text block (stop_reason={response.stop_reason})")
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise JudgeError(f"invalid JSON: {text[:200]}") from exc


@dataclass
class ClaudeJudge:
    client: anthropic.Anthropic = field(default_factory=anthropic.Anthropic)
    model: str = DEFAULT_JUDGE_MODEL
    effort: str = DEFAULT_JUDGE_EFFORT

    def evaluate(
        self,
        case: Case,
        suspect: Suspect,
        probe: Probe,
        unlocked: frozenset[str],
        answer: str,
        *,
        confession: bool = False,
    ) -> JudgeVerdict:
        data = request_structured_json(
            self.client,
            model=self.model,
            effort=self.effort,
            system=JUDGE_SYSTEM,
            prompt=build_judge_prompt(case, suspect, probe, unlocked, answer, confession=confession),
            schema=JUDGE_SCHEMA,
        )
        return parse_verdict(data)
