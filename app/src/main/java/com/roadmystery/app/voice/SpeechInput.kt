package com.roadmystery.app.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.OnServerConnectionListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

interface SpeechInput {
    /**
     * 한 번 듣고 인식한 문장을 돌려준다. 실패하면 null.
     * [onListening]은 인식기가 들을 준비를 마쳤을 때, [onSpeechDetected]는 말이 들리기 시작했을 때 부른다.
     */
    suspend fun listen(onListening: () -> Unit = {}, onSpeechDetected: () -> Unit = {}): String?

    fun cancel()
}

/** Pleos SpeechToText SDK. 키가 없으면 ON_DEVICE, 있으면 HYBRID. */
class PleosSpeechInput(
    context: Context,
    clientId: String,
    clientSecret: String,
) : SpeechInput {
    private val useServer = clientId.isNotBlank() && clientSecret.isNotBlank()
    private val stt = SpeechToText(context.applicationContext, if (useServer) Mode.HYBRID else Mode.ON_DEVICE)

    @Volatile private var ready = false
    private val listenMutex = Mutex()

    init {
        stt.initialize(
            object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching { stt.setLocale(Locale.KOREA) }
                        .onFailure { Log.w(TAG, "STT 언어 설정 실패", it) }
                    if (!useServer) {
                        ready = true
                        return
                    }
                    stt.registerApp(
                        clientId,
                        clientSecret,
                        object : OnServerConnectionListener {
                            override fun onConnected() {
                                ready = true
                            }

                            override fun onFailed(errorType: ErrorType) {
                                Log.w(TAG, "STT 서버 등록 실패, 온디바이스로 계속: $errorType")
                                ready = true
                            }
                        },
                    )
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "STT 초기화 실패: $errorType")
                }
            },
        )
    }

    override suspend fun listen(onListening: () -> Unit, onSpeechDetected: () -> Unit): String? = listenMutex.withLock {
        if (!ready) return@withLock null
        var registered: ResultListener? = null
        try {
            suspendCancellableCoroutine { continuation ->
                val completed = AtomicBoolean()
                val listener = object : ResultListener {
                    @Volatile private var partial: String? = null

                    override fun onUpdated(text: String, isFinal: Boolean) {
                        if (completed.get()) return
                        partial = text
                        if (text.isNotBlank()) onSpeechDetected()
                        if (isFinal) finish(text)
                    }

                    override fun onUpdatedEpdData(start: Long, end: Long) {
                        if (!completed.get() && (start > 0 || end > 0)) onSpeechDetected()
                    }

                    override fun onStartedRecognition() = onListening()
                    override fun onEndedRecognition() = finish(partial)

                    override fun onError(errorType: ErrorType) {
                        Log.w(TAG, "STT 오류: $errorType")
                        finish(null)
                    }

                    override fun onReady() = onListening()
                    override fun onUpdatedRms(rms: Double) = Unit

                    private fun finish(result: String?) {
                        if (completed.compareAndSet(false, true)) {
                            continuation.resume(result?.takeIf { it.isNotBlank() })
                        }
                    }
                }
                continuation.invokeOnCancellation { completed.set(true) }
                stt.addListener(listener)
                registered = listener
                stt.request()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "STT 요청 실패", error)
            null
        } finally {
            registered?.let { listener ->
                runCatching { stt.removeListener(listener) }
                cancel()
            }
        }
    }

    override fun cancel() {
        runCatching { stt.stop() }
    }

    fun release() {
        runCatching { stt.release() }
    }

    private companion object {
        const val TAG = "PleosSpeechInput"
    }
}

/**
 * 인식기가 준비된 뒤 [startMs] 동안 말 시작을 기다리고, 시작한 말에는 [finishMs]까지 끝낼 시간을 따로 준다.
 * 준비되지 않거나 말이 없으면 null. 준비에 걸리는 시간은 기기마다 달라서 듣는 시간에 넣지 않는다.
 */
internal suspend fun SpeechInput.listenWithin(startMs: Long, finishMs: Long): String? = coroutineScope {
    val listening = CompletableDeferred<Unit>()
    val speechDetected = CompletableDeferred<Unit>()
    val result = async {
        listen(onListening = { listening.complete(Unit) }, onSpeechDetected = { speechDetected.complete(Unit) })
    }
    try {
        val ready = withTimeoutOrNull(READY_LIMIT_MS) {
            select<Unit> {
                listening.onAwait { }
                speechDetected.onAwait { }
                result.onAwait { }
            }
        }
        val startedOrFinished = if (ready == null) null else withTimeoutOrNull(startMs) {
            select<Unit> {
                speechDetected.onAwait { }
                result.onAwait { }
            }
        }
        if (startedOrFinished == null) null else withTimeoutOrNull(finishMs) { result.await() }
    } finally {
        result.cancel()
    }
}

/** 주행 중 질문 구간: 준비된 뒤 3초 동안 말 시작을 기다린다. */
internal suspend fun SpeechInput.listenForDrivingQuestion(): String? = listenWithin(startMs = 3_000, finishMs = 8_000)

/** 정차 중 "음성으로 묻기": 준비된 뒤 6초 동안 말 시작을 기다리고, 말이 없으면 끝낸다. */
internal suspend fun SpeechInput.listenForQuestion(): String? = listenWithin(startMs = 6_000, finishMs = 10_000)

/** 인식기가 이 시간 안에 준비되지 않으면 듣기를 끝낸다. */
private const val READY_LIMIT_MS = 5_000L
