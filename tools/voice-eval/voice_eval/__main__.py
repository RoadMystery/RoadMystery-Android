"""CLI: python -m voice_eval {run,render,identify} ..."""

from __future__ import annotations

import argparse
import json
import logging
import sys
from collections.abc import Sequence
from datetime import datetime
from pathlib import Path
from typing import Protocol, TextIO, TypeVar

import anthropic

from voice_eval.content import (
    Case,
    ContentError,
    Probe,
    Suspect,
    load_case,
    load_probes,
    load_suspect,
)
from voice_eval.identify import identify_suspect, suspect_aliases
from voice_eval.judge import ClaudeJudge, Judge, JudgeError
from voice_eval.prompt import VARIANTS, render_system_prompt
from voice_eval.providers import (
    AnswerProvider,
    AnthropicProvider,
    Candidate,
    MissingCredentialsError,
    ScriptedProvider,
    load_candidates,
)
from voice_eval.report import render_markdown, summarize
from voice_eval.runner import RunRecord, run_matrix

TOOL_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = TOOL_ROOT.parents[1]
DEFAULT_CASE_DIR = REPO_ROOT / "content" / "cases" / "demo-sapphire"
DEFAULT_TEMPLATE = TOOL_ROOT / "prompts" / "suspect_system.ko.md"
DEFAULT_CANDIDATES = TOOL_ROOT / "candidates.json"
DEFAULT_RUNS_DIR = TOOL_ROOT / "runs"
MOCK_ANSWER = "그 시각에는 지하 저장고에서 와인을 고르고 있었습니다. 다른 것은 보지 못했습니다."
EXIT_USAGE_ERROR = 2

logger = logging.getLogger("voice_eval")


class _HasId(Protocol):
    @property
    def id(self) -> str: ...


T = TypeVar("T", bound=_HasId)


def _csv(value: str) -> tuple[str, ...]:
    return tuple(item.strip() for item in value.split(",") if item.strip())


def _pick(items: Sequence[T], wanted: tuple[str, ...], what: str) -> tuple[T, ...]:
    if not wanted:
        return tuple(items)
    unknown = set(wanted) - {item.id for item in items}
    if unknown:
        raise SystemExit(f"unknown {what} id(s): {', '.join(sorted(unknown))}")
    return tuple(item for item in items if item.id in wanted)


def _load_case_and_suspect(args: argparse.Namespace) -> tuple[Case, Suspect]:
    case = load_case(args.case_dir)
    suspect = load_suspect(args.case_dir / "suspects" / f"{args.suspect}.json", case)
    return case, suspect


def _build_backends(args: argparse.Namespace) -> tuple[AnswerProvider, Judge | None]:
    if args.mock:
        return ScriptedProvider(lambda *_: MOCK_ANSWER), None
    judge = None if args.no_judge else ClaudeJudge(model=args.judge_model)
    return AnthropicProvider(), judge


def _write_jsonl(sink: TextIO, record: RunRecord) -> None:
    sink.write(json.dumps(record.to_json(), ensure_ascii=False) + "\n")
    sink.flush()


def _cmd_run(args: argparse.Namespace) -> int:
    unknown_variants = set(args.variants) - set(VARIANTS)
    if unknown_variants:
        raise SystemExit(f"unknown variant(s): {', '.join(sorted(unknown_variants))}")
    case, suspect = _load_case_and_suspect(args)
    probes_path = args.probes or TOOL_ROOT / "probes" / f"{args.suspect}.json"
    probes = _pick(load_probes(probes_path, case), args.probe_ids, "probe")
    candidates = _pick(load_candidates(args.candidates), args.only, "candidate")
    template = args.template.read_text(encoding="utf-8")

    answers = args.repeats * len(probes) * len(args.variants) * len(candidates)
    judge_calls = 0 if (args.no_judge or args.mock) else answers
    logger.info(
        "계획: 답변 %d회 (후보 %d × 변형 %d × 질문 %d × 반복 %d), 채점 %d회",
        answers,
        len(candidates),
        len(args.variants),
        len(probes),
        args.repeats,
        judge_calls,
    )
    if args.dry_run:
        return 0

    provider, judge = _build_backends(args)
    if judge is not None:
        problem = _preflight_judge(judge, case, suspect, probes[0])
        if problem is not None:
            logger.error("채점 확인 호출 실패, 답변을 부르기 전에 멈춥니다: %s", problem)
            return EXIT_USAGE_ERROR
    _execute(args, case, suspect, probes, candidates, template, provider, judge)
    return 0


def _preflight_judge(judge: Judge, case: Case, suspect: Suspect, probe: Probe) -> str | None:
    """One cheap judge call before paying for the answer matrix (bad model id, schema errors)."""
    try:
        judge.evaluate(case, suspect, probe, frozenset(probe.unlocked), MOCK_ANSWER)
    except JudgeError as exc:
        return str(exc)
    return None


def _execute(
    args: argparse.Namespace,
    case: Case,
    suspect: Suspect,
    probes: Sequence[Probe],
    candidates: Sequence[Candidate],
    template: str,
    provider: AnswerProvider,
    judge: Judge | None,
) -> None:
    """Runs the matrix; the report is written even if the run stops halfway."""
    run_dir = args.out_dir / datetime.now().strftime("%Y%m%d-%H%M%S")
    run_dir.mkdir(parents=True, exist_ok=True)
    results_path = run_dir / "results.jsonl"
    report_path = run_dir / "report.md"
    records: list[RunRecord] = []
    try:
        with results_path.open("w", encoding="utf-8") as sink:

            def keep(record: RunRecord) -> None:
                _write_jsonl(sink, record)
                records.append(record)

            run_matrix(
                case=case,
                suspect=suspect,
                probes=probes,
                candidates=candidates,
                variants=args.variants,
                repeats=args.repeats,
                template=template,
                provider=provider,
                judge=judge,
                sink=keep,
            )
    finally:
        report_path.write_text(render_markdown(summarize(records), records), encoding="utf-8")
        logger.info("결과: %s (%d건)", results_path, len(records))
        logger.info("보고서: %s", report_path)


def _cmd_render(args: argparse.Namespace) -> int:
    case, suspect = _load_case_and_suspect(args)
    template = args.template.read_text(encoding="utf-8")
    sys.stdout.write(
        render_system_prompt(case, suspect, frozenset(args.unlocked), args.variant, template)
    )
    return 0


def _cmd_identify(args: argparse.Namespace) -> int:
    case = load_case(args.case_dir)
    found = identify_suspect(args.utterance, suspect_aliases(case), default=args.default)
    sys.stdout.write(f"{found or '(판별 실패)'}\n")
    return 0


def _parser() -> argparse.ArgumentParser:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--case-dir", type=Path, default=DEFAULT_CASE_DIR)
    common.add_argument("--suspect", default="butler")
    common.add_argument("--template", type=Path, default=DEFAULT_TEMPLATE)

    parser = argparse.ArgumentParser(
        prog="python -m voice_eval", description="VoiceAgent 용의자 프롬프트·LLM 비교 도구"
    )
    commands = parser.add_subparsers(dest="command", required=True)

    run = commands.add_parser(
        "run", parents=[common], help="후보 모델 × 프롬프트 변형 × 질문을 실행하고 채점"
    )
    run.add_argument("--probes", type=Path)
    run.add_argument("--probe-ids", type=_csv, default=(), help="질문 id (쉼표 구분)")
    run.add_argument("--candidates", type=Path, default=DEFAULT_CANDIDATES)
    run.add_argument("--only", type=_csv, default=(), help="후보 id (쉼표 구분)")
    run.add_argument("--variants", type=_csv, default=VARIANTS, help="cover,truth")
    run.add_argument("--repeats", type=int, default=1)
    run.add_argument("--judge-model", default="claude-opus-5-5")
    run.add_argument("--no-judge", action="store_true", help="LLM 채점 생략")
    run.add_argument("--mock", action="store_true", help="API 없이 고정 답변으로 실행")
    run.add_argument("--dry-run", action="store_true", help="호출 횟수만 출력하고 종료")
    run.add_argument("--out-dir", type=Path, default=DEFAULT_RUNS_DIR)
    run.set_defaults(handler=_cmd_run)

    render = commands.add_parser("render", parents=[common], help="시스템 프롬프트 출력")
    render.add_argument("--unlocked", type=_csv, default=(), help="찾은 단서 id (쉼표 구분)")
    render.add_argument("--variant", choices=VARIANTS, default="cover")
    render.set_defaults(handler=_cmd_render)

    identify = commands.add_parser(
        "identify", parents=[common], help="질문이 어느 용의자에게 한 것인지 판별"
    )
    identify.add_argument("utterance")
    identify.add_argument("--default", help="아무도 지목하지 않았을 때 쓸 용의자 id")
    identify.set_defaults(handler=_cmd_identify)
    return parser


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    args = _parser().parse_args(argv)
    try:
        return args.handler(args)
    except ContentError as exc:
        logger.error("콘텐츠 오류: %s", exc)
        return EXIT_USAGE_ERROR
    except (
        anthropic.AuthenticationError,
        anthropic.PermissionDeniedError,
        anthropic.CredentialsError,
        MissingCredentialsError,
    ) as exc:
        logger.error(
            "Claude API 인증 실패: %s\nANTHROPIC_API_KEY를 설정하거나 `ant auth login`으로 "
            "로그인한 뒤 다시 실행하세요.",
            exc,
        )
        return EXIT_USAGE_ERROR


if __name__ == "__main__":
    raise SystemExit(main())
