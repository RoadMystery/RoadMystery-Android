package com.roadmystery.app.story

import com.roadmystery.app.vehicle.VehicleEvent
import com.roadmystery.app.vehicle.VehicleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * StoryEngine이 나오기 전까지 쓰는 임시 상태 머신.
 * 차량 SDK를 모르고 VehicleEvent만 받는다. UI는 StateFlow를 구독만 한다.
 */
class GameState(
    private val clues: List<Clue> = SampleClues.all,
) {
    val sceneTitle: String = "1장 · 심야 휴게소의 마지막 손님"

    private val _mode = MutableStateFlow(AppMode.IDLE)
    val mode: StateFlow<AppMode> = _mode.asStateFlow()

    private val _unlockedClues = MutableStateFlow<List<Clue>>(emptyList())
    val unlockedClues: StateFlow<List<Clue>> = _unlockedClues.asStateFlow()

    /** 이번 정차에서 새로 열린 단서 id. 다시 출발하면 비운다. */
    private val _newClueId = MutableStateFlow<String?>(null)
    val newClueId: StateFlow<String?> = _newClueId.asStateFlow()

    /** 마지막으로 받은 장소 태그 (예: "tunnel"). */
    private val _place = MutableStateFlow<String?>(null)
    val place: StateFlow<String?> = _place.asStateFlow()

    val totalClues: Int get() = clues.size

    fun bind(
        vehicle: VehicleState,
        scope: CoroutineScope,
    ): Job = scope.launch { vehicle.events.collect { onVehicleEvent(it) } }

    fun onVehicleEvent(event: VehicleEvent) {
        if (_mode.value == AppMode.ENDING) return

        when (event) {
            VehicleEvent.Driving -> {
                _newClueId.value = null
                _mode.value = AppMode.DRIVING
            }
            VehicleEvent.Stopped, VehicleEvent.Parked -> {
                // 이미 정차 중이면 단서를 또 열지 않는다.
                if (_mode.value != AppMode.STOPPED) unlockNextClue()
                _mode.value = AppMode.STOPPED
            }
            is VehicleEvent.Hazard -> _mode.value = AppMode.PAUSED
            VehicleEvent.Arrived -> _mode.value = AppMode.ENDING
            is VehicleEvent.Place -> _place.value = event.tag
            is VehicleEvent.EtaChanged -> Unit
        }
    }

    private fun unlockNextClue() {
        val next = clues.getOrNull(_unlockedClues.value.size) ?: return
        _unlockedClues.value = _unlockedClues.value + next
        _newClueId.value = next.id
    }
}
