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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** 실제 Pleos 음성 출력. 초기화 중에는 기다리고 실패하면 해당 문장에서 멈춘다. */
class PleosTtsAudioPlayer(
    context: Context,
    scope: CoroutineScope,
    clientId: String,
    clientSecret: String
) : AudioPlayer {
    private val output = PleosSpeechOutput(context, scope, clientId, clientSecret)
    private val player = SentenceAudioPlayer(scope, output)
    var onQuestionWindow: (suspend (Scene) -> Boolean)?
        get() = player.onQuestionWindow
        set(value) {
            player.onQuestionWindow = value
        }

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
    fun disconnect() = output.disconnect()
    fun release() = player.release()
}

/** SDK 콜백에는 요청 ID가 없어 정지 확인이 유실되면 연결을 새로 만든다. */
private class PleosSpeechOutput(
    context: Context,
    scope: CoroutineScope,
    clientId: String,
    clientSecret: String
) : SpeechOutput {
    private val manager = context.getSystemService(AudioManager::class.java)
    var onFocusChange: (Int) -> Unit = {}
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> scope.launch { onFocusChange(change) } }
        .build()

    private val output = RecoverableTtsOutput(scope) { callbacks ->
        val useServer = clientId.isNotBlank() && clientSecret.isNotBlank()
        val tts = TextToSpeech(context.applicationContext, if (useServer) Mode.HYBRID else Mode.ON_DEVICE)
        val listener = object : EventListener {
            override fun onStart() {
                Log.i(TAG, "speech started")
                callbacks.started()
            }
            override fun onReady() = Unit
            override fun onUpdatedRms(rms: Double) {
                if (rms > 0) callbacks.audible { Log.i(TAG, "audible PCM reported") }
            }
            override fun onDone() = callbacks.done()
            override fun onStop() {
                Log.i(TAG, "speech stopped")
                callbacks.stopped()
            }
            override fun onError(errorType: ErrorType) = callbacks.failed(IllegalStateException(errorType.toString()))
        }
        object : TtsConnection {
            override fun initialize() {
                tts.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        scope.launch {
                            if (!callbacks.isCurrent) return@launch
                            runCatching {
                                tts.addEventListener(listener)
                                tts.setAudioAttributes(attributes)
                                tts.setLocale(Locale.KOREA)
                                if (useServer) {
                                    tts.registerApp(
                                        clientId,
                                        clientSecret,
                                        object : OnServerConnectionListener {
                                            override fun onConnected() = callbacks.ready()
                                            override fun onFailed(errorType: ErrorType) = callbacks.ready(IllegalStateException("음성 서비스 등록 실패: $errorType"))
                                        }
                                    )
                                } else {
                                    callbacks.ready()
                                }
                            }.onFailure { callbacks.ready(it) }
                        }
                    }

                    override fun onFailure(errorType: ErrorType) = callbacks.ready(IllegalStateException("TTS 초기화 실패: $errorType"))
                })
            }

            override fun speak(text: String, speaker: String) {
                check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    "오디오 사용 권한을 얻지 못했습니다"
                }
                tts.setSpeakerType(speaker)
                Log.i(TAG, "speak speaker=$speaker chars=${text.length}")
                tts.speak(text, true)
            }

            override fun stop() = tts.stop()

            override fun close() {
                runCatching { tts.removeEventListener(listener) }
                runCatching { tts.release() }
            }
        }
    }

    override suspend fun speak(text: String, speaker: String) = output.speak(text, speaker)
    override fun stop() = output.stop()
    fun disconnect() = output.disconnect()

    override fun release() {
        output.release()
        manager.abandonAudioFocusRequest(focus)
    }

    private companion object {
        const val TAG = "F03Speech"
    }
}

internal interface TtsConnection {
    fun initialize()
    fun speak(text: String, speaker: String)
    fun stop()
    fun close()
}

/** 재생과 콜백은 메인 스레드에서 처리하며, 닫은 연결의 콜백은 버린다. */
internal class RecoverableTtsOutput(
    private val scope: CoroutineScope,
    private val connect: (Callbacks) -> TtsConnection
) : SpeechOutput {
    private val mutex = Mutex()
    private var current: Callbacks? = null
    private var released = false

    internal inner class Callbacks {
        lateinit var connection: TtsConnection
        val initialized = CompletableDeferred<Unit>()
        var pending: CompletableDeferred<Unit>? = null
        var speechStarted: CompletableDeferred<Unit>? = null
        var stopAck: CompletableDeferred<Unit>? = null
        var soundReported = false
        val isCurrent get() = current === this

        fun ready(error: Throwable? = null) = dispatch {
            if (error == null) initialized.complete(Unit) else initialized.completeExceptionally(error)
        }

        fun started() = dispatch { if (stopAck == null) speechStarted?.complete(Unit) }

        fun audible(report: () -> Unit) = dispatch {
            if (!soundReported && pending != null && stopAck == null) {
                soundReported = true
                report()
            }
        }

        fun done() = dispatch { if (stopAck == null) pending?.complete(Unit) }

        fun stopped() = dispatch {
            val ack = stopAck
            if (ack == null) {
                pending?.completeExceptionally(IllegalStateException("음성 서비스가 재생을 중단했습니다"))
            } else {
                stopAck = null
                ack.complete(Unit)
            }
        }

        fun failed(error: Throwable) = dispatch { if (stopAck == null) pending?.completeExceptionally(error) }

        private fun dispatch(action: () -> Unit) {
            scope.launch { if (isCurrent) action() }
        }
    }

    private fun open(): Callbacks {
        val session = Callbacks()
        session.connection = connect(session)
        current = session
        runCatching { session.connection.initialize() }.onFailure { session.ready(it) }
        return session
    }

    private fun close(session: Callbacks) {
        if (!session.isCurrent) return
        current = null
        session.initialized.cancel()
        session.pending?.cancel()
        session.speechStarted?.cancel()
        session.stopAck?.cancel()
        session.connection.close()
    }

    override suspend fun speak(text: String, speaker: String): Unit = mutex.withLock {
        check(!released) { "음성 서비스가 종료됐습니다" }
        current?.let { session ->
            val ack = session.stopAck
            if (ack != null && withTimeoutOrNull(3_000) { ack.await() } == null) close(session)
        }
        val session = current ?: open()
        try {
            if (withTimeoutOrNull(15_000) { session.initialized.await() } == null) {
                error("음성 서비스 초기화 시간이 초과됐습니다")
            }
        } catch (error: Exception) {
            if (error !is CancellationException) close(session)
            throw error
        }
        val request = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        session.pending = request
        session.speechStarted = started
        session.soundReported = false
        try {
            session.connection.speak(text, speaker)
            withTimeout(10_000) {
                select<Unit> {
                    started.onAwait { }
                    request.onAwait { }
                }
            }
            withTimeout(120_000) { request.await() }
        } catch (timeout: TimeoutCancellationException) {
            if (session.pending === request) {
                stop()
                close(session)
            }
            throw timeout
        } finally {
            if (session.speechStarted === started) session.speechStarted = null
            if (session.pending === request) {
                if (!request.isCompleted || request.isCancelled) stop()
                session.pending = null
            }
        }
    }

    override fun stop() {
        val session = current ?: return
        val request = session.pending ?: return
        session.pending = null
        session.stopAck = CompletableDeferred()
        session.speechStarted = null
        request.cancel()
        runCatching { session.connection.stop() }.onFailure { close(session) }
    }

    fun disconnect() {
        stop()
        current?.let(::close)
    }

    override fun release() {
        released = true
        disconnect()
    }
}
