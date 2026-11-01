"""Turn Gleo Speech Bench output into Markdown tables.

Input is either the saved speech-bench-*.json or a logcat dump containing BENCH_RESULT lines:
    adb logcat -d -s SpeechBench:I > bench.log
    python scripts/summarize_bench.py bench.log
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from collections.abc import Callable, Iterable
from pathlib import Path
from typing import Any

PREFIX = "BENCH_RESULT "
Row = dict[str, Any]


def load_results(path: Path) -> tuple[list[Row], list[Row]]:
    text = path.read_text(encoding="utf-8", errors="replace")
    if text.lstrip().startswith("{"):
        data = json.loads(text)
        return list(data["tts"]), list(data["stt"])
    tts: list[Row] = []
    stt: list[Row] = []
    for line in text.splitlines():
        index = line.find(PREFIX)
        if index == -1:
            continue
        item = json.loads(line[index + len(PREFIX) :])
        (tts if item["kind"] == "tts" else stt).append(item)
    return tts, stt


def load_all(paths: list[Path]) -> tuple[list[Row], list[Row]]:
    """Several sources may be combined, e.g. a TTS-only rerun plus an earlier STT run."""
    tts: list[Row] = []
    stt: list[Row] = []
    for path in paths:
        more_tts, more_stt = load_results(path)
        tts += more_tts
        stt += more_stt
    return tts, stt


def _cell(value: Any) -> str:
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:.2f}"
    return str(value).replace("|", "\\|")


def _table(headers: list[str], rows: Iterable[list[Any]]) -> str:
    lines = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
    lines += ["| " + " | ".join(_cell(value) for value in row) + " |" for row in rows]
    return "\n".join(lines)


def _median(rows: list[Row], key: str, keep: Callable[[Row], bool] = lambda _: True) -> float | None:
    values = [row[key] for row in rows if keep(row) and row.get(key) is not None]
    return statistics.median(values) if values else None


def _mean(rows: list[Row], key: str, keep: Callable[[Row], bool] = lambda _: True) -> float | None:
    values = [row[key] for row in rows if keep(row) and row.get(key) is not None]
    return statistics.fmean(values) if values else None


def _single_segment(row: Row) -> bool:
    return row.get("segments") == 1


def render(tts: list[Row], stt: list[Row]) -> str:
    sound_median = _median(tts, "soundLatencyMs", lambda r: r["label"] == "speaker")
    final_median = _median(stt, "finalAfterSpeechEndMs", _single_segment)
    single = _single_segment
    parts = [
        "## TTS (온디바이스)",
        "",
        _table(
            ["구분", "화자", "onStart(ms)", "실제 소리(ms)", "재생(ms)", "콜백", "오류"],
            (
                [r["label"], r["speaker"], r["startLatencyMs"], r.get("soundLatencyMs"),
                 r["playbackMs"], r.get("events"), r["error"]]
                for r in tts
            ),
        ),
        "",
        "- 실제 소리 = 재생 음량(RMS) 콜백이 처음 0보다 커진 시점(speak() 호출 기준)",
        f"- 실제 소리까지 중앙값(화자별 측정만): {_cell(sound_median)} ms",
        "",
        "## STT (온디바이스, 합성 음성)",
        "",
        _table(
            ["id", "정답", "인식", "조각", "CER", "첫 부분결과(ms)", "말 끝→최종(ms)", "오류"],
            (
                [r["id"], r["expected"], r["recognized"], r.get("segments"), r["cer"],
                 r["firstPartialMs"], r.get("finalAfterSpeechEndMs"), r["error"]]
                for r in stt
            ),
        ),
        "",
        f"- 평균 CER: {_cell(_mean(stt, 'cer'))}",
        f"- 한 문장 발화 평균 CER: {_cell(_mean(stt, 'cer', single))}",
        f"- 여러 조각으로 나뉜 발화: {sum(1 for r in stt if (r.get('segments') or 0) > 1)}/{len(stt)}",
        f"- 말 끝→최종 결과 중앙값(한 조각 발화만): {_cell(final_median)} ms",
        "  - 여러 조각 발화는 말 끝 위치를 조각마다 새로 세는 것으로 보여 제외",
        f"- 첫 부분 결과 중앙값(음성 시작부터): {_cell(_median(stt, 'firstPartialMs'))} ms",
    ]
    return "\n".join(parts) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "sources", type=Path, nargs="+", help="speech-bench-*.json or logcat dumps"
    )
    args = parser.parse_args(argv)
    tts, stt = load_all(args.sources)
    if not tts and not stt:
        sys.stderr.write("BENCH_RESULT 결과가 없습니다\n")
        return 1
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stdout.write(render(tts, stt))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
