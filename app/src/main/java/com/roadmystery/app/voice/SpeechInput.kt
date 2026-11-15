package com.roadmystery.app.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.OnServerConnectionListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

interface SpeechInput {
    /** 한 번 듣고 인식한 문장을 돌려준다. 실패하면 null. */
    suspend fun listen(): String?

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

    init {
        stt.initialize(
            object : OnInitializeListener {
                override fun onSuccess() {
                    stt.setLocale(Locale.KOREA)
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

    override suspend fun listen(): String? {
        if (!ready) return null
        return suspendCancellableCoroutine { cont ->
            var partial: String? = null
            val listener =
                object : ResultListener {
                    override fun onUpdated(
                        text: String,
                        isFinal: Boolean,
                    ) {
                        partial = text
                        if (isFinal) finish(text)
                    }

                    override fun onUpdatedEpdData(
                        start: Long,
                        end: Long,
                    ) = Unit

                    override fun onStartedRecognition() = Unit

                    override fun onEndedRecognition() = finish(partial)

                    override fun onError(errorType: ErrorType) {
                        Log.w(TAG, "STT 오류: $errorType")
                        finish(null)
                    }

                    override fun onReady() = Unit

                    override fun onUpdatedRms(rms: Double) = Unit

                    fun finish(result: String?) {
                        stt.removeListener(this)
                        if (cont.isActive) cont.resume(result?.takeIf { it.isNotBlank() })
                    }
                }
            stt.addListener(listener)
            cont.invokeOnCancellation {
                stt.removeListener(listener)
                runCatching { stt.stop() }
            }
            stt.request()
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
