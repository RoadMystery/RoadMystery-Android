package dev.voiceagent.story

import dev.voiceagent.text.normalizeForMatch
import dev.voiceagent.text.pythonSplit

/** What the player said in the pause after a scene, as [StoryVoice.readWindow] reads it. */
sealed interface WindowTurn {
    /** Nothing to ask: silence, or a no ("없어요", "계속해 주세요"). The story goes on. */
    data object Continue : WindowTurn

    /** A question for [suspectId]: StoryEngine.ask(suspectId, question), then the story goes on. */
    data class Ask(val suspectId: String, val question: String) : WindowTurn

    /**
     * A question naming no suspect, with none asked before: the narrator asks whom ([StoryVoice.askWhoLine])
     * and the next pause can take the answer, or the story goes on.
     */
    data class AskWho(val question: String) : WindowTurn
}

/**
 * Telling a question from a no in the question pause. An utterance is no question when every word of it is
 * one of [NO_QUESTION_WORDS] (punctuation and spacing ignored), so "아니요, 괜찮아요" lets the story go on while
 * "아니, 서진 씨 그때 어디 있었어요?" is a question to 박서진.
 */
internal object QuestionWindow {
    /** Saying there is nothing to ask, or to go on, and the words that come with it. */
    private val NO_QUESTION_WORDS = setOf(
        "없어", "없어요", "없습니다", "없음", "없네", "없네요", "질문",
        "아니", "아니요", "아뇨", "아니에요", "아니야",
        "괜찮아", "괜찮아요", "괜찮습니다", "됐어", "됐어요", "됐습니다",
        "계속", "계속해", "계속해요", "계속해줘", "계속해주세요", "들려줘", "들려줘요", "들려주세요",
        "넘어가", "넘어가요", "넘어가자", "넘어가줘", "다음", "다음으로", "패스",
        "진행", "진행해", "진행해요", "진행해줘", "진행해주세요", "그냥", "이제", "주세요", "줘", "줘요",
    )

    fun isNoQuestion(utterance: String): Boolean {
        val words = pythonSplit(utterance).map(::normalizeForMatch).filter { it.isNotEmpty() }
        return words.all { it in NO_QUESTION_WORDS }
    }
}
