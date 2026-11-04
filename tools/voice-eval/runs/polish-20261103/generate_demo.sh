#!/usr/bin/env bash
# demo-sapphire: all 50 probes x1 on the old and the new template, interleaved per suspect.
set -u
cd "$(dirname "$0")/../.."
PY=.venv/Scripts/python.exe
RUN=runs/polish-20261103/demo
DRY=${DRY:-}
for s in butler niece cook; do
  PYTHONIOENCODING=utf-8 $PY -X utf8 -m voice_eval run --suspect "$s" --only haiku-5-5-low --variants cover --repeats 1 \
    --no-judge --template runs/polish-20261103/suspect_system.old.ko.md --out-dir "$RUN/answers-Dold" $DRY
  PYTHONIOENCODING=utf-8 $PY -X utf8 -m voice_eval run --suspect "$s" --only haiku-5-5-low --variants cover --repeats 1 \
    --no-judge --out-dir "$RUN/answers-Dnew" $DRY
done
echo ALL_DONE
