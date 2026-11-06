package com.roadmystery.app.story

import kotlinx.serialization.Serializable

@Serializable
data class Scenario(
    val id: String,
    val title: String,
    val culprit: String,
    val victim: Victim,
    val suspects: List<Suspect>,
    val clues: List<Clue>,
    val chapters: List<Chapter>,
) {
    fun clue(id: String): Clue? = clues.firstOrNull { it.id == id }

    fun suspect(id: String): Suspect? = suspects.firstOrNull { it.id == id }
}

@Serializable
data class Victim(
    val name: String,
    val role: String,
)

@Serializable
data class Suspect(
    val id: String,
    val name: String,
    val role: String,
    /** 인물 관계도에 표시할 피해자와의 관계. 숨긴 사실은 넣지 않는다. */
    val relation: String,
    val voice: String,
    val persona: Persona,
    /** 심문 답변으로 공개해도 되는 단서 id. */
    val revealable: List<String> = emptyList(),
    val interrogation: List<Answer> = emptyList(),
    /** 어떤 질문에도 해당하지 않을 때 돌아가며 쓰는 대답. */
    val fallback: List<String> = emptyList(),
)

/** 용의자 답변 하나. 키워드가 질문에 들어 있으면 이 답을 한다. */
@Serializable
data class Answer(
    val id: String,
    /** 정차 화면에 버튼으로 보여줄 대표 질문. */
    val question: String,
    val keywords: List<String>,
    val text: String,
    val reveals: List<String> = emptyList(),
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
    /** 장면이 시작될 때 Director에 넘길 연출. Effect 이름(소문자). */
    val effect: String? = null,
    /** TTS 화자. 기존 대본은 진행자가 읽고, 심문 대답은 용의자의 voice를 사용한다. */
    val voice: String = "narrator",
    /** 필요할 때 문장별 화자를 지정한다. 비어 있으면 장면 voice를 사용한다. */
    val sentenceVoices: List<String> = emptyList(),
)

object SceneTrigger {
    const val BRIEFING = "briefing"
    const val ENDING_CORRECT = "ending_correct"
    const val ENDING_WRONG = "ending_wrong"

    /** 엔진이 만드는 심문 대답·수첩 요약 장면. 시나리오 파일에는 쓰지 않는다. */
    const val REPLY = "reply"

    val endings = setOf(ENDING_CORRECT, ENDING_WRONG)
}
