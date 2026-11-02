package com.roadmystery.app.story

import kotlinx.serialization.Serializable

@Serializable
data class Scenario(
    val id: String,
    val title: String,
    val culprit: String,
    val suspects: List<Suspect>,
    val clues: List<Clue>,
    val chapters: List<Chapter>,
) {
    fun clue(id: String): Clue? = clues.firstOrNull { it.id == id }

    fun suspect(id: String): Suspect? = suspects.firstOrNull { it.id == id }
}

@Serializable
data class Suspect(
    val id: String,
    val name: String,
    val role: String,
    val voice: String,
    val persona: Persona,
    /** 심문 답변으로 공개해도 되는 단서 id. */
    val revealable: List<String> = emptyList(),
)

/** VoiceAgent(LLM)에 넘길 인물 설정. 범인·트릭은 여기에 넣지 않는다. */
@Serializable
data class Persona(
    val tone: String,
    val alibi: String,
    val secret: String,
)

@Serializable
data class Chapter(
    val id: String,
    val title: String,
    val scenes: List<Scene>,
) {
    /** trigger가 없는 장면이 순서대로 재생되는 본편이다. */
    val mainScenes: List<Scene> get() = scenes.filter { it.trigger == null }

    fun sceneFor(trigger: String): Scene? = scenes.firstOrNull { it.trigger == trigger }
}

@Serializable
data class Scene(
    val id: String,
    val title: String,
    /** false면 남은 도착 시간에 따라 빠질 수 있다. */
    val required: Boolean = true,
    val durationSec: Int,
    /** 문장 단위로 나눈 대본. 일시정지·재개가 문장 단위로 이뤄진다. */
    val script: List<String>,
    val unlocks: List<String> = emptyList(),
    /** null이면 본편. 그 외에는 SceneTrigger 또는 VehicleEvent.Place 태그. */
    val trigger: String? = null,
)

object SceneTrigger {
    const val BRIEFING = "briefing"
    const val ENDING_CORRECT = "ending_correct"
    const val ENDING_WRONG = "ending_wrong"

    val endings = setOf(ENDING_CORRECT, ENDING_WRONG)
}
