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
