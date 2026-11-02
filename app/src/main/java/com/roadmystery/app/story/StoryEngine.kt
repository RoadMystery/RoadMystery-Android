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

    fun start(scenarioId: String)

    fun onVehicleEvent(event: VehicleEvent)

    fun choose(
        decisionId: String,
        optionId: String,
    )

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
