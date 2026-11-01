import json
from collections.abc import Iterator
from pathlib import Path
from types import SimpleNamespace
from typing import Self

import anthropic
import httpx2
import pytest

from voice_eval.metrics import Timing
from voice_eval.providers import (
    FALLBACK_BETA,
    AnthropicProvider,
    Candidate,
    MissingCredentialsError,
    ScriptedProvider,
    StreamResult,
    load_candidates,
)

CANDIDATES_PATH = Path(__file__).resolve().parents[1] / "candidates.json"
REQUEST = httpx2.Request("POST", "https://api.anthropic.com/v1/messages")
USAGE = SimpleNamespace(input_tokens=1200, output_tokens=40)
FINAL = SimpleNamespace(
    stop_reason="end_turn",
    model="claude-haiku-5-5",
    usage=USAGE,
    content=[SimpleNamespace(type="text", text="지하 저장고에 있었습니다.")],
)
HAIKU = Candidate(id="haiku", model="claude-haiku-5-5", effort="low")
SYSTEM = "시스템 프롬프트"
MESSAGES = [{"role": "user", "content": "어디 계셨어요?"}]


def _status_error(cls: type[anthropic.APIStatusError], status: int) -> anthropic.APIStatusError:
    return cls("테스트 오류", response=httpx2.Response(status, request=REQUEST), body=None)


class FakeStream:
    def __init__(
        self,
        chunks: list[str],
        error: Exception | None = None,
        final: SimpleNamespace = FINAL,
        body_error: Exception | None = None,
    ) -> None:
        self._chunks = chunks
        self._error = error
        self._final = final
        self._body_error = body_error

    def __enter__(self) -> Self:
        if self._error is not None:
            raise self._error
        return self

    def __exit__(self, *exc_info: object) -> bool:
        return False

    @property
    def text_stream(self) -> Iterator[str]:
        yield from self._chunks
        if self._body_error is not None:
            raise self._body_error

    def get_final_message(self) -> SimpleNamespace:
        return self._final


class FakeMessages:
    def __init__(self, stream: FakeStream) -> None:
        self._stream = stream
        self.calls: list[dict] = []

    def stream(self, **params: object) -> FakeStream:
        self.calls.append(params)
        return self._stream


class FakeClient:
    def __init__(self, stream: FakeStream) -> None:
        self.messages = FakeMessages(stream)
        self.beta = SimpleNamespace(messages=FakeMessages(stream))


def _clock(times: list[float]):
    values = iter(times)
    return lambda: next(values)


def _write(tmp_path: Path, data: list[dict]) -> Path:
    path = tmp_path / "candidates.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    return path


def test_request_params_set_effort_and_optional_thinking() -> None:
    plain = HAIKU.request_params(SYSTEM, MESSAGES)
    assert plain["output_config"] == {"effort": "low"}
    assert "thinking" not in plain
    assert "temperature" not in plain

    no_think = Candidate(id="x", model="m", effort="low", thinking={"type": "disabled"})
    assert no_think.request_params(SYSTEM, MESSAGES)["thinking"] == {"type": "disabled"}


def test_repo_candidates_load() -> None:
    candidates = load_candidates(CANDIDATES_PATH)
    assert len(candidates) == 5
    assert len({candidate.id for candidate in candidates}) == 5


def test_duplicate_candidate_ids_are_rejected(tmp_path: Path) -> None:
    item = {"id": "a", "model": "claude-haiku-5-5", "effort": "low"}
    with pytest.raises(ValueError, match="unique"):
        load_candidates(_write(tmp_path, [item, item]))


def test_between_tools_with_fallbacks_is_rejected(tmp_path: Path) -> None:
    item = {
        "id": "a",
        "model": "claude-sonnet-5-5",
        "effort": "low",
        "thinking": {"type": "between_tools"},
        "fallbacks": "default",
    }
    with pytest.raises(ValueError, match="between_tools"):
        load_candidates(_write(tmp_path, [item]))


def test_stream_answer_times_chunks_and_reads_usage() -> None:
    client = FakeClient(FakeStream(["지하 저장고에", " 있었습니다."]))
    provider = AnthropicProvider(client=client, clock=_clock([0.0, 0.3, 0.5, 0.6]))

    result = provider.stream_answer(HAIKU, SYSTEM, MESSAGES)

    assert result.error is None
    assert result.text == "지하 저장고에 있었습니다."
    # A sentence that ends the stream is confirmed when the stream ends.
    assert result.timing == Timing(ttft_ms=pytest.approx(300), ttfs_ms=pytest.approx(600),
                                   total_ms=pytest.approx(600))
    assert (result.served_by, result.input_tokens, result.output_tokens) == (
        "claude-haiku-5-5",
        1200,
        40,
    )
    assert client.messages.calls[0]["system"] == SYSTEM
    assert client.beta.messages.calls == []


def test_fallback_candidate_uses_beta_stream() -> None:
    client = FakeClient(FakeStream(["네."]))
    provider = AnthropicProvider(client=client, clock=_clock([0.0, 0.1, 0.2]))
    candidate = Candidate(id="s", model="claude-sonnet-5-5", effort="low", fallbacks="default")

    provider.stream_answer(candidate, SYSTEM, MESSAGES)

    call = client.beta.messages.calls[0]
    assert call["betas"] == [FALLBACK_BETA]
    assert call["fallbacks"] == "default"
    assert client.messages.calls == []


@pytest.mark.parametrize(
    ("error", "label"),
    [
        (_status_error(anthropic.RateLimitError, 429), "RateLimitError"),
        (_status_error(anthropic.NotFoundError, 404), "NotFoundError"),
        (_status_error(anthropic.BadRequestError, 400), "BadRequestError"),
        (_status_error(anthropic.InternalServerError, 500), "APIStatusError 500"),
        (anthropic.APIConnectionError(request=REQUEST), "APIConnectionError"),
    ],
)
def test_request_errors_become_failed_results(error: Exception, label: str) -> None:
    provider = AnthropicProvider(
        client=FakeClient(FakeStream([], error=error)), clock=_clock([0.0, 0.25])
    )
    result = provider.stream_answer(HAIKU, SYSTEM, MESSAGES)

    assert result.error is not None and result.error.startswith(label)
    assert result.text == ""
    assert result.timing.total_ms == pytest.approx(250)


def test_authentication_error_propagates() -> None:
    error = _status_error(anthropic.AuthenticationError, 401)
    provider = AnthropicProvider(client=FakeClient(FakeStream([], error=error)))
    with pytest.raises(anthropic.AuthenticationError):
        provider.stream_answer(HAIKU, SYSTEM, MESSAGES)


def test_missing_credentials_raise_missing_credentials_error() -> None:
    error = TypeError("Could not resolve authentication method. Expected one of api_key")
    provider = AnthropicProvider(client=FakeClient(FakeStream([], error=error)))
    with pytest.raises(MissingCredentialsError):
        provider.stream_answer(HAIKU, SYSTEM, MESSAGES)


def test_sdk_credentials_error_propagates() -> None:
    error = anthropic.CredentialsError("로그인 프로필 없음")
    provider = AnthropicProvider(client=FakeClient(FakeStream([], error=error)))
    with pytest.raises(anthropic.CredentialsError):
        provider.stream_answer(HAIKU, SYSTEM, MESSAGES)


def test_default_client_never_retries_inside_the_timed_region() -> None:
    client = AnthropicProvider().client
    assert client.max_retries == 0


def test_transport_error_while_reading_the_body_is_recorded() -> None:
    stream = FakeStream(["지하 저장고"], body_error=httpx2.ReadError("connection reset"))
    provider = AnthropicProvider(client=FakeClient(stream), clock=_clock([0.0, 0.1, 0.3]))

    result = provider.stream_answer(HAIKU, SYSTEM, MESSAGES)

    assert result.error == "ReadError: connection reset"
    assert result.timing.total_ms == pytest.approx(300)


def test_stream_without_stop_reason_is_an_error() -> None:
    final = SimpleNamespace(stop_reason=None, model="claude-haiku-5-5", usage=USAGE, content=[])
    provider = AnthropicProvider(
        client=FakeClient(FakeStream(["지하"], final=final)), clock=_clock([0.0, 0.1, 0.2])
    )
    result = provider.stream_answer(HAIKU, SYSTEM, MESSAGES)
    assert result.error == "stream ended without stop_reason"


def test_fallback_answer_keeps_only_the_fallback_models_text() -> None:
    final = SimpleNamespace(
        stop_reason="end_turn",
        model="claude-opus-4-8",
        usage=USAGE,
        content=[
            SimpleNamespace(type="text", text="거절된 모델이 쓰던"),
            SimpleNamespace(type="fallback"),
            SimpleNamespace(type="text", text="저장고에 있었습니다."),
        ],
    )
    stream = FakeStream(["거절된 모델이 쓰던", "저장고에 있었습니다."], final=final)
    provider = AnthropicProvider(client=FakeClient(stream), clock=_clock([0.0, 0.1, 0.2, 0.3]))
    candidate = Candidate(id="s", model="claude-sonnet-5-5", effort="low", fallbacks="default")

    result = provider.stream_answer(candidate, SYSTEM, MESSAGES)

    assert result.text == "저장고에 있었습니다."
    assert result.fell_back is True
    assert result.served_by == "claude-opus-4-8"


def test_unrelated_type_error_is_not_masked() -> None:
    provider = AnthropicProvider(client=FakeClient(FakeStream([], error=TypeError("버그"))))
    with pytest.raises(TypeError, match="버그"):
        provider.stream_answer(HAIKU, SYSTEM, MESSAGES)


def test_cost_uses_served_model_price() -> None:
    timing = Timing(None, None, 0.0)
    haiku = StreamResult(
        text="", timing=timing, served_by="claude-haiku-5-5",
        input_tokens=1_000_000, output_tokens=1_000_000,
    )
    assert haiku.cost_usd == pytest.approx(0.60)
    unknown = StreamResult(text="", timing=timing, served_by="other", input_tokens=1, output_tokens=1)
    assert unknown.cost_usd is None


def test_scripted_provider_streams_answer_in_chunks() -> None:
    provider = ScriptedProvider(lambda *_: "네. 그렇습니다.", ms_per_chunk=10.0, chunk_chars=2)
    result = provider.stream_answer(HAIKU, SYSTEM, MESSAGES)

    assert result.text == "네. 그렇습니다."
    assert result.timing.ttft_ms == pytest.approx(10)
    # "네." is a sentence once the next chunk (" 그") shows it did not continue.
    assert result.timing.ttfs_ms == pytest.approx(20)
    assert result.served_by == HAIKU.model
