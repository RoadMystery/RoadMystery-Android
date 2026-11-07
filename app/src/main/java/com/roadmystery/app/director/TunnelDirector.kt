package com.roadmystery.app.director

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 값을 읽어 보관한 뒤 변경한다. 모든 채널의 조회가 성공하기 전에는 쓰지 않는다. */
data class CabinChange(
    val label: String,
    val original: Int,
    val target: Int,
    val read: suspend () -> Int,
    val write: suspend (Int) -> Unit
)

interface CabinPort {
    suspend fun prepareTunnel(): List<CabinChange>
    fun release() = Unit
}

/** SDK와 동일한 복원 경로를 쓰는 개발용 차량. 실제 차량을 제어하지 않는다. */
class FakeCabinPort(var available: Boolean = true) : CabinPort {
    var brightness = 70
    var fan = 2
    override suspend fun prepareTunnel(): List<CabinChange> {
        check(available) { "가상 차량: 조명·공조 미지원" }
        return listOf(
            CabinChange("가상 무드등", brightness, 25, { brightness }, { brightness = it }),
            CabinChange("가상 송풍", fan, 3, { fan }, { fan = it })
        )
    }
}

/** 짧은 터널 연출 후 원래 값을 복원한다. 중단/종료 후에도 복원 작업은 끝까지 수행한다. */
class TunnelDirector(
    private val cabin: CabinPort,
    private val sound: Director,
    private val durationMs: Long = 4_000,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val ownsScope: Boolean = true
) : Director {
    private val effect = MutableStateFlow<Effect?>(null)
    override val lastEffect = effect.asStateFlow()
    private val message = MutableStateFlow("연출 대기")
    val status = message.asStateFlow()
    private val mutex = Mutex()
    private var active: Job? = null
    private var generation = 0
    private var released = false
    private var restoreFault: String? = null

    override fun perform(effect: Effect) {
        if (released) return
        active?.cancel()
        sound.stop()
        this.effect.value = effect
        val version = ++generation
        sound.perform(effect)
        active = scope.launch {
            mutex.withLock {
                if (effect != Effect.TUNNEL) {
                    message.value = "${effect.label} · 낮은 음량 효과음"
                    return@withLock
                }
                val changed = mutableListOf<CabinChange>()
                var result = "터널 연출 완료 · 원래 설정 복원"
                try {
                    message.value = "터널 · 조명·공조 지원과 현재값 확인 중"
                    check(restoreFault == null) { "이전 복원 확인 실패: $restoreFault · 차량 제어 보류" }
                    val plan = withTimeout(5_000) { cabin.prepareTunnel() }
                    check(plan.isNotEmpty()) { "지원되는 조명·공조 없음" }
                    for (change in plan) {
                        // 응답 전에 일부 적용됐을 수도 있으므로 호출 전에 복원 대상에 넣는다.
                        changed += change
                        withTimeout(2_000) { change.write(change.target) }
                    }
                    message.value = "터널 연출 중 · " + plan.joinToString { "${it.label} ${it.original}→${it.target}" }
                    delay(durationMs)
                } catch (_: TimeoutCancellationException) {
                    result = "효과음만 재생 · 차량 응답 시간 초과"
                } catch (cancelled: CancellationException) {
                    result = "연출 중단 · 원래 설정 복원"
                    throw cancelled
                } catch (error: Exception) {
                    result = "효과음만 재생 · ${error.message ?: "차량 제어 불가"}"
                } finally {
                    val failures = mutableListOf<String>()
                    val preserved = mutableListOf<String>()
                    withContext(NonCancellable) {
                        for (change in changed.asReversed()) {
                            try {
                                withTimeout(2_000) {
                                    val current = change.read()
                                    if (current == change.target) {
                                        change.write(change.original)
                                    } else if (current != change.original) {
                                        preserved += change.label
                                    }
                                }
                            } catch (_: Exception) {
                                failures += change.label
                            }
                        }
                    }
                    if (failures.isNotEmpty()) restoreFault = failures.joinToString()
                    if (version == generation) {
                        message.value = when {
                            failures.isNotEmpty() -> "복원 확인 실패: ${failures.joinToString()} · 차량 설정을 확인해 주세요"
                            preserved.isNotEmpty() -> "$result · 사용자 변경 유지: ${preserved.joinToString()}"
                            else -> result
                        }
                    }
                }
            }
        }
    }

    override fun stop() {
        sound.stop()
        active?.cancel()
    }

    override fun release() {
        if (released) return
        released = true
        stop()
        val pending = active
        scope.launch {
            pending?.join()
            mutex.withLock { cabin.release() }
            sound.release()
            if (ownsScope) scope.cancel()
        }
    }
}
