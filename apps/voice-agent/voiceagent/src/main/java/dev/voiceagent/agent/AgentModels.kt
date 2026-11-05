package dev.voiceagent.agent

import dev.voiceagent.content.Case
import dev.voiceagent.content.Clue
import dev.voiceagent.content.InvestigationStage
import dev.voiceagent.content.Suspect
import dev.voiceagent.llm.ProxyContract
import dev.voiceagent.prompt.KoreanParticles
import dev.voiceagent.prompt.PromptVariant
import dev.voiceagent.prompt.Testimony

data class AgentConfig(
    /** Prompt variant sent to the LLM; choose it from the tools/voice-eval comparison. */
    val variant: PromptVariant = PromptVariant.COVER,
    /** Pleos speaker of the narrator (진행자); suspects speak in their persona's voice. */
    val narratorSpeaker: String = "haeun",
    /** The filler ("음...") plays when no sentence is ready by then (비기능 요구사항: 0.3초). */
    val fillerDelayMs: Long = 300,
    /** Without any text by then the request is dropped and the fallback line is spoken. */
    val firstTextTimeoutMs: Long = 8_000,
    /** Upper bound for a whole answer, like the proxy's 30 s upstream timeout. */
    val answerTimeoutMs: Long = 30_000,
    /** Up to two short openers ("하, 참.", "맨발이요?") at the start do not count (text/Sentences.kt). */
    val maxAnswerSentences: Int = 3,
    /** About 30 seconds of speech (F-06). */
    val maxSummarySentences: Int = 5,
    /** Earlier exchanges with the same suspect sent along with a new question. */
    val historyExchanges: Int = MAX_HISTORY_EXCHANGES,
    /**
     * Adds the suspect's pressure line ([Suspect.pressure]) to the prompt from
     * [InvestigationStage.PRESSING] on. Off until measured (see docs/voice-agent/investigation-progress.md):
     * the golden file pins how the prompt renders with the lines, but how the LLM answers them is not
     * measured yet.
     */
    val pressureLines: Boolean = false,
) {
    init {
        require(historyExchanges in 0..MAX_HISTORY_EXCHANGES) { "historyExchanges must be 0..$MAX_HISTORY_EXCHANGES" }
        require(maxAnswerSentences > 0 && maxSummarySentences > 0) { "sentence limits must be positive" }
        require(fillerDelayMs >= 0 && firstTextTimeoutMs in 1..answerTimeoutMs) { "invalid timeouts" }
    }

    companion object {
        /** Ten earlier exchanges plus the new question fill the proxy's 21 messages. */
        const val MAX_HISTORY_EXCHANGES = (ProxyContract.MAX_MESSAGES - 1) / 2
    }
}

data class AgentState(
    /** The suspect being questioned; a question without an addressee goes to them. */
    val currentSuspect: String? = null,
    val unlockedClues: Set<String> = emptySet(),
    /** What suspects have said, in order: conversation history and the F-06 수사 수첩. */
    val notebook: List<Testimony> = emptyList(),
    /** Counts [VoiceAgent.reset]s and [VoiceAgent.restore]s, so a turn they cut off cannot write into the new game. */
    val game: Int = 0,
    /**
     * Ids of the key reactions drawn out: the player heard the suspect answer a question bringing up
     * their clue ([Investigation.isAnswer]).
     */
    val confronted: Set<String> = emptySet(),
    /** 수사 진척도 from [unlockedClues] and [confronted] ([Investigation.stage]); never shown to the player. */
    val stage: InvestigationStage = InvestigationStage.OPENING,
    /**
     * The narrator's line (a hint or the nudge) if the latest turn the suspect answered ended with one,
     * so an answer-seeking question does not get the same hint on two such turns in a row. A turn the
     * suspect answered without a narrator line clears it; other turns leave it, and a line cut off by
     * barge-in is not recorded.
     */
    val lastHint: String? = null,
    /**
     * The narrator has nudged the player toward the accusation in this game: once, on the first
     * question turn that ends at READY. A nudge cut off by barge-in does not count and comes again.
     */
    val nudged: Boolean = false,
)

enum class ReplyKind {
    /** The suspect answered; the answer may have been cut at the spoiler guard or sentence limit. */
    ANSWER,

    /**
     * Nothing of the answer could be spoken (its first sentence read out instructions or leaked), so
     * one of the suspect's deflections was, and no narrator line followed.
     */
    DEFLECTION,

    /**
     * The suspect's answer or deflection was followed by a narrator line ([AgentReply.narratorLine]):
     * a hint, for a leak the spoiler guard cut or after an answer-seeking question, or the nudge toward
     * the accusation. Narrator lines are never recorded as testimony.
     */
    HINT,

    /** No answer came (offline, error, timeout), so the suspect's fallback line was spoken. */
    FALLBACK,

    /** Nobody was addressed and nobody is being questioned, so the narrator asked whom to ask. */
    ASK_WHO,

    /** The utterance was empty, so the player was asked to say it again. */
    NOT_UNDERSTOOD,

    /** The narrator summarized the notebook. */
    SUMMARY,

    /** The notebook was empty, so the narrator replayed the case overview without an LLM. */
    OVERVIEW,

    /** The summary failed, so the narrator read the titles of the clues found. */
    SUMMARY_FALLBACK,

    /** [VoiceAgent.stop] or a newer request cut this turn off. */
    INTERRUPTED,
}

data class AgentReply(
    val kind: ReplyKind,
    /** The suspect who spoke, or null when the narrator spoke alone. */
    val suspectId: String?,
    /** Lines handed to speech output, in order, without the filler. */
    val sentences: List<String>,
    val fillerPlayed: Boolean = false,
    /** From sending the request to the first LLM text; null when no text came. */
    val firstTextMs: Long? = null,
    /** Why the LLM gave no complete answer (an [dev.voiceagent.llm.LlmEvent.Failure] code or a timeout). */
    val failure: String? = null,
    /** Set when the TTS engine failed; the text can still be shown on screen. */
    val speechError: String? = null,
    /**
     * The narrator's line after the suspect's words: the one that ended a [ReplyKind.HINT] turn, or
     * one a barge-in cut off ([ReplyKind.INTERRUPTED]). It is also the last of [sentences].
     */
    val narratorLine: String? = null,
) {
    /** Everything spoken, the narrator's line included: for subtitles, never for testimony. */
    val text: String get() = sentences.joinToString(" ")

    /**
     * What the suspect said: [sentences] without the narrator's line, "" when the narrator spoke alone
     * ([suspectId] null). As testimony StoryEngine must store [AgentState.notebook] or this, never
     * [text], which ends with the narrator's hint on a HINT turn. (The notebook also leaves out
     * [ReplyKind.FALLBACK] and [ReplyKind.NOT_UNDERSTOOD] lines.)
     */
    val suspectText: String
        get() {
            if (suspectId == null) return ""
            val own = if (narratorLine != null && sentences.lastOrNull() == narratorLine) sentences.dropLast(1) else sentences
            return own.joinToString(" ")
        }
}

/**
 * Fixed narrator (진행자) lines, spoken without an LLM. They skip the spoiler guard, so what they quote
 * must be public by then: a confront hint names only a clue already found, and no suspect.
 */
object NarratorLines {
    const val NOT_UNDERSTOOD = "잘 듣지 못했어요. 다시 한번 말씀해 주세요."
    const val SUMMARY_FILLER = "수첩을 펼쳐 볼게요."

    /**
     * Once a game, at the end of the first question turn that ends at [InvestigationStage.READY]. It
     * only says the evidence is in: StoryEngine takes the accusation after arrival, so "accuse now"
     * would promise a choice the player does not have yet.
     */
    const val ACCUSE_NUDGE = "탐정님, 이제 범인을 가려낼 단서와 증언이 다 모인 것 같아요."

    fun askWho(case: Case): String {
        val suspects = case.suspects.joinToString(", ") { "${it.role} ${it.name}" }
        return "누구에게 물어볼까요? $suspects 중에서 골라 주세요."
    }

    /**
     * Points at a key reaction within reach by [clue], the last one it needs, without saying whose:
     * naming the suspect gave the culprit away whenever theirs was the reaction left.
     */
    fun confrontHint(clue: Clue): String = "탐정님, ${clue.title} 이야기를 아직 물어보지 않은 사람이 있어요."

    /**
     * The narrator's hint, for a leak the spoiler guard cut or after an answer-seeking question. First
     * the key reaction within reach that the story offered first ([Investigation.pendingKeyReactions]):
     * clues arrive with the story scenes, so "find the next clue" is nothing the player can act on,
     * while a reaction they can draw out now is. Else, while some clue is not found, the hint of the
     * first such clue that has one, whatever a cut answer was about to say: following the word that
     * leaked would confirm it. Once every clue is found and no key reaction is pending, the case's own.
     * Null when there is none to give, so a case without hints keeps its deflections.
     * [tried]: key reactions the question just went after without drawing them out, which the hint
     * must not suggest straight back; with every clue found and only they pending, there is nothing
     * to say.
     */
    fun hint(
        case: Case,
        suspects: Collection<Suspect>,
        unlocked: Set<String>,
        confronted: Set<String>,
        tried: Set<String> = emptySet(),
    ): String? {
        val pending = Investigation.pendingKeyReactions(case, suspects, unlocked, confronted)
        val confront = pending.filter { (_, fact) -> fact.id !in tried }.firstNotNullOfOrNull { (_, fact) ->
            case.clues.lastOrNull { it.id in fact.unlockedBy }?.let { confrontHint(it) }
        }
        if (confront != null) return confront
        val locked = case.clues.filter { it.id !in unlocked }
        if (locked.isNotEmpty()) return locked.firstNotNullOfOrNull { it.hint }
        // The case's hint is for an investigation that is done, not one waiting on the reaction just tried.
        return if (pending.isEmpty()) case.spoilerHint else null
    }

    fun offlineSummary(case: Case, unlocked: Set<String>): String {
        val titles = case.clues.filter { it.id in unlocked }.map { it.title }
        val clues = if (titles.isEmpty()) "" else " 지금까지 찾은 단서는 ${KoreanParticles.withCopula(titles.joinToString(", "))}."
        return "지금은 수첩을 자세히 정리해 드리기 어려워요.$clues 잠시 뒤에 다시 말씀해 주세요."
    }
}
