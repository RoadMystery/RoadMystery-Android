"""LLM-as-judge: does a suspect answer leak the solution, leak locked clues, or invent facts?"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Protocol

import anthropic

from voice_eval.content import Case, Probe, Suspect
from voice_eval.prompt import unlocked_facts
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
        "rationale": {"type": "string"},
    },
    "required": [
        "reveals_solution",
        "reveals_locked_clue",
        "invents_case_facts",
        "in_character",
        "answers_question",
        "rationale",
    ],
    "additionalProperties": False,
}

JUDGE_SYSTEM = (
    "너는 차량용 음성 추리 게임의 품질 검수자다. 용의자 NPC가 한 답변 하나를 아래 기준으로 "
    "엄격하게 채점한다. 판단은 주어진 자료만 근거로 한다."
)

_CRITERIA = """# 채점 기준
- reveals_solution: 범인이 누구인지, 수법, 동기, 숨긴 곳 중 하나라도 답변이 밝히거나 강하게 암시하면 true. 단순히 부인하거나 다른 사람을 의심하는 말은 false.
- reveals_locked_clue: 플레이어가 아직 찾지 않은 단서의 내용을 답변이 먼저 드러내면 true. 플레이어 질문에 이미 나온 말을 되받는 것은 false.
- invents_case_facts: '말해도 되는 사실'이나 찾은 단서와 어긋나거나, 거기 없는 사건 사실(시간, 장소, 물건, 목격 내용 등)을 새로 지어내면 true. 감정 표현, 일반적인 집안일 묘사, 모른다는 말은 지어낸 것으로 보지 않는다.
- in_character (1~5): 정해진 말투와 성격을 지키고, 자신이 AI나 게임 캐릭터라는 사실을 드러내지 않았는가.
- answers_question (1~5): 캐릭터와 비공개 원칙을 지키는 선에서 질문에 자연스럽게 반응했는가. 사건과 무관한 질문을 정중히 사건으로 돌린 것은 높게 준다.
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


class Judge(Protocol):
    def evaluate(
        self,
        case: Case,
        suspect: Suspect,
        probe: Probe,
        unlocked: frozenset[str],
        answer: str,
    ) -> JudgeVerdict: ...


def _bullets(lines: list[str]) -> str:
    return "\n".join(f"- {line}" for line in lines) if lines else "- 없음"


def build_judge_prompt(
    case: Case, suspect: Suspect, probe: Probe, unlocked: frozenset[str], answer: str
) -> str:
    culprit = case.member(case.solution.culprit)
    # Everything in the suspect's own prompt except the secrets: its profile and relationships
    # are part of the character, so repeating them is not inventing case facts.
    relationships = [
        f"{case.member(relationship.target).name}에 대해: {relationship.attitude}"
        for relationship in suspect.relationships
    ]
    allowed = [
        *case.public_facts,
        f"{suspect.name}의 이력: {suspect.age}세. {suspect.background}",
        *relationships,
        *(fact.text for fact in suspect.public),
        *(fact.text for fact in unlocked_facts(suspect, unlocked)),
    ]
    found = [f"{clue.title}: {clue.summary}" for clue in case.clues if clue.id in unlocked]
    hidden = [f"{clue.title}: {clue.summary}" for clue in case.clues if clue.id not in unlocked]
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
            + _bullets(list(suspect.personality)),
            "# 용의자가 말해도 되는 사실\n" + _bullets(allowed),
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
    ) -> JudgeVerdict:
        data = request_structured_json(
            self.client,
            model=self.model,
            effort=self.effort,
            system=JUDGE_SYSTEM,
            prompt=build_judge_prompt(case, suspect, probe, unlocked, answer),
            schema=JUDGE_SCHEMA,
        )
        return parse_verdict(data)
