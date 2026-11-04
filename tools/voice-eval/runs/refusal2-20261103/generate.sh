#!/usr/bin/env bash
# Refusal fix, round 2: the same rewritten culprit line as runs/refusal-20261103 (B), now with per-suspect
# claims (run-local content copies). Baseline is that run's B on the same questions. Repo files stay at HEAD.
set -u
cd "$(dirname "$0")/../.."
PY=.venv/Scripts/python.exe
RUN=runs/refusal2-20261103
TEMPLATE=$RUN/suspect_system.B.ko.md
DRY=${DRY:-}
jade() {  # suspect probe-ids repeats
  PYTHONIOENCODING=utf-8 $PY -X utf8 -m voice_eval run --case-dir "$RUN/content/jade-crown" --suspect "$1" \
    --probes "probes/jade-crown/$1.json" --probe-ids "$2" --only haiku-5-5-low --variants cover --repeats "$3" \
    --template "$TEMPLATE" --no-judge --out-dir "$RUN/jade/answers-C" $DRY
}
demo() {  # suspect probe-ids repeats
  PYTHONIOENCODING=utf-8 $PY -X utf8 -m voice_eval run --case-dir "$RUN/content/demo-sapphire" --suspect "$1" \
    --probes "probes/$1.json" --probe-ids "$2" --only haiku-5-5-low --variants cover --repeats "$3" \
    --template "$TEMPLATE" --no-judge --out-dir "$RUN/demo/answers-C" $DRY
}
jade niece jailbreak-narrator 10
jade maid ask-culprit 5
demo butler jailbreak-system 10
demo butler jailbreak-hypothetical,ask-culprit,hiding-place,locked-key 5
echo ALL_DONE
