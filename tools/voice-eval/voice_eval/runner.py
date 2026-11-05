"""Run every (candidate, prompt variant, probe, repeat) combination and score each answer."""

from __future__ import annotations

import logging
from collections.abc import Callable, Sequence
from dataclasses import asdict, dataclass
from typing import Any

from voice_eval.content import Case, Probe, Suspect
from voice_eval.judge import Judge, JudgeError, JudgeVerdict
from voice_eval.metrics import AnswerChecks, check_answer
from voice_eval.prompt import build_messages, raised_topics, render_system_prompt
from voice_eval.providers import AnswerProvider, Candidate, StreamResult

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class RunRecord:
    candidate: Candidate
    variant: str
    probe: Probe
    repeat: int
    result: StreamResult
    checks: AnswerChecks | None
    verdict: JudgeVerdict | None
    judge_error: str | None = None

    def to_json(self) -> dict[str, Any]:
        result = self.result
        return {
            "candidate": self.candidate.id,
            "model": self.candidate.model,
            "served_by": result.served_by,
            "variant": self.variant,
            "probe": self.probe.id,
            "category": self.probe.category,
            "repeat": self.repeat,
            "unlocked": list(self.probe.unlocked),
            "stage": self.probe.stage,
            "question": self.probe.question,
            "answer": result.text,
            "timing": asdict(result.timing),
            "stop_reason": result.stop_reason,
            "usage": {"input_tokens": result.input_tokens, "output_tokens": result.output_tokens},
            "cost_usd": result.cost_usd,
            "error": result.error,
            "fell_back": result.fell_back,
            "checks": asdict(self.checks) if self.checks else None,
            "verdict": asdict(self.verdict) if self.verdict else None,
            "judge_error": self.judge_error,
        }


def _score(
    case: Case,
    suspect: Suspect,
    probe: Probe,
    result: StreamResult,
    judge: Judge | None,
) -> tuple[AnswerChecks | None, JudgeVerdict | None, str | None]:
    if result.error is not None:
        return None, None, None
    checks = check_answer(result.text, case, suspect.name)
    if judge is None or result.stop_reason == "refusal" or not result.text.strip():
        return checks, None, None
    try:
        verdict = judge.evaluate(case, suspect, probe, frozenset(probe.unlocked), result.text)
    except JudgeError as exc:
        return checks, None, str(exc)
    return checks, verdict, None


def run_matrix(
    *,
    case: Case,
    suspect: Suspect,
    probes: Sequence[Probe],
    candidates: Sequence[Candidate],
    variants: Sequence[str],
    repeats: int,
    template: str,
    provider: AnswerProvider,
    judge: Judge | None = None,
    sink: Callable[[RunRecord], Any] | None = None,
) -> tuple[RunRecord, ...]:
    """Candidates are interleaved per probe so network drift hits them all alike.

    Every prompt and message list is built before the first (paid) call, so bad content fails fast.
    """
    # Per probe, because a question that raises a clue not found yet adds a topic rule and a question
    # asked late in the investigation adds the persona's pressure line.
    systems = {
        (variant, probe.id): render_system_prompt(
            case,
            suspect,
            frozenset(probe.unlocked),
            variant,
            template,
            topics=raised_topics(case, frozenset(probe.unlocked), probe.question),
            stage=probe.stage,
        )
        for variant in variants
        for probe in probes
    }
    conversations = {probe.id: build_messages(probe) for probe in probes}
    total = repeats * len(probes) * len(variants) * len(candidates)
    records: list[RunRecord] = []
    for repeat in range(1, repeats + 1):
        for probe in probes:
            messages = conversations[probe.id]
            for variant in variants:
                system = systems[(variant, probe.id)]
                for candidate in candidates:
                    result = provider.stream_answer(candidate, system, messages)
                    checks, verdict, judge_error = _score(case, suspect, probe, result, judge)
                    record = RunRecord(
                        candidate=candidate,
                        variant=variant,
                        probe=probe,
                        repeat=repeat,
                        result=result,
                        checks=checks,
                        verdict=verdict,
                        judge_error=judge_error,
                    )
                    records.append(record)
                    if sink is not None:
                        sink(record)
                    logger.info(
                        "[%d/%d] %s %s %s -> %s",
                        len(records),
                        total,
                        candidate.id,
                        variant,
                        probe.id,
                        result.error or f"{result.timing.ttfs_ms or 0:.0f}ms",
                    )
    return tuple(records)
