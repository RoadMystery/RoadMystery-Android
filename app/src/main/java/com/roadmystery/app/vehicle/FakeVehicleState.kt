package com.roadmystery.app.vehicle

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 시뮬레이션 패널에서 버튼으로 이벤트를 넣는 가짜 차량. */
class FakeVehicleState : VehicleState {
    private val _events = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<VehicleEvent> = _events.asSharedFlow()

    fun send(event: VehicleEvent) {
        _events.tryEmit(event)
    }
}
