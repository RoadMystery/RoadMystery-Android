package com.roadmystery.app.vehicle

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/** Raw signals, not pre-decided mode events: the same classifier is exercised as on the car. */
class FakeMotionSignals(private val nowMs: () -> Long) {
    private val selected = MutableStateFlow(MotionSample(0f, null, false, false))
    private var frozen: MotionSample? = null
    private var freeze = false

    val samples = flow {
        emit(MotionSample())
        while (true) {
            val value = selected.value.copy(sampledAtMs = nowMs())
            emit(if (freeze) frozen ?: value.also { frozen = it } else value)
            delay(100)
        }
    }

    fun set(speedMps: Float?, parked: Boolean? = false, restricted: Boolean? = false) {
        freeze = false
        frozen = null
        selected.value = MotionSample(speedMps, null, parked, restricted)
    }

    fun freezeSpeed() {
        freeze = true
    }
}
