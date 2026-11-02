import http.client
import json
import socket
import threading
import time
import urllib.error
import urllib.request
from collections.abc import Iterator
from types import SimpleNamespace
from typing import Self

import anthropic
import httpx2
import pytest

from voice_eval.providers import Candidate
from voice_eval.proxy import (
    HEALTH_PATH,
    RESPOND_PATH,
    ProxyConfig,
    ProxyEvent,
    RateLimiter,
    RelayOutcome,
    RequestError,
    RespondRequest,
    claude_upstream,
    format_sse,
    is_loopback,
    make_server,
    mock_upstream,
    parse_request,
    relay,
)

TOKEN = "dev-test-token-0123456789"
VALID = {
    "task": "interrogation",
    "system": "너는 집사다.",
    "messages": [{"role": "user", "content": "어디 있었어요?"}],
    "requestId": "abc",
}
PROFILE = {"interrogation": Candidate(id="h", model="claude-haiku-5-5", effort="low"),
           "summary": Candidate(id="h", model="claude-haiku-5-5", effort="low")}
REQUEST = httpx2.Request("POST", "https://api.anthropic.com/v1/messages")


def _body(**changes: object) -> bytes:
    return json.dumps({**VALID, **changes}).encode()


def test_valid_request_parses() -> None:
    request = parse_request(_body())
    assert request == RespondRequest("interrogation", "너는 집사다.",
                                     ({"role": "user", "content": "어디 있었어요?"},), "abc")


@pytest.mark.parametrize(
    "changes",
    [
        {"task": "free-chat"},
        {"system": ""},
        {"system": "x" * 16_001},
        {"messages": []},
        {"messages": [{"role": "assistant", "content": "먼저 말함"}]},
        {"messages": [{"role": "user", "content": "a"}, {"role": "assistant", "content": "b"}]},
        {"messages": [{"role": "user", "content": "x" * 1_001}]},
    ],
)
def test_requests_outside_the_contract_are_rejected(changes: dict) -> None:
    with pytest.raises(RequestError):
        parse_request(_body(**changes))


def test_non_json_is_rejected() -> None:
    with pytest.raises(RequestError):
        parse_request(b"not json")


def test_deeply_nested_json_is_a_request_error() -> None:
    with pytest.raises(RequestError):
        parse_request(b"[" * 30_000)


def test_request_id_keeps_only_log_safe_characters() -> None:
    assert parse_request(_body(requestId="app-1_2.x")).request_id == "app-1_2.x"
    assert parse_request(_body(requestId="x\nINFO forged\x1b[2J")).request_id == ""


@pytest.mark.parametrize("token", ["", "short-token", "토큰" * 10])
def test_tokens_must_be_long_and_ascii(token: str) -> None:
    with pytest.raises(ValueError):
        ProxyConfig(token, lambda request: iter(()))


@pytest.mark.parametrize(
    ("host", "expected"),
    [("127.0.0.1", True), ("localhost", True), ("::1", True), ("0.0.0.0", False), ("192.168.0.5", False)],
)
def test_loopback_hosts(host: str, expected: bool) -> None:
    assert is_loopback(host) is expected


def test_relay_stops_quietly_when_the_client_hangs_up() -> None:
    closed: list[bool] = []

    def events() -> Iterator[ProxyEvent]:
        try:
            yield ProxyEvent("delta", {"text": "저장고에"})
            yield ProxyEvent("delta", {"text": " 있었습니다."})
        finally:
            closed.append(True)

    def write(data: bytes) -> None:
        raise ConnectionAbortedError(10053, "what Windows raises after a normal close")

    assert relay(events(), write) == RelayOutcome(client_left=True, error_code=None)
    assert closed == [True]


def test_relay_reports_the_upstream_error_code() -> None:
    sent: list[bytes] = []
    events = iter([ProxyEvent("delta", {"text": "음"}),
                   ProxyEvent("error", {"code": "refusal", "message": "the model declined"})])
    assert relay(events, sent.append) == RelayOutcome(client_left=False, error_code="refusal")
    assert len(sent) == 2


def test_sse_frame_format() -> None:
    frame = format_sse(ProxyEvent("delta", {"text": "네."}))
    assert frame == 'event: delta\ndata: {"text": "네."}\n\n'.encode()


def test_rate_limiter_uses_a_sliding_window() -> None:
    now = [0.0]
    limiter = RateLimiter(limit=2, window_s=60.0, clock=lambda: now[0])
    assert limiter.allow("a") and limiter.allow("a")
    assert not limiter.allow("a")
    assert limiter.allow("b")
    now[0] = 61.0
    assert limiter.allow("a")


class FakeStream:
    def __init__(self, chunks: list[str], final: SimpleNamespace | None = None,
                 error: Exception | None = None) -> None:
        self._chunks, self._final, self._error = chunks, final, error

    def __enter__(self) -> Self:
        if self._error:
            raise self._error
        return self

    def __exit__(self, *exc: object) -> bool:
        return False

    @property
    def text_stream(self) -> Iterator[str]:
        yield from self._chunks

    def get_final_message(self) -> SimpleNamespace:
        return self._final


def _client(stream: FakeStream) -> SimpleNamespace:
    return SimpleNamespace(messages=SimpleNamespace(stream=lambda **params: stream))


def _final(stop_reason: str = "end_turn") -> SimpleNamespace:
    return SimpleNamespace(stop_reason=stop_reason, model="claude-haiku-5-5",
                           usage=SimpleNamespace(input_tokens=10, output_tokens=3))


def test_mock_upstream_streams_the_canned_answer_for_each_task() -> None:
    pauses: list[float] = []
    upstream = mock_upstream(
        {"interrogation": "저장고에 있었습니다. 정말입니다.", "summary": "단서는 장갑이에요."},
        first_delay_s=0.4,
        chunk_chars=4,
        chunk_delay_s=0.05,
        sleep=pauses.append,
    )
    request = parse_request(_body())

    events = list(upstream(request))

    assert "".join(e.data["text"] for e in events if e.name == "delta") == "저장고에 있었습니다. 정말입니다."
    assert all(len(e.data["text"]) <= 4 for e in events if e.name == "delta")
    assert events[-1].name == "done" and events[-1].data["model"] == "mock"
    assert pauses[0] == 0.4 and set(pauses[1:]) == {0.05}
    summary = list(upstream(parse_request(_body(task="summary"))))
    assert "".join(e.data["text"] for e in summary if e.name == "delta") == "단서는 장갑이에요."


def test_upstream_streams_deltas_then_done() -> None:
    upstream = claude_upstream(_client(FakeStream(["저장고에", " 있었습니다."], _final())), PROFILE)
    events = list(upstream(parse_request(_body())))
    assert [event.name for event in events] == ["delta", "delta", "done"]
    assert events[-1].data["outputTokens"] == 3


def test_refusal_becomes_an_error_event() -> None:
    upstream = claude_upstream(_client(FakeStream([], _final("refusal"))), PROFILE)
    events = list(upstream(parse_request(_body())))
    assert events[-1] == ProxyEvent("error", {"code": "refusal", "message": "the model declined"})


def test_upstream_rate_limit_becomes_an_error_event() -> None:
    error = anthropic.RateLimitError("느림", response=httpx2.Response(429, request=REQUEST), body=None)
    upstream = claude_upstream(_client(FakeStream([], error=error)), PROFILE)
    assert list(upstream(parse_request(_body())))[-1].data["code"] == "rate_limited"


def test_only_credential_errors_count_as_upstream_auth() -> None:
    missing = TypeError("Could not resolve authentication method. Expected either api_key or auth_token")
    other = TypeError("stream() got an unexpected keyword argument 'fallbacks'")
    for error, code in ((missing, "upstream_auth"), (other, "upstream_error")):
        upstream = claude_upstream(_client(FakeStream([], error=error)), PROFILE)
        assert list(upstream(parse_request(_body())))[-1].data["code"] == code


def test_a_stream_without_stop_reason_is_an_error() -> None:
    upstream = claude_upstream(_client(FakeStream(["저장고에"], _final(None))), PROFILE)
    events = list(upstream(parse_request(_body())))
    assert events[-1].name == "error" and events[-1].data["code"] == "upstream_error"


def test_answers_are_capped_well_below_the_benchmark_budget() -> None:
    seen: list[dict] = []

    def stream(**params: object) -> FakeStream:
        seen.append(params)
        return FakeStream(["네."], _final())

    list(claude_upstream(SimpleNamespace(messages=SimpleNamespace(stream=stream)), PROFILE)(parse_request(_body())))
    assert seen[0]["max_tokens"] <= 2048


@pytest.fixture
def server_url() -> Iterator[str]:
    def fake_upstream(request: RespondRequest) -> Iterator[ProxyEvent]:
        yield ProxyEvent("delta", {"text": "저장고에"})
        yield ProxyEvent("delta", {"text": " 있었습니다."})
        yield ProxyEvent("done", {"stopReason": "end_turn"})

    server = make_server("127.0.0.1", 0, ProxyConfig(TOKEN, fake_upstream, RateLimiter(limit=3)))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{server.server_address[1]}"
    server.shutdown()
    server.server_close()


def _post(url: str, body: bytes, token: str = TOKEN) -> tuple[int, str]:
    request = urllib.request.Request(
        url + RESPOND_PATH, data=body, method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8")


def test_server_streams_server_sent_events(server_url: str) -> None:
    status, text = _post(server_url, _body())
    assert status == 200
    assert text.count("event: delta") == 2 and "event: done" in text


def test_server_rejects_wrong_token_bad_body_and_floods(server_url: str) -> None:
    assert _post(server_url, _body(), token="wrong")[0] == 401
    assert _post(server_url, _body(task="free-chat"))[0] == 400
    statuses = [_post(server_url, _body())[0] for _ in range(4)]
    assert 429 in statuses


def test_health_endpoint(server_url: str) -> None:
    with urllib.request.urlopen(server_url + HEALTH_PATH, timeout=5) as response:
        assert json.loads(response.read()) == {"ok": True}


def _port(url: str) -> int:
    return int(url.rsplit(":", 1)[1])


def test_a_malformed_content_length_gets_400(server_url: str) -> None:
    connection = http.client.HTTPConnection("127.0.0.1", _port(server_url), timeout=5)
    connection.putrequest("POST", RESPOND_PATH, skip_accept_encoding=True)
    connection.putheader("Authorization", f"Bearer {TOKEN}")
    connection.putheader("Content-Length", "abc")
    connection.endheaders()
    assert connection.getresponse().status == 400
    connection.close()


def test_the_largest_request_the_contract_allows_is_accepted(server_url: str) -> None:
    messages = [{"role": "user" if index % 2 == 0 else "assistant", "content": "나" * 1_000}
                for index in range(21)]
    body = _body(system="가" * 16_000, messages=messages)
    assert len(body) > 64_000
    assert _post(server_url, body)[0] == 200


def test_a_second_server_cannot_take_a_port_in_use(server_url: str) -> None:
    with pytest.raises(OSError):
        make_server("127.0.0.1", _port(server_url), ProxyConfig(TOKEN, lambda request: iter(())))


def test_idle_connections_are_closed() -> None:
    config = ProxyConfig(TOKEN, lambda request: iter(()), socket_timeout_s=0.3)
    server = make_server("127.0.0.1", 0, config)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        with socket.create_connection(("127.0.0.1", server.server_address[1]), timeout=5) as idle:
            started = time.monotonic()
            assert idle.recv(1) == b""
            assert time.monotonic() - started < 3
    finally:
        server.shutdown()
        server.server_close()
