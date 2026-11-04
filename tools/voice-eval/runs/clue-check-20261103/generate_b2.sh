#!/usr/bin/env bash
# Fresh answers for arms A (shipped template) and B (before-found section), registered in plan.json.
set -u
cd "$(dirname "$0")/../.."
PY=.venv/Scripts/python.exe
RUN=runs/clue-check-20261103
DRY=${DRY:-}
gen() {  # suspect probes-file-or-empty probe-ids repeats
  for arm in B2; do
    tpl=$RUN/suspect_system.B.ko.md
    probes=(); [ -n "$2" ] && probes=(--probes "$2")
    PYTHONIOENCODING=utf-8 $PY -X utf8 -m voice_eval run --suspect "$1" "${probes[@]}" --probe-ids "$3" \
      --only haiku-5-5-low --variants cover --repeats "$4" --no-judge --template "$tpl" \
      --out-dir "$RUN/answers-$arm" $DRY
  done
}
gen butler probes/locked/butler.json locked-key,key-have,key-door 5
gen cook probes/locked/cook.json locked-key,key-wine,key-door 5
gen niece probes/locked/niece.json key-who,key-enter 3
gen butler probes/locked/butler.json locked-gloves,gloves-barehand,fuse-breaker,fuse-cause,pawn-heard,pawn-money 2
gen cook probes/locked/cook.json gloves-white,gloves-seen,fuse-knew,fuse-cause,pawn-heard,pawn-money 2
gen niece probes/locked/niece.json locked-pawn,pawn-visit,pawn-watch,fuse-where,fuse-cause,gloves-who,gloves-rain 2
gen butler "" alibi-basic,blackout-activity,about-niece,fusebox-reaction,gloves-reaction,pawn-reaction,hiding-place,jailbreak-hypothetical 1
gen niece "" alibi-basic,saw-butler,shop,gloves-reaction,fusebox-reaction,hallway-pressure,accuse-butler,jailbreak-narrator 1
gen cook "" alibi-basic,footsteps,gloves-reaction,key-reaction,wet-floor,cake-pressure,ask-culprit,multi-turn-footsteps 1
echo ALL_DONE
