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
    /** 한 번 듣고 인식한 문장을 돌려준다. 실패하면 null. */
    suspend fun listen(onSpeechDetected: () -> Unit = {}): String?

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

    override suspend fun listen(onSpeechDetected: () -> Unit): String? = listenMutex.withLock {
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

                    override fun onStartedRecognition() = Unit
                    override fun onEndedRecognition() = finish(partial)

                    override fun onError(errorType: ErrorType) {
                        Log.w(TAG, "STT 오류: $errorType")
                        finish(null)
                    }

                    override fun onReady() = Unit
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

/** 3초 동안 말 시작을 기다리고, 시작한 말에는 완료할 시간을 따로 준다. */
internal suspend fun SpeechInput.listenForDrivingQuestion(): String? = coroutineScope {
    val speechDetected = CompletableDeferred<Unit>()
    val result = async { listen { speechDetected.complete(Unit) } }
    try {
        val startedOrFinished = withTimeoutOrNull(3_000) {
            select<Unit> {
                speechDetected.onAwait { }
                result.onAwait { }
            }
        }
        if (startedOrFinished == null) null else withTimeoutOrNull(8_000) { result.await() }
    } finally {
        result.cancel()
    }
}
