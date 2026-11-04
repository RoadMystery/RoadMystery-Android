# v3: an in-character way out when the character's facts do not cover the question

Step 4.5 categorization of the 10 haiku-5-5-low train failures after v2 (train transcripts only):

- Artifact gap, 7 rows
  - invents a plausible fact the character would know but the prompt does not list, 3: butler/locked-key x2
    ("저장고 열쇠는 제가 늘 허리춤에 차고 다닙니다", also a locked-clue leak), butler/locked-gloves x1
    ("장갑을 끼지 않았습니다. 손을 자주 씻었거든요");
  - can't-tell-you refusal copied from the butler's own lines ("제 입으로 말씀드리기 어렵습니다",
    "답을 드릴 처지가 아닌"), 3: butler/ask-culprit x2, butler/violence-bait x1;
  - off-topic question answered with "don't know" and no turn back to the case, 1: butler/off-topic-weather.
- Grader variance, 3 rows: niece/gloves-reaction (same text as rows graded good), niece/saw-butler (flat),
  niece/pawn-reaction (opener split by a period; flips between reps).

Sonnet's 4 train failures: butler/locked-key x2 answer<4 ("열쇠 일은 제가 아는 바가 없습니다", judged implausible
for a 35-year butler), the same gap from the other side; niece x2 char<4 (flat, variance).

Hypothesis: for case matters it does not list, the prompt offers only "say you don't know". Where that is
implausible for the character (the butler and the house keys) Haiku invents a plausible answer and Sonnet gives an
implausible "don't know"; for questions it must not answer, the persona's own example lines model a refusal.

Change (v3-change.patch):
- answer-directly bullet: "아는 만큼" becomes "네가 말할 수 있는 사실로" (v2's wording pushed the key answer);
- fact-limit line: unlisted case matters stay unsaid even if the character would plausibly know them; when
  "don't know" would be awkward, turn the talk the way the character's personality does, toward what it can say;
- butler persona: speech example 2 and deflection 2 were can't-say lines; now "저는 그저 이 댁 살림을 맡아 온
  사람일 뿐입니다" and "그보다는 이 댁에서 있었던 일을 여쭤 주십시오, 탐정님".

Expected: haiku train 60 -> 64-66 of 70 (clearing the ~67 noise line needs nearly all 7 artifact-gap rows to
flip); sonnet locked-key answer<4 2 -> 0. Watch: evasion creeping back on direct questions (v1's problem),
locked-key leak back to 0, haiku test >= 27, TTFS p90.

Stop rule: if haiku train does not clear noise, the loop ends here (test is saturated, see state.json
reframe_after_v2).

Open persona follow-up: niece example "정전 때요? 식당에 있었죠, 다들 같이요." contradicts the case (the butler was
in the cellar and the cook in the kitchen during the blackout).
