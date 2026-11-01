import json
from types import SimpleNamespace

import anthropic
import httpx2
import pytest

from voice_eval.content import Case, Probe, Suspect
from voice_eval.judge import (
    JUDGE_SCHEMA,
    ClaudeJudge,
    JudgeError,
    build_judge_prompt,
    parse_verdict,
)
from voice_eval.providers import MissingCredentialsError

REQUEST = httpx2.Request("POST", "https://api.anthropic.com/v1/messages")
VALID = {
    "reveals_solution": False,
    "reveals_locked_clue": False,
    "invents_case_facts": True,
    "in_character": 5,
    "answers_question": 4,
    "rationale": "말투는 지켰지만 없는 목격담을 지어냄",
}


class FakeMessages:
    def __init__(self, response: object = None, error: Exception | None = None) -> None:
        self._response = response
        self._error = error
        self.calls: list[dict] = []

    def create(self, **params: object) -> object:
        self.calls.append(params)
        if self._error is not None:
            raise self._error
        return self._response


def _response(text: str | None, stop_reason: str = "end_turn") -> SimpleNamespace:
    content = [SimpleNamespace(type="text", text=text)] if text is not None else []
    return SimpleNamespace(stop_reason=stop_reason, content=content)


def _judge(messages: FakeMessages) -> ClaudeJudge:
    return ClaudeJudge(client=SimpleNamespace(messages=messages))


def _probe(probes: tuple[Probe, ...], probe_id: str) -> Probe:
    return next(probe for probe in probes if probe.id == probe_id)


def _section(prompt: str, header: str) -> str:
    start = prompt.index(header)
    end = prompt.find("\n# ", start + len(header))
    return prompt[start : end if end != -1 else None]


def test_prompt_separates_found_and_hidden_clues(
    case: Case, butler: Suspect, probes: tuple[Probe, ...]
) -> None:
    probe = _probe(probes, "fusebox-reaction")
    prompt = build_judge_prompt(case, butler, probe, frozenset(probe.unlocked), "모릅니다.")

    found = _section(prompt, "# 플레이어가 찾은 단서")
    hidden = _section(prompt, "# 플레이어가 아직 찾지 않은 단서")
    assert "지하실 두꺼비집" in found and "지하실 두꺼비집" not in hidden
    assert "젖은 흰 장갑" in hidden and "젖은 흰 장갑" not in found
    assert case.solution.hiding_place in prompt
    assert f"플레이어: {probe.question}" in prompt
    assert prompt.rstrip().endswith("rationale: 한두 문장으로 근거.")


def test_prompt_replays_conversation_history(
    case: Case, butler: Suspect, probes: tuple[Probe, ...]
) -> None:
    probe = _probe(probes, "multi-turn-contradiction")
    prompt = build_judge_prompt(case, butler, probe, frozenset(probe.unlocked), "그건...")
    assert f"용의자: {probe.history[1]['content']}" in prompt
    assert "용의자: 그건..." in prompt


def test_parse_verdict_accepts_valid_payload() -> None:
    verdict = parse_verdict(VALID)
    assert verdict.invents_case_facts is True
    assert verdict.in_character == 5


@pytest.mark.parametrize(
    "payload",
    [
        {**VALID, "in_character": 0},
        {**VALID, "answers_question": 6},
        {"rationale": "빠짐"},
        {**VALID, "reveals_solution": "false"},
        {**VALID, "in_character": 4.9},
        {**VALID, "answers_question": True},
    ],
)
def test_parse_verdict_rejects_bad_payload(payload: dict) -> None:
    with pytest.raises(JudgeError):
        parse_verdict(payload)


def test_judge_requests_structured_output_and_parses_it(
    case: Case, butler: Suspect, probes: tuple[Probe, ...]
) -> None:
    messages = FakeMessages(_response(json.dumps(VALID, ensure_ascii=False)))
    verdict = _judge(messages).evaluate(case, butler, probes[0], frozenset(), "저장고에 있었습니다.")

    assert verdict == parse_verdict(VALID)
    output_config = messages.calls[0]["output_config"]
    assert output_config["format"] == {"type": "json_schema", "schema": JUDGE_SCHEMA}
    assert output_config["effort"] == "medium"


@pytest.mark.parametrize(
    ("response", "match"),
    [
        (_response(None, stop_reason="refusal"), "refused"),
        (_response(None), "no text block"),
        (_response("{not json"), "invalid JSON"),
    ],
)
def test_unusable_judge_responses_raise(
    case: Case, butler: Suspect, probes: tuple[Probe, ...], response: object, match: str
) -> None:
    with pytest.raises(JudgeError, match=match):
        _judge(FakeMessages(response)).evaluate(case, butler, probes[0], frozenset(), "네.")


def test_api_errors_become_judge_errors(
    case: Case, butler: Suspect, probes: tuple[Probe, ...]
) -> None:
    error = anthropic.RateLimitError(
        "느려요", response=httpx2.Response(429, request=REQUEST), body=None
    )
    with pytest.raises(JudgeError, match="RateLimitError"):
        _judge(FakeMessages(error=error)).evaluate(case, butler, probes[0], frozenset(), "네.")


def test_auth_and_credential_errors_propagate(
    case: Case, butler: Suspect, probes: tuple[Probe, ...]
) -> None:
    auth = anthropic.AuthenticationError(
        "키 오류", response=httpx2.Response(401, request=REQUEST), body=None
    )
    with pytest.raises(anthropic.AuthenticationError):
        _judge(FakeMessages(error=auth)).evaluate(case, butler, probes[0], frozenset(), "네.")

    missing = TypeError("Could not resolve authentication method.")
    with pytest.raises(MissingCredentialsError):
        _judge(FakeMessages(error=missing)).evaluate(case, butler, probes[0], frozenset(), "네.")
