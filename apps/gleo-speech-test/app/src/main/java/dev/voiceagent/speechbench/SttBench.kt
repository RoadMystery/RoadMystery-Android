package dev.voiceagent.speechbench

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val BYTES_PER_100_MS = 3_200 // 16 kHz, 16-bit, mono
private const val BYTES_PER_MS = 32L
private const val CHUNK_INTERVAL_MS = 100L
private const val INIT_TIMEOUT_S = 15L
private const val RESULT_TIMEOUT_MS = 20_000L
/** The engine may send several final segments; wait this long without events before stopping. */
private const val QUIET_AFTER_LAST_EVENT_MS = 1_500L
private const val POLL_MS = 50L
private const val SETTLE_BETWEEN_CASES_MS = 500L

/**
 * Feeds PCM fixtures to the on-device recognizer at real-time speed, like a live microphone,
 * and records what was recognized and how long the final result took after speech ended.
 * Results are passed to onResult as each fixture finishes. Blocking: call from a background thread.
 */
class SttBench(private val context: Context, private val log: (String) -> Unit) {

    fun loadCases(): List<SttCase> {
        val json = context.assets.open("stt/cases.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            SttCase(item.getString("id"), item.getString("file"), item.getString("expected"))
        }
    }

    fun run(cases: List<SttCase>, onResult: (SttResult) -> Unit): List<SttResult> {
        val stt = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
        val recorder = SttRecorder()
        try {
            val initError = initialize(stt)
            if (initError != null) {
                log("STT 초기화 실패: $initError")
                return cases.map { failed(it, "initialize: $initError").also(onResult) }
            }
            stt.setLocale(Locale.KOREA)
            stt.addListener(recorder)
            return cases.map { case ->
                runCase(stt, recorder, case).also(onResult).also { SystemClock.sleep(SETTLE_BETWEEN_CASES_MS) }
            }
        } finally {
            stt.removeListener(recorder)
            stt.release()
        }
    }

    private fun initialize(stt: SpeechToText): String? {
        val done = CountDownLatch(1)
        var error: String? = null
        stt.initialize(object : OnInitializeListener {
            override fun onSuccess() = done.countDown()

            override fun onFailure(errorType: ErrorType) {
                error = errorType.name
                done.countDown()
            }
        })
        return if (done.await(INIT_TIMEOUT_S, TimeUnit.SECONDS)) error else "timeout"
    }

    private fun runCase(stt: SpeechToText, recorder: SttRecorder, case: SttCase): SttResult {
        val pcm = context.assets.open(case.file).use { it.readBytes() }
        val audioStart = SystemClock.elapsedRealtime()
        val attempt = recorder.begin(audioStart)
        for (offset in pcm.indices step BYTES_PER_100_MS) {
            // Like a live microphone, a 100 ms chunk is available only after it has been spoken.
            SystemClock.sleep(CHUNK_INTERVAL_MS)
            stt.sendAudio(pcm.copyOfRange(offset, minOf(offset + BYTES_PER_100_MS, pcm.size)))
        }
        stt.completeAudioSend()
        val audioEnd = SystemClock.elapsedRealtime()

        val settled = awaitQuiet(attempt)
        val recognized = attempt.text()
        val segments = attempt.segmentCount()
        val error = attempt.error ?: if (settled) null else "timeout"
        val lastFinalAt = attempt.lastFinalAt
        val speechEndAt = attempt.speechEndByte?.let { audioStart + it / BYTES_PER_MS }
        val result = SttResult(
            id = case.id,
            expected = case.expected,
            recognized = recognized,
            segments = segments,
            cer = if (error != null) null else characterErrorRate(case.expected, recognized),
            audioMs = pcm.size / BYTES_PER_MS,
            firstPartialMs = attempt.firstPartialAt?.minus(audioStart),
            finalAfterSpeechEndMs = if (segments == 1 && lastFinalAt != null && speechEndAt != null) {
                lastFinalAt - speechEndAt
            } else {
                null
            },
            finalAfterAudioEndMs = lastFinalAt?.minus(audioEnd),
            error = error,
        )
        log(
            "STT ${case.id}: \"$recognized\" 조각=${result.segments} " +
                "CER=${result.cer?.let { "%.2f".format(it) }} 말끝+${result.finalAfterSpeechEndMs}ms ${error ?: ""}",
        )
        return result
    }

    /** True once results stop arriving (or an error came); false on overall timeout. */
    private fun awaitQuiet(attempt: SttAttempt): Boolean {
        val deadline = SystemClock.elapsedRealtime() + RESULT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (attempt.error != null) return true
            val quietFor = SystemClock.elapsedRealtime() - attempt.lastEventAt
            if (attempt.lastFinalAt != null && attempt.partial.isEmpty() && quietFor >= QUIET_AFTER_LAST_EVENT_MS) {
                return true
            }
            SystemClock.sleep(POLL_MS)
        }
        return false
    }

    private fun failed(case: SttCase, error: String) =
        SttResult(case.id, case.expected, "", 0, null, 0, null, null, null, error)
}

/** Callback state for one fixture. Written from SDK callback threads, polled by the bench thread. */
private class SttAttempt(startedAt: Long) {
    private val finals = CopyOnWriteArrayList<String>()

    @Volatile var partial: String = ""
    @Volatile var firstPartialAt: Long? = null
    @Volatile var lastFinalAt: Long? = null
    @Volatile var lastEventAt: Long = startedAt
    @Volatile var speechEndByte: Long? = null
    @Volatile var error: String? = null

    fun addFinal(text: String, at: Long) {
        if (text.isNotBlank()) finals.add(text)
        partial = ""
        lastFinalAt = at
    }

    fun segmentCount(): Int = finals.size

    fun text(): String = (finals + listOf(partial).filter { it.isNotBlank() }).joinToString(" ")
}

private class SttRecorder : ResultListener {
    @Volatile private var current: SttAttempt? = null

    fun begin(startedAt: Long): SttAttempt = SttAttempt(startedAt).also { current = it }

    override fun onUpdated(stt: String, completed: Boolean) {
        val attempt = current ?: return
        val now = SystemClock.elapsedRealtime()
        attempt.lastEventAt = now
        if (attempt.firstPartialAt == null && stt.isNotBlank()) attempt.firstPartialAt = now
        if (completed) attempt.addFinal(stt, now) else attempt.partial = stt
    }

    override fun onUpdatedEpdData(on: Long, off: Long) {
        val attempt = current ?: return
        attempt.lastEventAt = SystemClock.elapsedRealtime()
        if (off > (attempt.speechEndByte ?: 0L)) attempt.speechEndByte = off
    }

    override fun onError(errorType: ErrorType) {
        current?.error = errorType.name
    }

    override fun onEndedRecognition() {
        current?.lastEventAt = SystemClock.elapsedRealtime()
    }

    override fun onReady() = Unit

    override fun onStartedRecognition() = Unit

    override fun onUpdatedRms(rms: Double) = Unit
}
