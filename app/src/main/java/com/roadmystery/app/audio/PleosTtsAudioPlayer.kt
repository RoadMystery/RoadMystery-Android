package com.roadmystery.app.audio

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.OnServerConnectionListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import com.roadmystery.app.story.Scene
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** 실제 Pleos 음성 출력. 초기화 중에는 기다리고 실패하면 해당 문장에서 멈춘다. */
class PleosTtsAudioPlayer(
    context: Context,
    scope: CoroutineScope,
    clientId: String,
    clientSecret: String
) : AudioPlayer {
    private val output = PleosSpeechOutput(context, scope, clientId, clientSecret)
    private val player = SentenceAudioPlayer(scope, output)
    val status = player.status
    override val events = player.events

    init {
        output.onFocusChange = { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> player.onFocusLost(transient = false)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player.onFocusLost(transient = true)
                AudioManager.AUDIOFOCUS_GAIN -> player.onFocusGained()
                // CAN_DUCK은 플랫폼이 음량을 줄이도록 둔다.
            }
        }
    }

    override fun play(scene: Scene, fromSentence: Int) = player.play(scene, fromSentence)
    override fun pause(): Int = player.pause()
    override fun stop() = player.stop()
    fun retry() = player.retry()
    fun release() = player.release()
}

/**
 * Pleos 완료 이벤트에는 요청 ID가 없다. stop 확인 전에는 다음 요청을 보내지 않는다.
 * 모든 상태 변경은 메인 스레드에서 직렬화한다.
 */
private class PleosSpeechOutput(
    context: Context,
    private val scope: CoroutineScope,
    clientId: String,
    clientSecret: String
) : SpeechOutput {
    private val tts = TextToSpeech(context.applicationContext, if (clientId.isBlank() || clientSecret.isBlank()) Mode.ON_DEVICE else Mode.HYBRID)
    private val manager = context.getSystemService(AudioManager::class.java)
    private val ready = CompletableDeferred<Unit>()
    private val mutex = Mutex()
    private var pending: CompletableDeferred<Unit>? = null
    private var stopping: CompletableDeferred<Unit>? = null
    private var released = false
    private var soundReported = false
    var onFocusChange: (Int) -> Unit = {}
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> scope.launch { onFocusChange(change) } }
        .build()

    private val listener = object : EventListener {
        override fun onStart() {
            Log.i(TAG, "speech started")
        }
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) {
            scope.launch {
                if (rms > 0 && !soundReported && pending != null && stopping == null) {
                    soundReported = true
                    Log.i(TAG, "audible PCM reported")
                }
            }
        }
        override fun onDone() {
            scope.launch {
                if (stopping == null) {
                    Log.i(TAG, "speech completed")
                    pending?.complete(Unit)
                }
            }
        }
        override fun onStop() {
            scope.launch {
                Log.i(TAG, "speech stopped")
                val ack = stopping
                if (ack != null) {
                    stopping = null
                    ack.complete(Unit)
                } else {
                    pending?.completeExceptionally(IllegalStateException("음성 서비스가 재생을 중단했습니다"))
                }
            }
        }
        override fun onError(errorType: ErrorType) {
            scope.launch {
                Log.e(TAG, "speech error: $errorType")
                pending?.completeExceptionally(IllegalStateException(errorType.toString()))
            }
        }
    }

    init {
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                scope.launch {
                    if (released) return@launch
                    tts.addEventListener(listener)
                    tts.setAudioAttributes(attributes)
                    tts.setLocale(Locale.KOREA)
                    if (clientId.isBlank() || clientSecret.isBlank()) {
                        ready.complete(Unit)
                    } else {
                        tts.registerApp(
                            clientId,
                            clientSecret,
                            object : OnServerConnectionListener {
                                override fun onConnected() {
                                    ready.complete(Unit)
                                }
                                override fun onFailed(errorType: ErrorType) {
                                    ready.completeExceptionally(IllegalStateException("음성 서비스 등록 실패: $errorType"))
                                }
                            }
                        )
                    }
                }
            }
            override fun onFailure(errorType: ErrorType) {
                ready.completeExceptionally(IllegalStateException("TTS 초기화 실패: $errorType"))
            }
        })
    }

    override suspend fun speak(text: String, speaker: String) = mutex.withLock {
        check(!released) { "음성 서비스가 종료됐습니다" }
        withTimeout(15_000) { ready.await() }
        // 확인 실패 시 새 요청에 이전 완료 이벤트를 연결하지 않고 오류로 멈춘다.
        stopping?.let { withTimeout(3_000) { it.await() } }
        check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "오디오 사용 권한을 얻지 못했습니다"
        }
        val request = CompletableDeferred<Unit>()
        pending = request
        soundReported = false
        try {
            tts.setSpeakerType(speaker)
            Log.i(TAG, "speak speaker=$speaker chars=${text.length}")
            tts.speak(text, true)
            withTimeout(120_000) { request.await() }
        } finally {
            if (pending === request) {
                if (!request.isCompleted || request.isCancelled) stop()
                pending = null
            }
        }
    }

    override fun stop() {
        val request = pending ?: return
        pending = null
        if (!request.isCompleted) request.cancel(CancellationException("낭독 중단"))
        if (stopping == null && !released) {
            val ack = CompletableDeferred<Unit>()
            stopping = ack
            runCatching { tts.stop() }.onFailure { ack.completeExceptionally(it) }
        }
    }

    override fun release() {
        stop()
        released = true
        manager.abandonAudioFocusRequest(focus)
        runCatching { tts.removeEventListener(listener) }
        runCatching { tts.release() }
    }

    companion object {
        const val TAG = "F03Speech"
    }
}
