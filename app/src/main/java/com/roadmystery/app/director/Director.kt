package com.roadmystery.app.director

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 장면 분위기 연출. ambientColor는 무드등 색(0xRRGGBB). */
enum class Effect(
    val label: String,
    val ambientColor: Int,
    val tone: Int
) {
    NIGHT("밤", 0x1A2A6C, ToneGenerator.TONE_PROP_BEEP),
    TENSION("긴장", 0xB0122B, ToneGenerator.TONE_PROP_BEEP2),
    TUNNEL("터널", 0x3A0CA3, ToneGenerator.TONE_PROP_ACK),
    REVEAL("진실", 0xFFD166, ToneGenerator.TONE_PROP_PROMPT)
    ;

    companion object {
        fun from(name: String): Effect? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

interface Director {
    /** 마지막으로 실행한 연출. 시뮬레이션 패널 표시용. */
    val lastEffect: StateFlow<Effect?>

    fun perform(effect: Effect)

    /** 위험·장면 종료·앱 종료 시 효과음과 임시 차량 연출을 정리한다. */
    fun stop() = Unit
    fun release() = stop()
}

/** 연출 기록만 남긴다. 테스트와 차량 제어가 불가능한 환경용. */
class FakeDirector : Director {
    private val _lastEffect = MutableStateFlow<Effect?>(null)
    override val lastEffect: StateFlow<Effect?> = _lastEffect.asStateFlow()

    override fun perform(effect: Effect) {
        _lastEffect.value = effect
    }
}

/** 차량 제어가 안 될 때의 대체 연출: 짧은 효과음. */
class ToneDirector : Director {
    private val _lastEffect = MutableStateFlow<Effect?>(null)
    override val lastEffect: StateFlow<Effect?> = _lastEffect.asStateFlow()
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 15) }.getOrNull()

    override fun perform(effect: Effect) {
        _lastEffect.value = effect
        tone?.startTone(effect.tone, 250)
    }

    override fun stop() {
        tone?.stopTone()
    }
    override fun release() {
        stop()
        tone?.release()
    }
}
