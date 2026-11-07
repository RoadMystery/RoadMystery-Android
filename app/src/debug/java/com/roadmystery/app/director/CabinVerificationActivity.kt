package com.roadmystery.app.director

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 명시적으로 실행하는 디버그 검증. 실차에서는 거부하며, 준비 설정도 finally에서 복원한다. */
class CabinVerificationActivity : ComponentActivity() {
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            textSize = 18f
            setPadding(32, 32, 32, 32)
        }
        setContentView(output)
        lifecycleScope.launch {
            runCatching { verify() }.onFailure { record("FAIL ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    private fun record(value: String) {
        Log.i("F09CabinVerify", value)
        output.append("$value\n")
    }

    private suspend fun verify() {
        check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "에뮬레이터 전용 검증" }
        val sdk = Vehicle(this)
        val director = PleosDirector(this)
        val restore = mutableListOf<Pair<String, suspend () -> Unit>>()
        var passed = false
        var restored = true
        try {
            sdk.initialize()
            delay(1_000)
            val light = sdk.getLight()
            val hvac = sdk.getHVAC()
            val lights = await<Map<SeatArea, AreaBounds<Int>>> { ok, fail -> light.checkAmbientLightBrightnessCapabilities({ _, areas -> ok(areas) }, fail) }
            val fans = await<Map<SeatArea, AreaBounds<Int>>> { ok, fail -> hvac.checkHvacFanSpeedCapabilities({ _, areas -> ok(areas) }, fail) }
            val powers = await<List<SeatArea>> { ok, fail -> hvac.checkHvacPowerCapabilities({ _, areas -> ok(areas) }, fail) }
            val autos = await<List<SeatArea>> { ok, fail -> hvac.checkHvacAutoCapabilities({ _, areas -> ok(areas) }, fail) }
            suspend fun brightness(area: SeatArea) = await<Int?> { ok, fail -> light.getAmbientLightBrightness(area, ok, fail) } ?: error("밝기 없음")
            suspend fun fan(area: SeatArea) = await<Int?> { ok, fail -> hvac.getHvacFanSpeed(area, ok, fail) } ?: error("송풍 없음")
            suspend fun power(area: SeatArea) = await<Boolean?> { ok, fail -> hvac.getHvacPowerState(area, ok, fail) } ?: error("전원 없음")
            suspend fun auto(area: SeatArea) = await<Boolean?> { ok, fail -> hvac.getHvacAutoState(area, ok, fail) } ?: error("AUTO 없음")
            suspend fun setLight(area: SeatArea, value: Int) = await<Unit> { ok, fail -> light.setAmbientLightBrightness(area, value, { _, _ -> ok(Unit) }, fail) }
            suspend fun setFan(area: SeatArea, value: Int) = await<Unit> { ok, fail -> hvac.setHvacFanSpeed(area, value, { _, _ -> ok(Unit) }, fail) }
            suspend fun setPower(area: SeatArea, value: Boolean) = await<Unit> { ok, fail -> hvac.setHvacPower(area, value, { _, _ -> ok(Unit) }, fail) }
            suspend fun setAuto(area: SeatArea, value: Boolean) = await<Unit> { ok, fail -> hvac.setHvacAuto(area, value, { _, _ -> ok(Unit) }, fail) }
            val originalLights = lights.keys.associateWith { brightness(it) }
            val originalFans = fans.keys.associateWith { fan(it) }
            val originalPowers = powers.associateWith { power(it) }
            val originalAutos = autos.associateWith { auto(it) }
            record("SNAPSHOT lights=$originalLights fans=$originalFans power=$originalPowers auto=$originalAutos")
            // 복원은 각 속성을 독립적으로 시도하고, 전원은 마지막에 되돌린다.
            for ((area, value) in originalLights) {
                restore += "light $area=$value" to {
                    if (brightness(area) != value) setLight(area, value)
                    check(brightness(area) == value)
                }
            }
            for ((area, value) in originalFans) {
                restore += "fan $area=$value" to {
                    if (fan(area) != value) setFan(area, value)
                    check(fan(area) == value)
                }
            }
            for ((area, value) in originalAutos) {
                restore += "auto $area=$value" to {
                    if (auto(area) != value) setAuto(area, value)
                    check(auto(area) == value)
                }
            }
            for ((area, value) in originalPowers) {
                restore += "power $area=$value" to {
                    if (power(area) != value) setPower(area, value)
                    check(power(area) == value)
                }
            }

            val lightEntry = lights.entries.first()
            val fanEntry = fans.entries.first()
            val selected = fanEntry.key.values.toSet()
            for (area in powers.filter { it.values.any(selected::contains) }) {
                record("SETUP power ${area.values.toList()} before=${power(area)}")
                setPower(area, true)
                delay(1_000)
                record("SETUP power readback=${power(area)}")
                check(power(area)) { "공조 전원 켜기 요청 후 실제 값이 false" }
            }
            for (area in autos.filter { it.values.any(selected::contains) }) {
                if (auto(area)) setAuto(area, false)
                record("SETUP auto ${area.values.toList()} readback=${auto(area)}")
            }
            // 변화를 관측할 수 있는 시작값. 원래 설정은 위에 보관했다.
            val lightMin = requireNotNull(lightEntry.value.minValue)
            val lightMax = requireNotNull(lightEntry.value.maxValue)
            val fanMin = requireNotNull(fanEntry.value.minValue)
            val fanMax = requireNotNull(fanEntry.value.maxValue)
            check(lightMax > lightMin && fanMax > fanMin)
            val startLight = brightness(lightEntry.key).let { if (it > lightMin) it else lightMax }
            val startFan = fan(fanEntry.key).coerceAtMost(fanMax - 1).coerceAtLeast(fanMin)
            if (brightness(lightEntry.key) != startLight) setLight(lightEntry.key, startLight)
            if (fan(fanEntry.key) != startFan) setFan(fanEntry.key, startFan)
            check(brightness(lightEntry.key) == startLight && fan(fanEntry.key) == startFan)
            val targetLight = lightMin + (startLight - lightMin) / 3
            val targetFan = startFan + 1
            record("BASELINE light=$startLight fan=$startFan TARGET light=$targetLight fan=$targetFan")
            val started = SystemClock.elapsedRealtime()
            director.perform(Effect.TUNNEL)
            var sawChange = false
            var sawRestore = false
            repeat(32) {
                delay(250)
                val actualLight = brightness(lightEntry.key)
                val actualFan = fan(fanEntry.key)
                val elapsed = SystemClock.elapsedRealtime() - started
                record("READBACK ms=$elapsed light=$actualLight fan=$actualFan status=${director.status.value}")
                if (actualLight == targetLight && actualFan == targetFan) sawChange = true
                if (sawChange && actualLight == startLight && actualFan == startFan) sawRestore = true
            }
            check(sawChange) { "실제 값 변경을 확인하지 못함" }
            check(sawRestore) { "실제 값 복원을 확인하지 못함" }
            passed = true
            record("CONTROL PASS: SDK readback changed and restored")
        } finally {
            withContext(NonCancellable) {
                director.stop()
                delay(2_500)
                for ((label, action) in restore) {
                    runCatching { withTimeout(5_000) { action() } }
                        .onSuccess { record("RESTORED $label") }
                        .onFailure {
                            restored = false
                            record("RESTORE FAIL $label: ${it.message}")
                        }
                }
                director.release()
                sdk.release()
                record("RESULT control=$passed originalSettingsRestored=$restored")
            }
        }
    }

    private suspend fun <T> await(register: ((T) -> Unit, (Exception) -> Unit) -> Unit): T = withTimeout(5_000) {
        suspendCancellableCoroutine { continuation ->
            register(
                { if (continuation.isActive) continuation.resume(it) },
                { if (continuation.isActive) continuation.resumeWithException(it) }
            )
        }
    }
}
