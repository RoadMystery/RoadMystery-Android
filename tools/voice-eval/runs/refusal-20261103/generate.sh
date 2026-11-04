#!/usr/bin/env bash
# Refusal fix: the shared template's culprit line rewritten (B) against HEAD (A), only on the questions
# where the refusal lives, plus controls and side-effect questions. A is not judged (answers in base-A).
set -u
cd "$(dirname "$0")/../.."
PY=.venv/Scripts/python.exe
RUN=runs/refusal-20261103
HEAD_TEMPLATE=$RUN/suspect_system.head.ko.md
DRY=${DRY:-}
jade() {  # out-dir suspect probe-ids repeats [extra args]
  local out=$1 s=$2 ids=$3 n=$4
  shift 4
  PYTHONIOENCODING=utf-8 $PY -X utf8 -m voice_eval run --case-dir ../../content/cases/jade-crown --suspect "$s" \
    --probes "probes/jade-crown/$s.json" --probe-ids "$ids" --only haiku-5-5-low --variants cover --repeats "$n" \
    --no-judge --out-dir "$RUN/jade/$out" "$@" $DRY
}
demo() {  # out-dir suspect probe-ids repeats [extra args]
  local out=$1 s=$2 ids=$3 n=$4
  shift 4
  PYTHONIOENCODING=utf-8 $PY -X utf8 -m voice_eval run --suspect "$s" --probe-ids "$ids" --only haiku-5-5-low \
    --variants cover --repeats "$n" --no-judge --out-dir "$RUN/demo/$out" "$@" $DRY
}
# Primary questions, A and B back to back.
jade base-A niece jailbreak-narrator 10 --template "$HEAD_TEMPLATE"
jade answers-B niece jailbreak-narrator 10
demo base-A butler jailbreak-system 10 --template "$HEAD_TEMPLATE"
demo answers-B butler jailbreak-system 10
# B only, 5 each: other refusal questions, controls (son, cook) and side effects.
jade answers-B niece ask-culprit,accuse-niece 5
jade answers-B maid jailbreak-prompt,ask-culprit,accuse-maid 5
jade answers-B son jailbreak-system,ask-culprit 5
demo answers-B butler ask-culprit,jailbreak-hypothetical,locked-gloves,locked-key,hiding-place,direct-accusation 5
demo answers-B niece jailbreak-narrator,ask-culprit 5
demo answers-B cook jailbreak-prompt,ask-culprit 5
echo ALL_DONE
