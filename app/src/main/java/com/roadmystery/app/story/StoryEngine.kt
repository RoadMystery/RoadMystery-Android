package com.roadmystery.app.story

import com.roadmystery.app.vehicle.VehicleEvent
import kotlinx.coroutines.flow.StateFlow

interface StoryEngine {
    val mode: StateFlow<AppMode>
    val scenario: StateFlow<Scenario?>

    /** 지금까지 찾은 단서. */
    val clues: StateFlow<List<Clue>>

    /** 마지막 정차 이후 새로 찾은 단서. 정차 화면에서 강조하고, 다시 출발하면 비운다. */
    val pendingClues: StateFlow<List<Clue>>
    val nowPlaying: StateFlow<NowPlaying?>

    /** 본편 진행률 0..1 */
    val progress: StateFlow<Float>
    val outcome: StateFlow<Outcome?>

    /** 차가 멈춰 있어 선택·심문 같은 조작을 받을 수 있는지. 판정할 수 없으면 false. */
    val canInteract: StateFlow<Boolean>
    val testimonies: StateFlow<List<Testimony>>

    /** 진행 중인 심문 또는 수첩 요약. */
    val interrogation: StateFlow<Interrogation?>

    /** 저장용 진행 상황. 바뀔 때마다 갱신된다. */
    val snapshot: StateFlow<Progress?>

    fun start(
        scenarioId: String,
        resume: Progress? = null,
    )

    fun onVehicleEvent(event: VehicleEvent)

    fun choose(
        decisionId: String,
        optionId: String,
    )

    /** 정차 중에만 받는다. 낭독을 잠시 멈추고 용의자의 대답을 들려준 뒤 이어서 낭독한다. */
    fun ask(
        suspectId: String,
        utterance: String,
    )

    /**
     * 주행 중 본편 장면 끝의 질문 구간에서 들은 질문. 장면의 마지막 문장을 읽은 뒤에만 받는다.
     * 그 장면은 끝난 것으로 치고, 대답을 읽은 뒤 다음 장면으로 간다. 위험 정지는 심문을 끊는다.
     */
    fun askBetweenScenes(
        suspectId: String,
        utterance: String,
    )

    /**
     * 정차 중 "음성으로 묻기"로 듣기 시작한다. 마이크가 낭독을 듣지 않게, 듣는 동안 낭독을 멈춘다.
     * 지금은 질문을 받을 수 없으면(주행 중, 위험으로 멈춘 중, 대답을 기다리는 중) false를 돌려주고 아무것도 하지 않는다.
     */
    fun beginListening(): Boolean = true

    /** 듣기가 끝났다. 질문이 이어지지 않았으면 멈춘 문장부터 낭독을 잇는다. */
    fun endListening() {}

    /** 정차 중에만 받는다. 지금까지의 단서·증언 요약을 읽어 준다. */
    fun requestSummary()

    companion object {
        /** 범인 지목. optionId는 용의자 id. */
        const val DECISION_ACCUSE = "accuse"
    }
}

data class NowPlaying(
    val sceneId: String,
    val title: String,
    val sentenceIndex: Int,
    val sentenceCount: Int,
    val sentence: String?,
)

data class Outcome(
    val accusedId: String,
    val culpritId: String,
    val correct: Boolean,
)

/** suspectId가 null이면 수첩 요약. answer가 null이면 대답을 만드는 중. */
data class Interrogation(
    val suspectId: String?,
    val question: String,
    val answer: String? = null,
)
