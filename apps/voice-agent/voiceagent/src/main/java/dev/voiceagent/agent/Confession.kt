package dev.voiceagent.agent

import dev.voiceagent.content.Case
import dev.voiceagent.content.InvestigationStage
import dev.voiceagent.content.Suspect
import dev.voiceagent.prompt.PromptVariant

/**
 * When the culprit's confession joins its prompt (docs/voice-agent/game-rules.md), as voice_eval.prompt
 * .confession_opens decides it: in [PromptVariant.AWARE], once every clue is found and every key reaction
 * drawn out ([InvestigationStage.READY]), on a question that brings up a clue unlocking one of the culprit's
 * key reactions, the clue that broke its cover story. Whether that question accuses it is the model's to
 * judge. VoiceAgent keeps the confession in the prompt from the first such turn the player heard answered
 * ([AgentState.cornered]), and does not hold that answer to the forbidden phrases: it is meant to give the
 * culprit away.
 */
object Confession {
    /** voice_eval.prompt.key_clue_ids: the clues that unlock [suspect]'s key reactions. */
    fun keyClueIds(suspect: Suspect): Set<String> = suspect.unlockable.filter { it.key }.flatMap { it.unlockedBy }.toSet()

    /** Whether [suspect] can confess at all: the culprit, with a confession line, in [variant]. */
    fun canConfess(case: Case, suspect: Suspect, variant: PromptVariant): Boolean =
        variant == PromptVariant.AWARE && suspect.id == case.solution.culprit && suspect.confession != null

    /** [questions]: the player's turns to [suspect] at [stage], the one being answered among them. */
    fun opens(case: Case, suspect: Suspect, variant: PromptVariant, stage: InvestigationStage, questions: List<String>): Boolean {
        if (!canConfess(case, suspect, variant) || stage != InvestigationStage.READY) return false
        val cornering = keyClueIds(suspect)
        return questions.any { question -> Investigation.mentionedClues(case, question).any { it.id in cornering } }
    }
}
