package com.roadmystery.app.audio

import com.roadmystery.app.story.Scene
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 한 요청이 실제로 끝날 때까지 반환하지 않는다. cancel 시 출력도 즉시 중단한다. */
interface SpeechOutput {
    suspend fun speak(text: String, speaker: String)
    fun stop()
    fun release()
}

object NarrationVoices {
    val speakers = listOf("sanghyeok", "seoyoon", "eunwoo", "haeun")

    fun speaker(voice: String): String = when (voice) {
        "narrator" -> "sanghyeok"
        "female_calm" -> "seoyoon"
        "male_rough" -> "eunwoo"
        "female_nervous", "female_young", "female_bright" -> "haeun"
        in speakers -> voice
        else -> "sanghyeok"
    }
}

enum class PlaybackPhase { IDLE, PREPARING, SPEAKING, QUESTION, WAITING, PAUSED, ERROR }

data class PlaybackStatus(
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val sceneId: String? = null,
    val sentenceIndex: Int = 0,
    val speaker: String? = null,
    val message: String = "낭독 대기"
)

/** 문장 완료를 기다려 순서대로 재생한다. 오류를 장면 완료로 취급하지 않는다. */
class SentenceAudioPlayer(
    private val scope: CoroutineScope,
    private val output: SpeechOutput,
    private val questionWaitMs: Long = 3_000
) : AudioPlayer {
    private val mutableEvents = MutableSharedFlow<AudioEvent>(extraBufferCapacity = 64)
    override val events = mutableEvents.asSharedFlow()
    private val mutableStatus = MutableStateFlow(PlaybackStatus())
    val status = mutableStatus.asStateFlow()
    private var job: Job? = null
    private var current: Scene? = null
    private var index = 0
    private var focusHeld = false
    private var suspendedForFocus = false
    var onQuestionWindow: (suspend (Scene) -> Boolean)? = null

    override fun play(scene: Scene, fromSentence: Int) {
        cancelPlayback()
        current = scene
        index = fromSentence.coerceIn(0, scene.script.size)
        suspendedForFocus = false
        if (focusHeld) {
            suspendedForFocus = true
            publish(PlaybackPhase.PAUSED, "다른 오디오가 끝나기를 기다리는 중")
        } else {
            launchPlayback(scene)
        }
    }

    private fun launchPlayback(scene: Scene) {
        job = scope.launch {
            try {
                for (i in index until scene.script.size) {
                    index = i
                    val speaker = NarrationVoices.speaker(scene.sentenceVoices.getOrNull(i) ?: scene.voice)
                    publish(PlaybackPhase.SPEAKING, "문장 ${i + 1}/${scene.script.size} · $speaker", speaker)
                    mutableEvents.emit(AudioEvent.SentenceStarted(scene.id, i))
                    output.speak(scene.script[i], speaker)
                }
                if (scene.trigger == null) {
                    // script.size는 장면 사이 질문 구간을 뜻한다. 중단 후에도 다음 장면을 건너뛰지 않는다.
                    index = scene.script.size
                    publish(PlaybackPhase.QUESTION, QUESTION)
                    output.speak(QUESTION, NarrationVoices.speaker("narrator"))
                    publish(PlaybackPhase.WAITING, "질문 대기")
                    if (onQuestionWindow?.invoke(scene) != true) delay(questionWaitMs)
                    ensureActive()
                }
                publish(PlaybackPhase.IDLE, "장면 재생 완료")
                current = null
                mutableEvents.emit(AudioEvent.SceneFinished(scene.id))
            } catch (timeout: TimeoutCancellationException) {
                output.stop()
                publish(PlaybackPhase.ERROR, "음성 서비스 응답 시간이 초과됐습니다. 해당 문장에서 멈췄습니다.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                output.stop()
                publish(PlaybackPhase.ERROR, "음성 재생 실패: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    override fun pause(): Int {
        cancelPlayback()
        suspendedForFocus = false
        publish(PlaybackPhase.PAUSED, "낭독 일시정지")
        return index
    }

    override fun stop() {
        cancelPlayback()
        suspendedForFocus = false
        current = null
        index = 0
        mutableStatus.value = PlaybackStatus()
    }

    /** 일시 포커스 상실만 자동 복구한다. 위험 중단이 발생하면 복구 예약을 지운다. */
    fun onFocusLost(transient: Boolean) {
        focusHeld = transient
        val wasActive = job?.isActive == true
        pause()
        suspendedForFocus = transient && wasActive
    }

    fun onFocusGained() {
        focusHeld = false
        if (!suspendedForFocus) return
        suspendedForFocus = false
        current?.let(::launchPlayback)
    }

    fun retry() {
        if (mutableStatus.value.phase == PlaybackPhase.ERROR) current?.let { play(it, index) }
    }

    fun release() {
        stop()
        output.release()
    }

    private fun cancelPlayback() {
        job?.cancel()
        job = null
        output.stop()
    }

    private fun publish(phase: PlaybackPhase, message: String, speaker: String? = null) {
        mutableStatus.value = PlaybackStatus(phase, current?.id, index, speaker, message)
    }

    companion object {
        const val QUESTION = "질문할 것이 있나요? 잠시 생각할 시간을 드릴게요."
    }
}
