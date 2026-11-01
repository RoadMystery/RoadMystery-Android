"""LLM answer providers: the Claude API adapter and a scripted provider for offline runs."""

from __future__ import annotations

import itertools
import json
import time
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

import anthropic

from voice_eval.metrics import StreamTimer, Timing

FALLBACK_BETA = "server-side-fallback-2026-07-01"
DEFAULT_MAX_TOKENS = 4096
"""Answers are three sentences, but thinking also counts toward max_tokens."""
BENCH_TIMEOUT_S = 60.0

PRICING_PER_MTOK: Mapping[str, tuple[float, float]] = {
    # USD (input, output) per million tokens, Anthropic API list price as of 2026-10.
    "claude-haiku-5-5": (0.10, 0.50),
    "claude-sonnet-5-5": (2.00, 10.00),
    "claude-opus-5-5": (4.00, 20.00),
}

Messages = list[dict[str, str]]

_NO_CREDENTIALS = "Could not resolve authentication method"


class MissingCredentialsError(RuntimeError):
    """No Claude API credentials could be resolved (no API key, token, or login profile)."""


def raise_if_missing_credentials(exc: TypeError) -> None:
    """The SDK reports missing credentials as a TypeError; turn that into MissingCredentialsError."""
    if _NO_CREDENTIALS in str(exc):
        raise MissingCredentialsError(str(exc)) from exc


def benchmark_client() -> anthropic.Anthropic:
    """No automatic retries: a retry (and any retry-after wait) would land inside the timed
    region and be recorded as a slow success. Failed requests are counted as errors instead."""
    return anthropic.Anthropic(max_retries=0, timeout=BENCH_TIMEOUT_S)


def answer_after_last_fallback(content: Sequence[Any], streamed: str) -> tuple[str, bool]:
    """With server-side fallbacks the content is [declined text, fallback block, fallback text]
    and text_stream yields both halves, so keep only the text after the last fallback block."""
    positions = [i for i, block in enumerate(content) if getattr(block, "type", None) == "fallback"]
    if not positions:
        return streamed, False
    tail = content[positions[-1] + 1 :]
    return "".join(block.text for block in tail if getattr(block, "type", None) == "text"), True


@dataclass(frozen=True)
class Candidate:
    id: str
    model: str
    effort: str
    thinking: Mapping[str, Any] | None = None
    fallbacks: str | None = None
    max_tokens: int = DEFAULT_MAX_TOKENS
    note: str = ""

    def request_params(self, system: str, messages: Messages) -> dict[str, Any]:
        params: dict[str, Any] = {
            "model": self.model,
            "max_tokens": self.max_tokens,
            "system": system,
            "messages": messages,
            "output_config": {"effort": self.effort},
        }
        if self.thinking is not None:
            params["thinking"] = dict(self.thinking)
        return params


def load_candidates(path: Path) -> tuple[Candidate, ...]:
    data = json.loads(path.read_text(encoding="utf-8"))
    candidates = tuple(
        Candidate(
            id=item["id"],
            model=item["model"],
            effort=item["effort"],
            thinking=item.get("thinking"),
            fallbacks=item.get("fallbacks"),
            max_tokens=item.get("max_tokens", DEFAULT_MAX_TOKENS),
            note=item.get("note", ""),
        )
        for item in data
    )
    ids = [candidate.id for candidate in candidates]
    if len(ids) != len(set(ids)):
        raise ValueError(f"{path}: candidate ids must be unique")
    for candidate in candidates:
        thinking_type = (candidate.thinking or {}).get("type")
        if candidate.fallbacks and thinking_type == "between_tools":
            raise ValueError(
                f"{path}: '{candidate.id}' sends between_tools, which fallback models reject"
            )
    return candidates


@dataclass(frozen=True)
class StreamResult:
    text: str
    timing: Timing
    stop_reason: str | None = None
    served_by: str | None = None
    input_tokens: int | None = None
    output_tokens: int | None = None
    error: str | None = None
    fell_back: bool = False
    """True when a server-side fallback model produced the answer (served_by names it)."""

    @classmethod
    def failed(cls, error: str, total_ms: float) -> StreamResult:
        return cls(text="", timing=Timing(None, None, total_ms), error=error)

    @property
    def cost_usd(self) -> float | None:
        prices = PRICING_PER_MTOK.get(self.served_by or "")
        if prices is None or self.input_tokens is None or self.output_tokens is None:
            return None
        input_price, output_price = prices
        return (self.input_tokens * input_price + self.output_tokens * output_price) / 1_000_000


class AnswerProvider(Protocol):
    def stream_answer(
        self, candidate: Candidate, system: str, messages: Messages
    ) -> StreamResult: ...


@dataclass
class AnthropicProvider:
    """Streams one answer from the Claude API and times first token / first sentence.

    Authentication, permission and missing-credential errors propagate (the whole run is
    pointless); every other failure, including transport errors raised while the stream body
    is read, is returned as a failed StreamResult so a long run continues and is reported.
    """

    client: anthropic.Anthropic = field(default_factory=benchmark_client)
    clock: Callable[[], float] = time.perf_counter

    def _open_stream(self, candidate: Candidate, params: dict[str, Any]) -> Any:
        if candidate.fallbacks:
            return self.client.beta.messages.stream(
                **params, betas=[FALLBACK_BETA], fallbacks=candidate.fallbacks
            )
        return self.client.messages.stream(**params)

    def stream_answer(
        self, candidate: Candidate, system: str, messages: Messages
    ) -> StreamResult:
        params = candidate.request_params(system, messages)
        start = self.clock()
        timer = StreamTimer(start=start, clock=self.clock)

        def failed(error: str) -> StreamResult:
            return StreamResult.failed(error, total_ms=(self.clock() - start) * 1000.0)

        try:
            with self._open_stream(candidate, params) as stream:
                for text in stream.text_stream:
                    timer.on_text(text)
                final = stream.get_final_message()
        except (
            anthropic.AuthenticationError,
            anthropic.PermissionDeniedError,
            anthropic.CredentialsError,
        ):
            raise
        except TypeError as exc:
            raise_if_missing_credentials(exc)
            raise
        except anthropic.NotFoundError as exc:
            return failed(f"NotFoundError: {exc.message}")
        except anthropic.RateLimitError as exc:
            return failed(f"RateLimitError: {exc.message}")
        except anthropic.BadRequestError as exc:
            return failed(f"BadRequestError: {exc.message}")
        except anthropic.APIStatusError as exc:
            return failed(f"APIStatusError {exc.status_code}: {exc.message}")
        except anthropic.APIConnectionError as exc:
            return failed(f"APIConnectionError: {exc}")
        # The SDK wraps transport errors only around send(); errors while reading the stream
        # body (httpx2.ReadError, RemoteProtocolError, ...) arrive raw. Record, don't abort.
        except Exception as exc:  # noqa: BLE001
            return failed(f"{type(exc).__name__}: {exc}")

        timing = timer.finish()
        text, fell_back = timer.text, False
        if candidate.fallbacks:
            text, fell_back = answer_after_last_fallback(getattr(final, "content", ()), text)
        return StreamResult(
            text=text,
            timing=timing,
            stop_reason=final.stop_reason,
            served_by=final.model,
            input_tokens=final.usage.input_tokens,
            output_tokens=final.usage.output_tokens,
            error=None if final.stop_reason is not None else "stream ended without stop_reason",
            fell_back=fell_back,
        )


class ScriptedProvider:
    """Returns scripted answers in fixed-size chunks on a synthetic clock (no network)."""

    def __init__(
        self,
        script: Callable[[Candidate, str, Messages], str],
        ms_per_chunk: float = 40.0,
        chunk_chars: int = 4,
    ) -> None:
        self._script = script
        self._ms_per_chunk = ms_per_chunk
        self._chunk_chars = chunk_chars

    def stream_answer(
        self, candidate: Candidate, system: str, messages: Messages
    ) -> StreamResult:
        answer = self._script(candidate, system, messages)
        ticks = itertools.count(1)
        timer = StreamTimer(start=0.0, clock=lambda: next(ticks) * self._ms_per_chunk / 1000.0)
        for index in range(0, len(answer), self._chunk_chars):
            timer.on_text(answer[index : index + self._chunk_chars])
        return StreamResult(
            text=timer.text,
            timing=timer.finish(),
            stop_reason="end_turn",
            served_by=candidate.model,
            input_tokens=len(system),
            output_tokens=len(answer),
        )
