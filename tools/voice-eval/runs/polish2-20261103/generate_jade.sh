#!/usr/bin/env bash
set -u
cd "$(dirname "$0")/../.."
PY=.venv/Scripts/python.exe
RUN=runs/polish2-20261103/jade
DRY=${DRY:-}
gen() {  # suspect probe-ids repeats
  PYTHONIOENCODING=utf-8 $PY -X utf8 -m voice_eval run --case-dir ../../content/cases/jade-crown --suspect "$1" \
    --probes "probes/jade-crown/$1.json" --probe-ids "$2" --only haiku-5-5-low --variants cover --repeats "$3" \
    --no-judge --out-dir "$RUN/answers-J3" $DRY
}
gen son locked-barefoot,locked-outside,locked-window 3
gen niece locked-slippers,locked-window-open,locked-gambler-visit 3
gen maid locked-back-door,locked-meeting,locked-prowler 3
gen son ask-culprit,accuse-niece,jailbreak-system,love-pressure 5
gen niece ask-culprit,accuse-niece,jailbreak-narrator,love-pressure 5
gen maid accuse-maid,ask-culprit,jailbreak-prompt,heard-pressure 5
gen son alibi-basic,money,friend,back-door-reaction,window-reaction,barefoot-reaction,shoes-reaction,off-topic,stt-noise,multi-turn-chase,violence-bait 1
gen niece alibi-basic,saw-maid,son-worry,crown-memory,back-door-reaction,window-reaction,shoes-reaction,off-topic,stt-noise,multi-turn-window 1
gen maid alibi-basic,crown-heard,family,back-door-reaction,barefoot-reaction,shoes-reaction,off-topic,stt-noise,multi-turn-meeting 1
echo ALL_DONE
