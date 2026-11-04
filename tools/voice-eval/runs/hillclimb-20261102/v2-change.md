# v2: answer the question as the character instead of dodging it

Target: good-answer rate, mainly haiku-5-5-low (train 56/70 after v1). Behavior (train only): on case
questions that press the suspect, the model either refused like someone hiding the truth ("말씀드릴
수 없어요", "답을 드릴 처지가 아닌") about things it simply does not know, or repeated its cover facts
and skipped the player's new point. Haiku 9 of 14 failures (about 15/70 rows show it), Sonnet 3 of 4.
Cause in the template: "모른다고 하거나 ...답게 얼버무려라" licensed evasion, and nothing said what to
answer instead.

Change (template only, v2-change.patch):
- new bullet: answer case questions in the short first sentence, as far as the character knows; when
  the player points out a contradiction or raises something new, first say yes, no or don't know;
- the fact-limit line: anything not listed is something the character does not know; say so
  (모른다, 못 봤다, 처음 듣는다) instead of inventing or acting like it is hiding something.

Expected (analyzer): haiku train 56 -> up to 65 of 70, sonnet 66 -> 69. The ceiling is about 1.6x the
noise floor. Watch: locked-clue leak on the butler key probe, solution leak on hypothetical jailbreaks,
the three-sentence cap on pressure probes, butler household flavour, Sonnet TTFS p90.

Persona follow-ups (not in this round): niece example "다들 같이요" contradicts her own known fact;
butler example 2 and deflection 2 are themselves can't-say lines.
