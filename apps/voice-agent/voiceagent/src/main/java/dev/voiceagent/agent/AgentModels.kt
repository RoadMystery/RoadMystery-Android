package dev.voiceagent.agent

import dev.voiceagent.content.Case
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
    /** Counts [VoiceAgent.reset]s, so a turn cut off by a reset cannot write into the new game. */
    val game: Int = 0,
)

enum class ReplyKind {
    /** The suspect answered; the answer may have been cut at the spoiler guard or sentence limit. */
    ANSWER,

    /** The spoiler guard caught the first sentence, so one of the suspect's deflections was spoken. */
    DEFLECTION,

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
    /** The suspect who spoke, or null for the narrator. */
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
) {
    val text: String get() = sentences.joinToString(" ")
}

/** Fixed narrator (진행자) lines, spoken without an LLM. */
object NarratorLines {
    const val NOT_UNDERSTOOD = "잘 듣지 못했어요. 다시 한번 말씀해 주세요."
    const val SUMMARY_FILLER = "수첩을 펼쳐 볼게요."

    fun askWho(case: Case): String {
        val suspects = case.suspects.joinToString(", ") { "${it.role} ${it.name}" }
        return "누구에게 물어볼까요? $suspects 중에서 골라 주세요."
    }

    fun offlineSummary(case: Case, unlocked: Set<String>): String {
        val titles = case.clues.filter { it.id in unlocked }.map { it.title }
        val clues = if (titles.isEmpty()) "" else " 지금까지 찾은 단서는 ${KoreanParticles.withCopula(titles.joinToString(", "))}."
        return "지금은 수첩을 자세히 정리해 드리기 어려워요.$clues 잠시 뒤에 다시 말씀해 주세요."
    }
}
