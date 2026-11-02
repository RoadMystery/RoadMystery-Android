package com.roadmystery.app.director

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import android.util.Log
import kotlinx.coroutines.flow.StateFlow

/**
 * Vehicle SDK의 무드등으로 연출한다. 제어 권한이 없거나 실패하면 효과음(fallback)만 낸다.
 * StoryEngine은 Director 인터페이스만 알고, 이 클래스는 MainActivity에서 조립한다.
 */
class PleosDirector(
    private val vehicle: Vehicle,
    private val fallback: Director = ToneDirector(),
) : Director {
    override val lastEffect: StateFlow<Effect?> = fallback.lastEffect

    @Volatile private var lightAvailable = true
    private val cabin = SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)

    override fun perform(effect: Effect) {
        fallback.perform(effect)
        if (!lightAvailable) return
        runCatching {
            vehicle.getLight().setAmbientLightBrightnessColor(
                cabin,
                effect.ambientColor,
                { _, _ -> },
                { error -> disableLight(error) },
            )
        }.onFailure { disableLight(it) }
    }

    private fun disableLight(error: Throwable) {
        lightAvailable = false
        Log.w(TAG, "무드등 제어 불가, 효과음만 사용: ${error.message}")
    }

    private companion object {
        const val TAG = "PleosDirector"
    }
}
