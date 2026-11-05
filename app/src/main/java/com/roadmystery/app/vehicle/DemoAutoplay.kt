package com.roadmystery.app.vehicle

import com.roadmystery.app.story.StoryEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 시연용 이벤트 순서를 FakeVehicleState에 자동으로 넣는다.
 * 시간 대신 엔진 상태(열린 단서 수, 재생 중인 장면)를 기다리므로 낭독 속도와 상관없이 같은 흐름이 나온다.
 * 범인 지목은 발표자가 직접 누르도록 남겨 둔다.
 */
class DemoAutoplay(
    private val vehicle: FakeVehicleState,
    private val engine: StoryEngine,
    private val stopViewMs: Long = 6_000,
) {
    private val _step = MutableStateFlow<String?>(null)

    /** 지금 진행 중인 단계 설명. null이면 멈춤. */
    val step: StateFlow<String?> = _step.asStateFlow()

    suspend fun run(scenarioId: String) {
        try {
            step("사건 시작")
            engine.start(scenarioId)
            delay(1_500)

            step("출발")
            vehicle.send(VehicleEvent.Driving)
            awaitClues(1)

            step("신호 대기 — 첫 단서 확인")
            vehicle.send(VehicleEvent.Stopped)
            delay(stopViewMs)

            step("출발 후 터널 진입")
            vehicle.send(VehicleEvent.Driving)
            delay(1_500)
            vehicle.send(VehicleEvent.Place("tunnel"))
            awaitScene("tunnel_flashback")
            awaitClues(2)

            step("급제동 — 낭독 정지")
            vehicle.send(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
            delay(1_500)
            vehicle.send(VehicleEvent.Stopped)
            delay(stopViewMs)

            step("다시 출발 — 멈춘 문장부터 재개")
            vehicle.send(VehicleEvent.Driving)
            awaitClues(4)

            step("도착 1분 전 — 선택 장면 생략")
            vehicle.send(VehicleEvent.EtaChanged(remainingSec = 60))
            awaitClues(5)

            step("목적지 도착 — 범인을 지목하세요")
            vehicle.send(VehicleEvent.Arrived)
        } finally {
            _step.value = null
        }
    }

    private fun step(label: String) {
        _step.value = label
    }

    private suspend fun awaitClues(count: Int) {
        withTimeoutOrNull(WAIT_LIMIT_MS) { engine.clues.first { it.size >= count } }
    }

    private suspend fun awaitScene(sceneId: String) {
        withTimeoutOrNull(WAIT_LIMIT_MS) { engine.nowPlaying.first { it?.sceneId == sceneId } }
    }

    private companion object {
        /** 한 단계가 이 시간보다 길면 다음으로 넘어간다(시연이 멈추지 않게). */
        const val WAIT_LIMIT_MS = 120_000L
    }
}
