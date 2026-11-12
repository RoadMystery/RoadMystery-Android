"""Development stand-in for the VoiceAgent LLM proxy (the production server is 신준호's).

Implements docs/voice-agent/llm-proxy-contract.md so the VoiceAgent on the emulator can reach
Claude without an API key in the app. It binds to localhost only; the emulator reaches the host
at http://10.0.2.2:<port>. Prompt and answer text are never logged.
"""

from __future__ import annotations

import hmac
import ipaddress
import json
import logging
import re
import sys
import threading
import time
from collections import deque
from collections.abc import Callable, Iterator, Mapping
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

import anthropic

from voice_eval.providers import Candidate, is_missing_credentials

logger = logging.getLogger(__name__)

RESPOND_PATH = "/v1/voice-agent/respond"
HEALTH_PATH = "/healthz"
TASKS = ("interrogation", "summary")
MAX_SYSTEM_CHARS = 16_000
MAX_MESSAGE_CHARS = 1_000
MAX_MESSAGES = 21
MAX_BODY_BYTES = 256_000
"""The contract's largest request is about 111 KB of UTF-8, or 223 KB with \\u escapes."""
MAX_ANSWER_TOKENS = 2_048
"""Spoken answers need a few hundred tokens; the cap bounds what one request can spend."""
MIN_TOKEN_CHARS = 16
SOCKET_TIMEOUT_S = 10.0
RATE_WINDOW_S = 60.0
_REQUEST_ID = re.compile(r"[A-Za-z0-9._-]{1,64}")


class RequestError(ValueError):
    """The request body breaks the contract; answered with HTTP 400."""


@dataclass(frozen=True)
class RespondRequest:
    task: str
    system: str
    messages: tuple[dict[str, str], ...]
    request_id: str


@dataclass(frozen=True)
class ProxyEvent:
    name: str
    """'delta', 'done', or 'error'."""
    data: Mapping[str, Any]


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise RequestError(message)


def _parse_messages(raw: Any) -> tuple[dict[str, str], ...]:
    _require(isinstance(raw, list) and 1 <= len(raw) <= MAX_MESSAGES, "messages: 1-21 items")
    messages = []
    for index, message in enumerate(raw):
        expected = "user" if index % 2 == 0 else "assistant"
        _require(isinstance(message, dict) and message.get("role") == expected,
                 f"messages[{index}].role must be '{expected}'")
        content = message.get("content")
        _require(isinstance(content, str) and bool(content.strip())
                 and len(content) <= MAX_MESSAGE_CHARS,
                 f"messages[{index}].content must be 1-{MAX_MESSAGE_CHARS} characters")
        messages.append({"role": expected, "content": content})
    _require(messages[-1]["role"] == "user", "the last message must be from the user")
    return tuple(messages)


def parse_request(body: bytes) -> RespondRequest:
    try:
        data = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as exc:
        raise RequestError(f"body is not UTF-8 JSON ({type(exc).__name__})") from exc
    _require(isinstance(data, dict), "body must be a JSON object")
    task = data.get("task")
    _require(task in TASKS, f"task must be one of {', '.join(TASKS)}")
    system = data.get("system")
    _require(isinstance(system, str) and bool(system.strip()) and len(system) <= MAX_SYSTEM_CHARS,
             f"system must be 1-{MAX_SYSTEM_CHARS} characters")
    request_id = data.get("requestId")
    return RespondRequest(
        task=task,
        system=system,
        messages=_parse_messages(data.get("messages")),
        # Logged, so anything that could forge or color a log line is dropped.
        request_id=request_id if isinstance(request_id, str) and _REQUEST_ID.fullmatch(request_id) else "",
    )


def format_sse(event: ProxyEvent) -> bytes:
    payload = json.dumps(dict(event.data), ensure_ascii=False)
    return f"event: {event.name}\ndata: {payload}\n\n".encode()


class RateLimiter:
    """Sliding-window request limit per token."""

    def __init__(self, limit: int, window_s: float = RATE_WINDOW_S,
                 clock: Callable[[], float] = time.monotonic) -> None:
        self._limit = limit
        self._window = window_s
        self._clock = clock
        self._hits: dict[str, deque[float]] = {}
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        now = self._clock()
        with self._lock:
            hits = self._hits.setdefault(key, deque())
            while hits and now - hits[0] >= self._window:
                hits.popleft()
            if len(hits) >= self._limit:
                return False
            hits.append(now)
            return True


Upstream = Callable[[RespondRequest], Iterator[ProxyEvent]]


def _error(code: str, message: str) -> ProxyEvent:
    return ProxyEvent("error", {"code": code, "message": message})


def claude_upstream(client: anthropic.Anthropic, profiles: Mapping[str, Candidate]) -> Upstream:
    """Streams Claude text as delta events. Server-side fallbacks are not used here: text from a
    declined model would already be on its way to the car, so a refusal becomes an error event."""

    def stream(request: RespondRequest) -> Iterator[ProxyEvent]:
        params = profiles[request.task].request_params(request.system, list(request.messages))
        params["max_tokens"] = min(params["max_tokens"], MAX_ANSWER_TOKENS)
        try:
            with client.messages.stream(**params) as upstream:
                for text in upstream.text_stream:
                    if text:
                        yield ProxyEvent("delta", {"text": text})
                final = upstream.get_final_message()
        except (anthropic.AuthenticationError, anthropic.PermissionDeniedError,
                anthropic.CredentialsError) as exc:
            yield _error("upstream_auth", type(exc).__name__)
            return
        except TypeError as exc:
            # The SDK reports missing credentials as a TypeError, but API misuse raises one too.
            yield _error("upstream_auth" if is_missing_credentials(exc) else "upstream_error",
                         type(exc).__name__)
            return
        except anthropic.RateLimitError:
            yield _error("rate_limited", "upstream rate limit")
            return
        except anthropic.APIStatusError as exc:
            yield _error("upstream_error", f"HTTP {exc.status_code}")
            return
        except anthropic.APIConnectionError as exc:
            yield _error("upstream_unavailable", type(exc).__name__)
            return
        except Exception as exc:  # noqa: BLE001 - transport errors while reading the body
            yield _error("upstream_error", type(exc).__name__)
            return
        if final.stop_reason == "refusal":
            yield _error("refusal", "the model declined")
            return
        if final.stop_reason is None:
            yield _error("upstream_error", "stream ended without stop_reason")
            return
        usage = final.usage
        yield ProxyEvent("done", {
            "stopReason": final.stop_reason,
            "model": final.model,
            "inputTokens": usage.input_tokens,
            "outputTokens": usage.output_tokens,
        })

    return stream


_MOCK_LINE = re.compile(r"^# 고를 수 있는 대사\n- ([A-Za-z0-9_-]+) \(", re.MULTILINE)
"""The first line on offer in a prompt that has the model pick lines by id (voice_eval.lines)."""


def mock_upstream(
    answers: Mapping[str, str],
    first_delay_s: float = 0.4,
    chunk_chars: int = 6,
    chunk_delay_s: float = 0.05,
    sleep: Callable[[float], None] = time.sleep,
) -> Upstream:
    """Streams a canned answer per task like Claude would, so the app can be tested end to end
    without an API key or cost. The first delay is past the app's 0.3 s filler threshold. A prompt
    that has the model pick a suspect's lines gets the id of its first line instead, the app's
    default (the canned answer names no line, so the app would only ever deflect)."""

    def stream(request: RespondRequest) -> Iterator[ProxyEvent]:
        line = _MOCK_LINE.search(request.system)
        text = line.group(1) if line else answers[request.task]
        sleep(first_delay_s)
        for start in range(0, len(text), chunk_chars):
            if start:
                sleep(chunk_delay_s)
            yield ProxyEvent("delta", {"text": text[start : start + chunk_chars]})
        yield ProxyEvent("done", {"stopReason": "end_turn", "model": "mock",
                                  "inputTokens": 0, "outputTokens": 0})

    return stream


@dataclass(frozen=True)
class RelayOutcome:
    client_left: bool
    error_code: str | None
    """The code of an upstream error event, if the answer ended with one."""


def relay(events: Iterator[ProxyEvent], write: Callable[[bytes], None]) -> RelayOutcome:
    """Writes events to the client. The upstream is always closed, so a hang-up stops the Claude
    stream; on Windows a normal close surfaces as ConnectionAbortedError, hence ConnectionError."""
    error_code = None
    try:
        for event in events:
            if event.name == "error":
                error_code = str(event.data.get("code"))
            write(format_sse(event))
    except (ConnectionError, TimeoutError):
        return RelayOutcome(client_left=True, error_code=error_code)
    finally:
        close = getattr(events, "close", None)
        if close is not None:
            close()
    return RelayOutcome(client_left=False, error_code=error_code)


def is_loopback(host: str) -> bool:
    if host == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


@dataclass(frozen=True)
class ProxyConfig:
    token: str
    upstream: Upstream
    limiter: RateLimiter = field(default_factory=lambda: RateLimiter(limit=30))
    socket_timeout_s: float = SOCKET_TIMEOUT_S
    """A client that stalls while sending or reading cannot hold a server thread longer."""

    def __post_init__(self) -> None:
        token = self.token
        if len(token) < MIN_TOKEN_CHARS or not token.isascii() or any(char.isspace() for char in token):
            raise ValueError(
                f"the app token must be at least {MIN_TOKEN_CHARS} ASCII characters without spaces"
            )


def make_handler(config: ProxyConfig) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        server_version = "VoiceAgentDevProxy/0.1"
        sys_version = ""
        timeout = config.socket_timeout_s

        def log_message(self, format: str, *args: Any) -> None:
            logger.debug(format, *args)

        def _json(self, status: int, payload: Mapping[str, Any], retry_after: int = 0) -> None:
            body = json.dumps(dict(payload), ensure_ascii=False).encode()
            try:
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                if retry_after:
                    self.send_header("Retry-After", str(retry_after))
                self.end_headers()
                self.wfile.write(body)
            except (ConnectionError, TimeoutError):
                self.close_connection = True  # the client is gone; there is nobody to tell

        def _fail(self, status: int, code: str, message: str, retry_after: int = 0) -> None:
            self._json(status, {"error": {"code": code, "message": message}}, retry_after)

        def do_GET(self) -> None:
            if self.path == HEALTH_PATH:
                self._json(200, {"ok": True})
            else:
                self._fail(404, "not_found", self.path)

        def do_POST(self) -> None:
            if self.path != RESPOND_PATH:
                return self._fail(404, "not_found", self.path)
            token = self.headers.get("Authorization", "").removeprefix("Bearer ").strip()
            if not hmac.compare_digest(token.encode(), config.token.encode()):
                return self._fail(401, "unauthorized", "missing or wrong app token")
            if not config.limiter.allow(token):
                return self._fail(429, "rate_limited", "too many requests", int(RATE_WINDOW_S))
            try:
                request = parse_request(self._read_body())
            except RequestError as exc:
                return self._fail(400, "invalid_request", str(exc))
            except TimeoutError:
                return self._fail(408, "timeout", "the request body did not arrive in time")
            except ConnectionError:
                self.close_connection = True
                return None
            self._stream(request)
            return None

        def _read_body(self) -> bytes:
            try:
                length = int(self.headers.get("Content-Length") or 0)
            except ValueError as exc:
                raise RequestError("Content-Length must be a number") from exc
            if not 0 < length <= MAX_BODY_BYTES:
                raise RequestError(f"body must be 1-{MAX_BODY_BYTES} bytes")
            return self.rfile.read(length)

        def _stream(self, request: RespondRequest) -> None:
            try:
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Connection", "close")
                self.end_headers()
            except (ConnectionError, TimeoutError):
                self.close_connection = True
                return
            started = time.monotonic()
            outcome = relay(config.upstream(request), self._send)
            request_id = request.request_id or "-"
            if outcome.client_left:
                logger.info("client left %s", request_id)
            if outcome.error_code:
                logger.warning("%s %s upstream failed: %s", request.task, request_id, outcome.error_code)
            logger.info("%s %s %.0fms", request.task, request_id, (time.monotonic() - started) * 1000)

        def _send(self, data: bytes) -> None:
            self.wfile.write(data)
            self.wfile.flush()

    return Handler


class _DevServer(ThreadingHTTPServer):
    # Windows SO_REUSEADDR lets a second proxy bind the same port and silently take its requests.
    allow_reuse_address = sys.platform != "win32"


def make_server(host: str, port: int, config: ProxyConfig) -> ThreadingHTTPServer:
    return _DevServer((host, port), make_handler(config))
