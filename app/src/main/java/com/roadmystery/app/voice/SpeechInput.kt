package com.roadmystery.app.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.OnServerConnectionListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
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

    /** 진행 중인 듣기를 그만둔다. 그 듣기는 들은 말이 있어도 null로 끝난다. */
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
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var current: UtteranceListener? = null

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
        var registered: UtteranceListener? = null
        try {
            suspendCancellableCoroutine { continuation ->
                val listener = UtteranceListener(
                    onListening = onListening,
                    onSpeechDetected = onSpeechDetected,
                    later = { delayMs, block -> mainHandler.postDelayed({ block() }, delayMs) },
                    warn = { Log.w(TAG, it) },
                    done = { continuation.resume(it) },
                )
                continuation.invokeOnCancellation { listener.close() }
                stt.addListener(listener)
                registered = listener
                current = listener
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
                current = null
                stop()
            }
        }
    }

    /** 진행 중인 듣기를 null로 끝낸다. null을 받은 [listenWithin]은 이어지는 말을 들으려고 마이크를 다시 열지 않는다. */
    override fun cancel() {
        current?.abort()
        stop()
    }

    private fun stop() {
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
 * 묻는 말 하나를 듣는다. 말이 없으면 null.
 *
 * 인식기는 0.6초쯤만 쉬어도 말이 끝났다고 보고 듣기를 끝낸다. 이름을 부른 뒤 잠깐 쉬었다가 물으면 부른 이름만
 * 돌아온다. 그래서 끝맺지 않은 말이면 다시 들어서 이어지는 말을 붙인다.
 *
 * 인식기가 다시 준비되기까지(에뮬레이터에서 0.1~0.7초)의 말은 듣지 못한다. 0.6초만 쉬고 이으면 뒷말의 첫 낱말이
 * 빠질 수 있고, 1초쯤 쉬면 다 들린다.
 */
internal suspend fun SpeechInput.listenWithin(startMs: Long, finishMs: Long): String? {
    val first = listenOnce(startMs, finishMs) ?: return null
    return withFollowUps(first, MAX_FOLLOW_UPS, finishMs)
}

/** [heard] 뒤에 이어지는 말을 [left]번까지 더 듣고 붙인다. 끝맺은 말이거나 이어지는 말이 없으면 그대로 돌려준다. */
private suspend fun SpeechInput.withFollowUps(heard: String, left: Int, finishMs: Long): String {
    if (left == 0 || isCompleteUtterance(heard)) return heard
    val more = listenOnce(FOLLOW_UP_MS, finishMs) ?: return heard
    return withFollowUps("${heard.trim()} ${more.trim()}", left - 1, finishMs)
}

/**
 * 더 기다리지 않아도 되는 말인가. 인식기는 끝맺은 물음에 물음표를 붙인다. 질문이 없다는 대답도 끝난 말이고,
 * 정지 명령은 기다리지 않고 곧바로 따른다.
 */
internal fun isCompleteUtterance(text: String): Boolean =
    text.trimEnd().lastOrNull() in SENTENCE_END || isNoQuestion(text) || isStopCommand(text)

/**
 * 인식기가 준비된 뒤 [startMs] 동안 말 시작을 기다리고, 시작한 말에는 [finishMs]까지 끝낼 시간을 따로 준다.
 * 준비되지 않거나 말이 없으면 null. 준비에 걸리는 시간은 기기마다 달라서 듣는 시간에 넣지 않는다.
 */
private suspend fun SpeechInput.listenOnce(startMs: Long, finishMs: Long): String? = coroutineScope {
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

/** 끝맺지 않은 말 뒤에, 다시 준비된 인식기가 이어지는 말의 시작을 기다리는 시간. */
private const val FOLLOW_UP_MS = 1_500L

/** 묻는 말 하나에 이어 붙이는 횟수의 한도. */
private const val MAX_FOLLOW_UPS = 2

private val SENTENCE_END = setOf('?', '.', '!', '？', '。', '！')
