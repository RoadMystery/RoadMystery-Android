import json
import socket
from pathlib import Path

import pytest

import voice_eval.__main__ as cli
from voice_eval.judge import JudgeError
from voice_eval.providers import MissingCredentialsError, ScriptedProvider, StreamResult

ONE_ANSWER = ["--only", "haiku-5-5-low", "--probe-ids", "alibi-basic", "--variants", "cover"]


class CountingProvider:
    def __init__(self, fail_on_call: int | None = None) -> None:
        self.calls = 0
        self._fail_on_call = fail_on_call
        self._scripted = ScriptedProvider(lambda *_: "저장고에 있었습니다.")

    def stream_answer(self, candidate, system, messages) -> StreamResult:
        self.calls += 1
        if self.calls == self._fail_on_call:
            raise RuntimeError("중간에 멈춤")
        return self._scripted.stream_answer(candidate, system, messages)


def test_render_prints_prompt_for_unlocked_clue(capsys: pytest.CaptureFixture[str]) -> None:
    assert cli.main(["render", "--unlocked", "clue-fusebox"]) == 0
    out = capsys.readouterr().out
    assert "한도윤" in out
    assert "지하실 두꺼비집" in out
    assert "절대 말하지 말 것" not in out


def test_render_truth_variant_adds_secret_section(capsys: pytest.CaptureFixture[str]) -> None:
    assert cli.main(["render", "--variant", "truth"]) == 0
    assert "절대 말하지 말 것" in capsys.readouterr().out


def test_identify_prints_suspect_id(capsys: pytest.CaptureFixture[str]) -> None:
    assert cli.main(["identify", "요리사한테 부엌 얘기 물어봐"]) == 0
    assert capsys.readouterr().out.strip() == "cook"


def test_identify_reports_when_nobody_matches(capsys: pytest.CaptureFixture[str]) -> None:
    assert cli.main(["identify", "오늘 날씨 어때"]) == 0
    assert "판별 실패" in capsys.readouterr().out


def test_dry_run_plans_without_writing(tmp_path: Path) -> None:
    assert cli.main(["run", "--dry-run", "--out-dir", str(tmp_path)]) == 0
    assert list(tmp_path.iterdir()) == []


def test_mock_run_writes_results_and_report(tmp_path: Path) -> None:
    code = cli.main(
        [
            "run",
            "--mock",
            "--only",
            "haiku-5-5-low",
            "--probe-ids",
            "alibi-basic,ask-culprit",
            "--out-dir",
            str(tmp_path),
        ]
    )

    assert code == 0
    run_dir = next(tmp_path.iterdir())
    lines = (run_dir / "results.jsonl").read_text(encoding="utf-8").splitlines()
    assert len(lines) == 2 * 2  # 2 probes x 2 variants
    assert json.loads(lines[0])["candidate"] == "haiku-5-5-low"
    assert "| haiku-5-5-low | cover |" in (run_dir / "report.md").read_text(encoding="utf-8")


@pytest.mark.parametrize(
    "flag",
    [["--only", "no-such-candidate"], ["--probe-ids", "no-such-probe"], ["--variants", "leaky"]],
)
def test_unknown_selection_exits(tmp_path: Path, flag: list[str]) -> None:
    with pytest.raises(SystemExit):
        cli.main(["run", "--mock", *flag, "--out-dir", str(tmp_path)])


def test_content_error_exits_with_code_2(tmp_path: Path) -> None:
    assert cli.main(["render", "--case-dir", str(tmp_path)]) == cli.EXIT_USAGE_ERROR


def test_missing_credentials_exit_with_code_2(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    class NoCredentialsProvider:
        def stream_answer(self, candidate, system, messages):
            raise MissingCredentialsError("Could not resolve authentication method")

    monkeypatch.setattr(cli, "AnthropicProvider", NoCredentialsProvider)
    code = cli.main(["run", *ONE_ANSWER, "--no-judge", "--out-dir", str(tmp_path)])
    assert code == cli.EXIT_USAGE_ERROR


def test_summary_mock_run_writes_results_and_report(tmp_path: Path) -> None:
    code = cli.main(
        ["summary", "--mock", "--only", "haiku-5-5-low",
         "--probe-ids", "empty-notebook,one-clue", "--out-dir", str(tmp_path)]
    )

    assert code == 0
    run_dir = next(tmp_path.iterdir())
    assert run_dir.name.endswith("-summary")
    rows = [json.loads(line) for line in (run_dir / "results.jsonl").read_text("utf-8").splitlines()]
    assert [row["fallback"] for row in rows] == [True, False]
    assert "| haiku-5-5-low | 2 |" in (run_dir / "report.md").read_text(encoding="utf-8")


def test_summary_dry_run_writes_nothing(tmp_path: Path) -> None:
    assert cli.main(["summary", "--dry-run", "--out-dir", str(tmp_path)]) == 0
    assert list(tmp_path.iterdir()) == []


def test_judge_preflight_failure_stops_before_paying_for_answers(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    class BrokenJudge:
        def __init__(self, model: str) -> None:
            self.model = model

        def evaluate(self, case, suspect, probe, unlocked, answer):
            raise JudgeError("NotFoundError: no such model")

    provider = CountingProvider()
    monkeypatch.setattr(cli, "ClaudeJudge", BrokenJudge)
    monkeypatch.setattr(cli, "AnthropicProvider", lambda: provider)

    code = cli.main(["run", *ONE_ANSWER, "--judge-model", "claude-typo", "--out-dir", str(tmp_path)])

    assert code == cli.EXIT_USAGE_ERROR
    assert provider.calls == 0


def test_report_is_written_when_a_run_stops_halfway(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    provider = CountingProvider(fail_on_call=2)
    monkeypatch.setattr(cli, "AnthropicProvider", lambda: provider)
    args = ["--only", "haiku-5-5-low", "--probe-ids", "alibi-basic,ask-culprit", "--variants", "cover"]

    with pytest.raises(RuntimeError):
        cli.main(["run", *args, "--no-judge", "--out-dir", str(tmp_path)])

    run_dir = next(tmp_path.iterdir())
    assert len((run_dir / "results.jsonl").read_text(encoding="utf-8").splitlines()) == 1
    assert "| haiku-5-5-low | cover |" in (run_dir / "report.md").read_text(encoding="utf-8")


def test_proxy_refuses_a_non_loopback_host(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("VOICE_PROXY_TOKEN", "dev-test-token-0123456789")
    assert cli.main(["proxy", "--mock", "--host", "0.0.0.0"]) == cli.EXIT_USAGE_ERROR


def test_proxy_rejects_a_short_token(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("VOICE_PROXY_TOKEN", "short")
    assert cli.main(["proxy", "--mock"]) == cli.EXIT_USAGE_ERROR


def test_proxy_reports_a_port_that_is_taken(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("VOICE_PROXY_TOKEN", "dev-test-token-0123456789")
    with socket.socket() as holder:
        holder.bind(("127.0.0.1", 0))
        holder.listen()
        port = holder.getsockname()[1]
        assert cli.main(["proxy", "--mock", "--port", str(port)]) == cli.EXIT_USAGE_ERROR
