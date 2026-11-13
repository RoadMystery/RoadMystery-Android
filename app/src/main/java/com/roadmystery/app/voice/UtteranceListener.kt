package com.roadmystery.app.voice

import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.listener.ResultListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 한 번의 듣기에서 인식기가 알려 오는 것을 받아 결과 하나로 끝낸다. [done]은 한 번만 불린다.
 *
 * 인식기는 말이 끝났다고 알린 바로 뒤에 확정 결과를 준다. 그 결과를 이 듣기가 받지 않으면, 곧바로 이어서 여는
 * 다음 듣기가 받아 앞말이 한 번 더 들어간다("윤재희 씨 윤재희 씨"). 그래서 끝났다는 알림에서 끝내지 않고
 * [FINAL_RESULT_WAIT_MS]까지 확정 결과를 기다린다. 끝내 오지 않거나 그 사이 오류가 나면 그때까지 들은 말로 끝낸다.
 *
 * [later]는 주어진 시간 뒤에 일을 실행하고, [warn]은 오류를 기록한다.
 */
internal class UtteranceListener(
    private val onListening: () -> Unit,
    private val onSpeechDetected: () -> Unit,
    private val later: (delayMs: Long, block: () -> Unit) -> Unit,
    private val warn: (String) -> Unit,
    private val done: (String?) -> Unit,
) : ResultListener {
    private val completed = AtomicBoolean()

    @Volatile private var partial: String? = null

    @Volatile private var ended = false

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

    override fun onEndedRecognition() {
        if (completed.get()) return
        ended = true
        later(FINAL_RESULT_WAIT_MS) { finish(partial) }
    }

    override fun onError(errorType: ErrorType) {
        warn("STT 오류: $errorType")
        finish(if (ended) partial else null)
    }

    override fun onReady() = onListening()

    override fun onUpdatedRms(rms: Double) = Unit

    /** 듣기를 그만둘 때 부른다. 그 뒤에 오는 알림은 버린다. */
    fun close() = completed.set(true)

    /** 밖에서 듣기를 멈출 때 부른다. 들은 말이 있어도 버리고 끝낸다. */
    fun abort() = finish(null)

    private fun finish(result: String?) {
        if (completed.compareAndSet(false, true)) done(result?.takeIf { it.isNotBlank() })
    }
}

/** 말이 끝났다는 알림 뒤에 확정 결과를 기다리는 시간. 기기 안 인식기는 0.02초 안에 준다. */
internal const val FINAL_RESULT_WAIT_MS = 1_000L
