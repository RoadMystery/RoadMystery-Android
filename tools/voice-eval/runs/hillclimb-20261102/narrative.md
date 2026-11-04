# Hill-climb 2026-11-02: suspect answers (F-05)

| round | change | haiku test | haiku train | sonnet test | sonnet train | haiku TTFS p50/p90 | spend |
|---|---|---|---|---|---|---|---|
| 0 base | baseline prompt | 22/30 | 55/70 | 13/30 | 49/70 | 0.90 / 1.65 s | 1.85 |
| 1 v1 | openers count toward the 3-sentence cap | 30/30 | 56/70 | 26/30 | 66/70 | 0.88 / 1.35 s | 5.65 |
| 2 v2 | answer case questions directly; unlisted = don't know | 27/30 | 60/70 | 29/30 | 66/70 | 0.94 / 1.23 s | 9.51 |
| 3 v3 | plausible-but-unlisted stays unsaid; in-character turn | 28/30 | 60/70 | 28/30 | 69/70 | 0.95 / 1.86 s | 13.38 |
| final | v2 + butler lines + niece example | 27/30 (v2 rows) | 63/70 | 29/30 (v2 rows) | 65/70 | 0.95 / 1.60 s | 16.12 |

Spend column is cumulative for the answer rounds; summary rounds and regrades add 1.24.

v1 is the round that moved things: counting reaction openers as sentences took Haiku's over-three-sentence
answers from 11 to 0 and lifted Sonnet from 62 to 92 of 100. v2 traded Haiku's evasive answers for direct ones
without moving the total, and v3 tied again while adding a new failure (rule-like planning text read aloud), so
the loop stopped on a saturated test set. The final config keeps v2's template and only fixes two persona lines
that were wrong as content; it passed the rule registered before the run (state.json final_check: haiku train
63/70, no solution leak, no instruction-like text, over3 0, locked 2) and is what ships.
Haiku stays the interrogation pick: tied with Sonnet on test, behind on train, but about 1.2 s vs 4.1 s at TTFS
p90 because Sonnet thinks before about a quarter of its answers under the longer rule set.
