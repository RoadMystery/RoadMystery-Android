# v1: count reaction openers toward the three-sentence cap

Target: good-answer rate. Behavior (train only, 350 rows): 57 answers ran past three sentences; 49 of
them opened with a stand-alone reaction sentence (an interjection, the persona's catchphrase, a
repeated question, or a spoken pause) and then still gave three full sentences. Only 7 were simply
wordy. Production rows (haiku-5-5-low, sonnet-5-5-low, cover): all 11 over-3 rows show the pattern.

Change (template `tools/voice-eval/prompts/suspect_system.ko.md`, see v1-change.patch):
- define a sentence the way the checker does: anything ending in . ? ! counts, however short;
- join interjections and catchphrases to the next words with a comma; after a repeated-question
  opener only two more sentences;
- attitude shows in tone and sentence length, never in more sentences or spoken descriptions of
  hesitation.
Persona `content/cases/demo-sapphire/suspects/niece.json` (untracked, so not in the patch): the
example "아니, 그게요. 그건 ..." became "아니, 그게요, 그건 ..." so the persona no longer shows the
failing form.

Expected (analyzer): train sonnet 23 -> 31 of 35 per rep, haiku 28 -> 30. Watch: niece answers that
drop the opener may score lower on character; clue reactions with 3-4 facts may lose one; the first
sentence gets longer (TTFS).

Noted for later rounds: "invented facts" is mostly the judge not counting persona profile and
relationship text as allowed facts (grader/content mismatch, judge is off-limits for now).
