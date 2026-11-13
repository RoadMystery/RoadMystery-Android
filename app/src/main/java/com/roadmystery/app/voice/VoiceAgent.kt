package com.roadmystery.app.voice

import com.roadmystery.app.story.Answer
import com.roadmystery.app.story.Progress
import com.roadmystery.app.story.Scenario
import com.roadmystery.app.story.Suspect

data class CaseContext(
    val scenario: Scenario,
    val suspect: Suspect,
    val foundClueIds: Set<String>,
    /** 이 용의자에게 이미 들은 답변 id. 같은 답을 반복하지 않는 데 쓴다. */
    val heardAnswerIds: Set<String>,
)

data class SuspectReply(
    val text: String,
    val answerId: String? = null,
    /** 이 답변으로 새로 공개되는 단서. suspect.revealable 안에서만 나온다. */
    val reveals: List<String> = emptyList(),
    /**
     * 증언으로 남길 답인지. 연결이 끊겨 준비된 말로 대신한 답처럼 사건에 대해 말한 것이 없는 답은 false로 준다.
     * 그런 답은 화면에 보이고 읽어 주지만, 증언 목록과 저장에는 남기지 않는다.
     */
    val isTestimony: Boolean = true,
)

interface VoiceAgent {
    suspend fun ask(
        utterance: String,
        context: CaseContext,
    ): SuspectReply

    suspend fun summarize(
        scenario: Scenario,
        progress: Progress,
    ): String
}

/**
 * 시나리오에 미리 써 둔 답변에서 키워드로 고른다.
 *
 * Gleo AI는 앱이 LLM을 직접 부르는 방식이 아니라 앱의 함수를 호출하는 방식(App Schema)이라,
 * 데모에서는 이 구현을 쓴다. 답변이 대본에서만 나오므로 범인·트릭이 새어 나가지 않는다.
 */
class ScriptedVoiceAgent : VoiceAgent {
    private val fallbackTurn = mutableMapOf<String, Int>()

    override suspend fun ask(
        utterance: String,
        context: CaseContext,
    ): SuspectReply {
        val suspect = context.suspect
        val normalized = utterance.replace(" ", "")
        val best =
            suspect.interrogation
                .map { answer -> answer to answer.keywords.count { normalized.contains(it.replace(" ", "")) } }
                .filter { (_, hits) -> hits > 0 }
                // 키워드가 많이 맞는 답, 같으면 아직 안 들은 답을 고른다.
                .maxWithOrNull(compareBy<Pair<Answer, Int>> { it.second }.thenBy { it.first.id !in context.heardAnswerIds })
                ?.first

        if (best != null) {
            return SuspectReply(
                text = best.text,
                answerId = best.id,
                reveals = best.reveals.filter { it in suspect.revealable && it !in context.foundClueIds },
            )
        }
        val turn = fallbackTurn.getOrDefault(suspect.id, 0)
        fallbackTurn[suspect.id] = turn + 1
        return SuspectReply(text = suspect.fallback[turn % suspect.fallback.size])
    }

    override suspend fun summarize(
        scenario: Scenario,
        progress: Progress,
    ): String {
        val clues = progress.cluesFound.mapNotNull { scenario.clue(it)?.title }
        if (clues.isEmpty() && progress.testimonies.isEmpty()) return "아직 수첩에 적힌 내용이 없습니다."

        val sentences = mutableListOf<String>()
        if (clues.isNotEmpty()) sentences += "지금까지 찾은 단서는 ${clues.size}개입니다. ${clues.joinToString(", ")}."
        progress.testimonies
            .groupBy { it.suspectId }
            .forEach { (suspectId, list) ->
                val name = scenario.suspect(suspectId)?.name ?: return@forEach
                sentences += "${name}에게서 ${list.size}개의 증언을 들었습니다. 마지막 증언은 이렇습니다. ${list.last().answer}"
            }
        val missing = scenario.clues.size - clues.size
        if (missing > 0) sentences += "아직 찾지 못한 단서가 ${missing}개 남아 있습니다."
        return sentences.joinToString(" ")
    }
}
