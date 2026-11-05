package com.roadmystery.app.vehicle

/** Speed is m/s; timestamps use SystemClock.elapsedRealtime, never wall clock time. */
data class MotionSample(
    val speedMps: Float? = null,
    val sampledAtMs: Long? = null,
    val parked: Boolean? = null,
    val restricted: Boolean? = null
)

data class MotionReading(
    val event: VehicleEvent = VehicleEvent.Driving,
    val reason: String = "차량 신호 대기",
    val sample: MotionSample = MotionSample(),
    val physicalEvent: VehicleEvent = event
)

/** Conservative UI mode: only confirmed, unrestricted stationary samples unlock the screen. */
class MotionClassifier(
    private val stopDelayMs: Long = 2_000,
    private val staleAfterMs: Long = 3_000
) {
    private var stoppedSince: Long? = null

    init {
        require(stopDelayMs >= 0 && staleAfterMs > 0)
    }

    fun classify(sample: MotionSample, nowMs: Long): MotionReading {
        val speed = sample.speedMps
        val timestamp = sample.sampledAtMs
        val problem = when {
            speed == null || !speed.isFinite() -> "속도 미수신·오류: 주행으로 처리"
            timestamp == null || timestamp > nowMs || nowMs - timestamp > staleAfterMs -> "속도 신호 유효시간 초과: 주행으로 처리"
            speed != 0f -> "이동 감지: 주행"
            sample.parked == null -> "기어 미수신: 주행으로 처리"
            else -> null
        }
        if (problem != null) {
            stoppedSince = null
            return MotionReading(VehicleEvent.Driving, problem, sample)
        }
        val since = stoppedSince?.takeIf { it <= nowMs } ?: nowMs.also { stoppedSince = it }
        if (nowMs - since < stopDelayMs) {
            return MotionReading(VehicleEvent.Driving, "정차 확인 중 (2초 유지 필요)", sample)
        }
        val physical = if (sample.parked == true) VehicleEvent.Parked else VehicleEvent.Stopped
        if (sample.restricted != false) {
            return MotionReading(VehicleEvent.Driving, "정차 확인됨 · 차량 화면 제한으로 최소 표시 유지", sample, physical)
        }
        return MotionReading(physical, if (sample.parked == true) "정차 유지 + P 기어 확인" else "정차 유지 확인", sample, physical)
    }
}
