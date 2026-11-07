package com.roadmystery.app.director

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import android.content.Context
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Vehicle SDK의 무드등으로 연출한다. 제어 권한이 없거나 실패하면 효과음(fallback)만 낸다.
 * StoryEngine은 Director 인터페이스만 알고, 이 클래스는 MainActivity에서 조립한다.
 */
class PleosDirector(context: Context) : Director {
    private val delegate = TunnelDirector(PleosCabinPort(context.applicationContext), ToneDirector())
    override val lastEffect = delegate.lastEffect
    val status = delegate.status
    override fun perform(effect: Effect) = delegate.perform(effect)
    override fun stop() = delegate.stop()
    override fun release() = delegate.release()
}

/** 전조등·안전 관련 조명·온도·제상·전원은 변경하지 않는다. */
private class PleosCabinPort(private val context: Context) : CabinPort {
    private var sdk: Vehicle? = null

    override suspend fun prepareTunnel(): List<CabinChange> {
        val vehicle = sdk ?: Vehicle(context).also {
            sdk = it
            it.initialize()
        }
        val light = vehicle.getLight()
        val hvac = vehicle.getHVAC()
        val lightAreas = awaitValue<Map<SeatArea, AreaBounds<Int>>> { ok, fail ->
            light.checkAmbientLightBrightnessCapabilities({ config, areas ->
                if (config?.accessType == AccessType.READ_WRITE) {
                    ok(areas)
                } else {
                    fail(IllegalStateException("무드등 읽기·쓰기 미지원"))
                }
            }, fail)
        }
        val fanAreas = awaitValue<Map<SeatArea, AreaBounds<Int>>> { ok, fail ->
            hvac.checkHvacFanSpeedCapabilities({ config, areas ->
                if (config?.accessType == AccessType.READ_WRITE) {
                    ok(areas)
                } else {
                    fail(IllegalStateException("송풍 읽기·쓰기 미지원"))
                }
            }, fail)
        }
        val lightEntry = lightAreas.entries.firstOrNull() ?: error("무드등 지원 구역 없음")
        val fanEntry = fanAreas.entries.firstOrNull() ?: error("송풍 지원 구역 없음")
        val lightArea = lightEntry.key
        val fanArea = fanEntry.key
        // 전원/AUTO/송풍은 같은 좌석 구역 ID를 쓴다고 가정할 수 없다.
        val powerAreas = awaitValue<List<SeatArea>> { ok, fail ->
            hvac.checkHvacPowerCapabilities({ config, areas ->
                if (config?.accessType in listOf(AccessType.READ, AccessType.READ_WRITE)) {
                    ok(areas)
                } else {
                    fail(IllegalStateException("공조 전원 조회 미지원"))
                }
            }, fail)
        }
        val autoAreas = awaitValue<List<SeatArea>> { ok, fail ->
            hvac.checkHvacAutoCapabilities({ config, areas ->
                if (config?.accessType in listOf(AccessType.READ, AccessType.READ_WRITE)) {
                    ok(areas)
                } else {
                    fail(IllegalStateException("공조 AUTO 조회 미지원"))
                }
            }, fail)
        }
        fun covering(areas: List<SeatArea>): List<SeatArea> {
            val requested = fanArea.values.toSet()
            val selected = areas.filter { it.values.any(requested::contains) }
            check(selected.flatMap { it.values.toList() }.containsAll(requested)) { "송풍 구역에 대응하는 전원/AUTO 구역 없음" }
            return selected
        }
        for (area in covering(powerAreas)) {
            val on = awaitValue<Boolean> { ok, fail -> hvac.getHvacPowerState(area, { ok(it == true) }, fail) }
            check(on) { "공조 꺼짐 · 사용자 설정 유지" }
        }
        for (area in covering(autoAreas)) {
            val auto = awaitValue<Boolean> { ok, fail -> hvac.getHvacAutoState(area, { ok(it != false) }, fail) }
            check(!auto) { "공조 자동 모드 · 사용자 설정 유지" }
        }
        suspend fun brightness() = awaitValue<Int> { ok, fail ->
            light.getAmbientLightBrightness(lightArea, { value ->
                if (value != null) ok(value) else fail(IllegalStateException("무드등 현재값 없음"))
            }, fail)
        }
        suspend fun fan() = awaitValue<Int> { ok, fail ->
            hvac.getHvacFanSpeed(fanArea, { value ->
                if (value != null) ok(value) else fail(IllegalStateException("송풍 현재값 없음"))
            }, fail)
        }
        val originalLight = brightness()
        val originalFan = fan()
        val lightMin = lightEntry.value.minValue ?: error("무드등 범위 없음")
        val lightMax = lightEntry.value.maxValue ?: error("무드등 범위 없음")
        val fanMin = fanEntry.value.minValue ?: error("송풍 범위 없음")
        val fanMax = fanEntry.value.maxValue ?: error("송풍 범위 없음")
        check(originalLight in lightMin..lightMax && originalFan in fanMin..fanMax) { "현재값이 지원 범위를 벗어남" }
        return listOf(
            CabinChange("무드등 밝기", originalLight, lightMin + (originalLight - lightMin) / 3, ::brightness) { value ->
                awaitValue<Unit> { ok, fail -> light.setAmbientLightBrightness(lightArea, value, { _, _ -> ok(Unit) }, fail) }
            },
            CabinChange("송풍 단계", originalFan, (originalFan + 1).coerceAtMost(fanMax), ::fan) { value ->
                awaitValue<Unit> { ok, fail -> hvac.setHvacFanSpeed(fanArea, value, { _, _ -> ok(Unit) }, fail) }
            }
        )
    }

    override fun release() {
        runCatching { sdk?.release() }
        sdk = null
    }

    private suspend fun <T> awaitValue(register: ((T) -> Unit, (Exception) -> Unit) -> Unit): T = suspendCancellableCoroutine { continuation ->
        register(
            { if (continuation.isActive) continuation.resume(it) },
            { if (continuation.isActive) continuation.resumeWithException(it) }
        )
    }
}
