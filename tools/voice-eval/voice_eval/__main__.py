"""CLI: python -m voice_eval {run,summary,render,identify} ..."""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
from collections.abc import Sequence
from datetime import datetime
from pathlib import Path
from typing import Protocol, TextIO, TypeVar

import anthropic

from voice_eval.content import (
    STAGES,
    Case,
    ContentError,
    Probe,
    Suspect,
    load_case,
    load_probes,
    load_suspect,
)
from voice_eval.env import load_env_file
from voice_eval.golden import GOLDEN_CASE, build_golden
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
from voice_eval.proxy import (
    MIN_TOKEN_CHARS,
    RESPOND_PATH,
    ProxyConfig,
    RateLimiter,
    Upstream,
    claude_upstream,
    is_loopback,
    make_server,
    mock_upstream,
)
from voice_eval.report import render_markdown, summarize
from voice_eval.runner import RunRecord, run_matrix
from voice_eval.summary import (
    ClaudeSummaryJudge,
    SummaryJudge,
    SummaryProbe,
    load_summary_probes,
)
from voice_eval.summary_eval import SummaryRecord, render_summary_markdown, run_summary_matrix

TOOL_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = TOOL_ROOT.parents[1]
DEFAULT_CASE_DIR = REPO_ROOT / "content" / "cases" / "demo-sapphire"
DEFAULT_TEMPLATE = TOOL_ROOT / "prompts" / "suspect_system.ko.md"
DEFAULT_CANDIDATES = TOOL_ROOT / "candidates.json"
DEFAULT_RUNS_DIR = TOOL_ROOT / "runs"
DEFAULT_ENV_FILE = TOOL_ROOT / ".env"
"""Holds ANTHROPIC_API_KEY; gitignored, read at startup, values never printed."""
DEFAULT_SUMMARY_TEMPLATE = TOOL_ROOT / "prompts" / "summary_system.ko.md"
DEFAULT_SUMMARY_PROBES = TOOL_ROOT / "probes" / "summary.json"
DEFAULT_GOLDEN = TOOL_ROOT / "golden" / "voice-agent-golden.json"
DEFAULT_GOLDEN_CASE_DIR = REPO_ROOT / "content" / "cases" / GOLDEN_CASE
"""The golden file replays the case the app ships, so it does not follow DEFAULT_CASE_DIR."""
DEFAULT_GOLDEN_SUMMARY_PROBES = TOOL_ROOT / "probes" / GOLDEN_CASE / "summary.json"
MOCK_ANSWER = "그 시각에는 지하 저장고에서 와인을 고르고 있었습니다. 다른 것은 보지 못했습니다."
MOCK_SUMMARY = "지하실 두꺼비집 차단기가 손으로 내려가 있었어요. 집사는 그때 저장고에 있었다고 말했어요."
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


def _cmd_summary(args: argparse.Namespace) -> int:
    case = load_case(args.case_dir)
    probes = _pick(load_summary_probes(args.probes, case), args.probe_ids, "probe")
    candidates = _pick(load_candidates(args.candidates), args.only, "candidate")
    template = args.summary_template.read_text(encoding="utf-8")

    calls = args.repeats * len(candidates) * sum(1 for probe in probes if not probe.is_empty)
    judge_calls = 0 if (args.no_judge or args.mock) else calls
    logger.info("계획: 요약 %d회(빈 수첩은 LLM 없이 처리), 채점 %d회", calls, judge_calls)
    if args.dry_run:
        return 0

    if args.mock:
        provider: AnswerProvider = ScriptedProvider(lambda *_: MOCK_SUMMARY)
        judge: SummaryJudge | None = None
    else:
        provider = AnthropicProvider()
        judge = None if args.no_judge else ClaudeSummaryJudge(model=args.judge_model)
    first = next((probe for probe in probes if not probe.is_empty), None)
    if judge is not None and first is not None:
        try:
            judge.evaluate(case, first, MOCK_SUMMARY)
        except JudgeError as exc:
            logger.error("채점 확인 호출 실패, 요약을 부르기 전에 멈춥니다: %s", exc)
            return EXIT_USAGE_ERROR
    _execute_summary(args, case, probes, candidates, template, provider, judge)
    return 0


def _execute_summary(
    args: argparse.Namespace,
    case: Case,
    probes: Sequence[SummaryProbe],
    candidates: Sequence[Candidate],
    template: str,
    provider: AnswerProvider,
    judge: SummaryJudge | None,
) -> None:
    """Runs the summary matrix; the report is written even if the run stops halfway."""
    run_dir = args.out_dir / f"{datetime.now().strftime('%Y%m%d-%H%M%S')}-summary"
    run_dir.mkdir(parents=True, exist_ok=True)
    results_path = run_dir / "results.jsonl"
    records: list[SummaryRecord] = []
    try:
        with results_path.open("w", encoding="utf-8") as sink:

            def keep(record: SummaryRecord) -> None:
                sink.write(json.dumps(record.to_json(), ensure_ascii=False) + "\n")
                sink.flush()
                records.append(record)

            run_summary_matrix(
                case=case,
                probes=probes,
                candidates=candidates,
                repeats=args.repeats,
                template=template,
                provider=provider,
                judge=judge,
                sink=keep,
            )
    finally:
        (run_dir / "report.md").write_text(render_summary_markdown(records), encoding="utf-8")
        logger.info("결과: %s (%d건)", results_path, len(records))
        logger.info("보고서: %s", run_dir / "report.md")


def _proxy_upstream(args: argparse.Namespace) -> tuple[Upstream, str]:
    if args.mock:
        upstream = mock_upstream({"interrogation": MOCK_ANSWER, "summary": MOCK_SUMMARY})
        return upstream, "고정 답변(--mock, API 호출 없음)"
    if not os.environ.get("ANTHROPIC_API_KEY"):
        logger.warning("ANTHROPIC_API_KEY가 없어 요청마다 upstream_auth 오류가 납니다.")
    candidates = {candidate.id: candidate for candidate in load_candidates(args.candidates)}
    unknown = {args.interrogation, args.summary} - set(candidates)
    if unknown:
        raise SystemExit(f"unknown candidate id(s): {', '.join(sorted(unknown))}")
    profiles = {"interrogation": candidates[args.interrogation], "summary": candidates[args.summary]}
    for task, candidate in profiles.items():
        if candidate.fallbacks:
            logger.warning("%s 후보 %s의 fallbacks는 프록시에서 쓰지 않습니다. 거절은 refusal 오류로 "
                           "앱에 갑니다.", task, candidate.id)
    # No retries: a retry would re-send the prompt after the car has already given up (8 s).
    client = anthropic.Anthropic(max_retries=0, timeout=30.0)
    return claude_upstream(client, profiles), f"심문 {args.interrogation}, 요약 {args.summary}"


def _cmd_proxy(args: argparse.Namespace) -> int:
    token = os.environ.get("VOICE_PROXY_TOKEN", "")
    if not token:
        logger.error("VOICE_PROXY_TOKEN이 없습니다. tools/voice-eval/.env에 공백 없는 영문·숫자 "
                     "%d자 이상으로 VOICE_PROXY_TOKEN=... 한 줄을 추가하세요.", MIN_TOKEN_CHARS)
        return EXIT_USAGE_ERROR
    if not is_loopback(args.host) and not args.allow_remote:
        logger.error("--host %s는 이 PC 밖에서 접속할 수 있습니다. 토큰이 평문으로 오가 누구나 API 키로 "
                     "요청할 수 있게 되므로 거부합니다. 꼭 필요하면 --allow-remote를 붙이세요.", args.host)
        return EXIT_USAGE_ERROR
    upstream, models = _proxy_upstream(args)
    try:
        config = ProxyConfig(token, upstream, RateLimiter(args.rate_limit))
    except ValueError:
        logger.error("VOICE_PROXY_TOKEN은 공백 없는 영문·숫자 %d자 이상이어야 합니다.", MIN_TOKEN_CHARS)
        return EXIT_USAGE_ERROR
    try:
        server = make_server(args.host, args.port, config)
    except OSError as exc:
        logger.error("포트 %d를 열 수 없습니다(%s). 다른 프록시가 이미 떠 있는지 확인하세요.",
                     args.port, type(exc).__name__)
        return EXIT_USAGE_ERROR
    logger.info("개발용 프록시: http://%s:%d%s (에뮬레이터에서는 http://10.0.2.2:%d)",
                args.host, args.port, RESPOND_PATH, args.port)
    logger.info("%s, 분당 %d회 제한. 끝내려면 Ctrl+C", models, args.rate_limit)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logger.info("프록시를 멈췄습니다.")
    finally:
        server.server_close()
    return 0


def _cmd_golden(args: argparse.Namespace) -> int:
    golden = build_golden(
        args.case_dir,
        args.template.read_text(encoding="utf-8"),
        DEFAULT_SUMMARY_TEMPLATE.read_text(encoding="utf-8"),
        args.summary_probes,
    )
    args.out.parent.mkdir(parents=True, exist_ok=True)
    # LF on every OS: the committed file is LF and the Kotlin parity test reads it as is.
    args.out.write_text(json.dumps(golden, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
    logger.info("골든 파일: %s (프롬프트 %d개, 판별 %d개)", args.out, len(golden["prompts"]),
                len(golden["identify"]))
    return 0


def _cmd_render(args: argparse.Namespace) -> int:
    case, suspect = _load_case_and_suspect(args)
    template = args.template.read_text(encoding="utf-8")
    sys.stdout.write(
        render_system_prompt(
            case, suspect, frozenset(args.unlocked), args.variant, template, stage=args.stage
        )
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
    render.add_argument(
        "--stage", choices=STAGES, default="opening", help="수사 진척도 (pressing·ready면 단계별 대사가 붙음)"
    )
    render.set_defaults(handler=_cmd_render)

    identify = commands.add_parser(
        "identify", parents=[common], help="질문이 어느 용의자에게 한 것인지 판별"
    )
    identify.add_argument("utterance")
    identify.add_argument("--default", help="아무도 지목하지 않았을 때 쓸 용의자 id")
    identify.set_defaults(handler=_cmd_identify)

    summary = commands.add_parser(
        "summary", parents=[common], help="수사 수첩 요약(F-06)을 후보 모델별로 실행하고 채점"
    )
    summary.add_argument("--probes", type=Path, default=DEFAULT_SUMMARY_PROBES)
    summary.add_argument("--probe-ids", type=_csv, default=(), help="수첩 상황 id (쉼표 구분)")
    summary.add_argument("--summary-template", type=Path, default=DEFAULT_SUMMARY_TEMPLATE)
    summary.add_argument("--candidates", type=Path, default=DEFAULT_CANDIDATES)
    summary.add_argument("--only", type=_csv, default=(), help="후보 id (쉼표 구분)")
    summary.add_argument("--repeats", type=int, default=1)
    summary.add_argument("--judge-model", default="claude-opus-5-5")
    summary.add_argument("--no-judge", action="store_true", help="LLM 채점 생략")
    summary.add_argument("--mock", action="store_true", help="API 없이 고정 요약으로 실행")
    summary.add_argument("--dry-run", action="store_true", help="호출 횟수만 출력하고 종료")
    summary.add_argument("--out-dir", type=Path, default=DEFAULT_RUNS_DIR)
    summary.set_defaults(handler=_cmd_summary)

    proxy = commands.add_parser("proxy", help="에뮬레이터용 개발 프록시 서버 실행")
    proxy.add_argument("--host", default="127.0.0.1", help="기본값은 이 PC 안에서만 접속")
    proxy.add_argument("--port", type=int, default=8787)
    proxy.add_argument("--candidates", type=Path, default=DEFAULT_CANDIDATES)
    proxy.add_argument("--interrogation", default="haiku-5-5-low", help="심문에 쓸 후보 id")
    proxy.add_argument("--summary", default="sonnet-5-5-low", help="요약에 쓸 후보 id")
    proxy.add_argument("--rate-limit", type=int, default=30, help="토큰당 분당 요청 수")
    proxy.add_argument("--mock", action="store_true",
                       help="API 없이 고정 답변을 스트리밍 (앱 연결 확인용, 비용 없음)")
    proxy.add_argument("--allow-remote", action="store_true",
                       help="127.0.0.1 밖의 --host 허용 (토큰이 평문으로 오가므로 권장하지 않음)")
    proxy.set_defaults(handler=_cmd_proxy)

    # Not parents=[common]: its actions are shared, so a golden default would change them all.
    golden = commands.add_parser("golden", help="Kotlin VoiceAgent가 맞춰야 할 골든 파일 생성(앱이 쓰는 사건)")
    golden.add_argument("--case-dir", type=Path, default=DEFAULT_GOLDEN_CASE_DIR)
    golden.add_argument("--template", type=Path, default=DEFAULT_TEMPLATE)
    golden.add_argument("--summary-probes", type=Path, default=DEFAULT_GOLDEN_SUMMARY_PROBES)
    golden.add_argument("--out", type=Path, default=DEFAULT_GOLDEN)
    golden.set_defaults(handler=_cmd_golden)
    return parser


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    loaded = load_env_file(DEFAULT_ENV_FILE)
    if loaded:
        logger.info("%s에서 읽은 설정: %s", DEFAULT_ENV_FILE.name, ", ".join(loaded))
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
