package dev.voiceagent.agent

import dev.voiceagent.content.Case
import dev.voiceagent.content.Clue
import dev.voiceagent.content.InvestigationStage
import dev.voiceagent.content.Suspect
import dev.voiceagent.content.UnlockableFact
import dev.voiceagent.text.countedSentences
import dev.voiceagent.text.normalizeForMatch
import dev.voiceagent.text.phraseHits

/**
 * 수사 진척도, never shown to the player: how far the investigation has come from the clues found,
 * which arrive with the story, and the key reactions drawn out of the suspects, which the player's
 * questions earn. VoiceAgent keeps it in [AgentState.stage]; the narrator's hints and the nudge to
 * accuse follow it, and so do the suspects' pressure lines when [AgentConfig.pressureLines] is on.
 * Questions are matched like the spoiler guard: normalized, so spacing and punctuation do not matter.
 */
object Investigation {
    /**
     * Words of a question that asks for the answer instead of investigating: who did it, a jailbreak
     * for the culprit, an accusation naming 범인. With the "누가 … 훔쳤" pattern of [isAnswerSeeking]
     * they catch 13 of the 124 stored probe questions, none by mistake. They are calibrated on those
     * questions only, so a player's own wording can still slip past. An accusation without 범인
     * ("옥분 씨가 화관 가져간 거죠?") or a question about where the stones went is left alone: the
     * player is deducing, and the narrator should not talk over it. Some words are out on purpose:
     * "정답" alone is in "정답게", "누가 손댄" in "서랍에 누가 손댄 흔적", "누가 그런" in "누가 그런 말을 했어요?".
     */
    val ANSWER_SEEKING = listOf(
        "범인", "진범", "정답을", "정답이", "정답은", "정답 알려", "정답 말해", "누구 짓", "누구 소행", "도둑이 누구", "누가 그랬",
    )

    /**
     * "누가" and, within four syllables, taking the crown: words may come between ("누가 화관을 훔쳤어요?")
     * and the recognizer may spell it out ("누가 가져가써요"). Matched against the normalized question.
     */
    private val WHO_TOOK_IT = Regex("누가.{0,4}(훔쳤|훔친|훔쳐|가져갔|가져간|가져가|손댔)")

    /** The clues, in case order, that [question] brings up by a keyword, the title or a mention. */
    fun mentionedClues(case: Case, question: String): List<Clue> =
        case.clues.filter { phraseHits(question, it.keywords + it.title + it.mentions).isNotEmpty() }

    /**
     * Ids of [suspect]'s key reactions that [question] goes after: all of their clues are in [unlocked],
     * so the suspect's prompt has the reaction, and the question brings up one of those clues. They are
     * drawn out once the player hears the suspect answer it ([isAnswer]).
     */
    fun drawnOut(case: Case, suspect: Suspect, unlocked: Set<String>, question: String): Set<String> {
        val raised = mentionedClues(case, question).map { it.id }.toSet()
        return suspect.unlockable
            .filter { it.key && unlocked.containsAll(it.unlockedBy) && it.unlockedBy.any(raised::contains) }
            .map { it.id }
            .toSet()
    }

    /**
     * Whether [heard], what the player heard [suspect] say, answers the question at all: a counted
     * sentence, not only openers like "하, 참.", and not the suspect's line for a question it did not
     * understand, which the prompt has it say word for word (matched normalized all the same).
     */
    fun isAnswer(suspect: Suspect, heard: List<String>): Boolean =
        countedSentences(heard) > 0 && phraseHits(heard.joinToString(" "), listOf(suspect.fallbackNotUnderstood)).isEmpty()

    /** [confronted]: the key reactions drawn out. A case without key reactions is READY once every clue is found. */
    fun stage(case: Case, suspects: Collection<Suspect>, unlocked: Set<String>, confronted: Set<String>): InvestigationStage {
        val found = case.clues.count { it.id in unlocked }
        return when {
            found == 0 -> InvestigationStage.OPENING
            found < case.clues.size -> InvestigationStage.GATHERING
            keyReactions(suspects).all { (_, fact) -> fact.id in confronted } -> InvestigationStage.READY
            else -> InvestigationStage.PRESSING
        }
    }

    /**
     * Key reactions within reach (all their clues found) but not drawn out yet, in the order the story
     * makes them available: by the case order of the last clue they need, then by cast order.
     */
    fun pendingKeyReactions(
        case: Case,
        suspects: Collection<Suspect>,
        unlocked: Set<String>,
        confronted: Set<String>,
    ): List<Pair<Suspect, UnlockableFact>> {
        val cast = case.cast.map { it.id }
        return keyReactions(suspects)
            .filter { (_, fact) -> fact.id !in confronted && unlocked.containsAll(fact.unlockedBy) }
            .sortedWith(
                compareBy<Pair<Suspect, UnlockableFact>>(
                    { (_, fact) -> case.clues.indexOfLast { it.id in fact.unlockedBy } },
                    { (suspect, _) -> cast.indexOf(suspect.id) },
                ),
            )
    }

    /** [ANSWER_SEEKING] words, or "누가" with taking the crown a few syllables on ("화관 누가 훔쳐 갔어요?"). */
    fun isAnswerSeeking(question: String): Boolean =
        phraseHits(question, ANSWER_SEEKING).isNotEmpty() || WHO_TOOK_IT.containsMatchIn(normalizeForMatch(question))

    private fun keyReactions(suspects: Collection<Suspect>): List<Pair<Suspect, UnlockableFact>> =
        suspects.flatMap { suspect -> suspect.unlockable.filter { it.key }.map { suspect to it } }
}
