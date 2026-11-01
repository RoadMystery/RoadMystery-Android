package dev.voiceagent.speechbench

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Korean speakers documented for TextToSpeech.setSpeakerType (narrator + three suspects). */
val KOREAN_SPEAKERS = listOf("seoyoon", "eunwoo", "haeun", "sanghyeok")

private const val DEFAULT_SPEAKER = "haeun"
private const val SAMPLE_LINE = "그 시각에는 지하 저장고에서 와인을 고르고 있었습니다. 다른 것은 보지 못했습니다."
private const val FILLER_LINE = "음..."
private val TWO_SENTENCES = listOf("지하 저장고에 있었습니다. ", "불이 꺼져서 아무것도 보지 못했습니다.")
/** Stand-in for the LLM still generating the second sentence. */
private const val SIMULATED_LLM_GAP_MS = 1_000L
/** Lets late callbacks of the previous utterance drain before the next one starts. */
private const val SETTLE_BEFORE_ATTEMPT_MS = 500L
/** After a terminal callback, playback counts as over once no callback arrives for this long. */
private const val QUIET_AFTER_END_MS = 700L
private const val POLL_MS = 50L
private const val INIT_TIMEOUT_S = 15L
private const val START_TIMEOUT_S = 15L
private const val DONE_TIMEOUT_MS = 30_000L

/** How the parts of one answer are handed to the engine. */
private enum class Delivery {
    /** One speak() call per part, isFinal=false until the last part (SDK streaming input). */
    STREAMED,

    /** Each part is its own speak(part, isFinal=true) request, sent as it becomes available. */
    QUEUED,
}

/**
 * Measures how fast the on-device engine starts talking for each Korean speaker, whether a short
 * filler plays quickly, and how a two-sentence answer behaves when the second sentence arrives
 * a second later (streamed input vs. separate requests). Blocking: call from a background thread.
 */
class TtsBench(private val context: Context, private val log: (String) -> Unit) {

    fun run(onResult: (TtsResult) -> Unit): List<TtsResult> {
        val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
        val recorder = TtsRecorder()
        try {
            val initError = initialize(tts)
            if (initError != null) {
                log("TTS 초기화 실패: $initError")
                return listOf(TtsResult("initialize", "-", "", null, null, null, "", initError).also(onResult))
            }
            tts.setLocale(Locale.KOREA)
            tts.addEventListener(recorder)
            val steps = buildList<() -> TtsResult> {
                add { speak(tts, recorder, "warmup", DEFAULT_SPEAKER, listOf(SAMPLE_LINE)) }
                KOREAN_SPEAKERS.forEach { speaker ->
                    add { speak(tts, recorder, "speaker", speaker, listOf(SAMPLE_LINE)) }
                }
                add { speak(tts, recorder, "filler", DEFAULT_SPEAKER, listOf(FILLER_LINE)) }
                add { speak(tts, recorder, "streamed", DEFAULT_SPEAKER, TWO_SENTENCES, Delivery.STREAMED) }
                add { speak(tts, recorder, "queued", DEFAULT_SPEAKER, TWO_SENTENCES, Delivery.QUEUED) }
            }
            return steps.map { step -> step().also(onResult) }
        } finally {
            tts.removeEventListener(recorder)
            tts.release()
        }
    }

    private fun initialize(tts: TextToSpeech): String? {
        val done = CountDownLatch(1)
        var error: String? = null
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() = done.countDown()

            override fun onFailure(errorType: ErrorType) {
                error = errorType.name
                done.countDown()
            }
        })
        return if (done.await(INIT_TIMEOUT_S, TimeUnit.SECONDS)) error else "timeout"
    }

    private fun speak(
        tts: TextToSpeech,
        recorder: TtsRecorder,
        label: String,
        speaker: String,
        parts: List<String>,
        delivery: Delivery = Delivery.STREAMED,
    ): TtsResult {
        SystemClock.sleep(SETTLE_BEFORE_ATTEMPT_MS)
        tts.setSpeakerType(speaker)
        val attempt = recorder.begin()
        val requestedAt = SystemClock.elapsedRealtime()
        parts.forEachIndexed { index, part ->
            if (index > 0) SystemClock.sleep(SIMULATED_LLM_GAP_MS)
            tts.speak(part, delivery == Delivery.QUEUED || index == parts.lastIndex)
        }
        val started = attempt.started.await(START_TIMEOUT_S, TimeUnit.SECONDS)
        val ended = started && attempt.ended.await(DONE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (ended) awaitQuiet(attempt)

        val audibleFrom = attempt.soundAt ?: attempt.startedAt
        val lastDoneAt = attempt.lastDoneAt
        val result = TtsResult(
            label = label,
            speaker = speaker,
            text = parts.joinToString(""),
            startLatencyMs = attempt.startedAt?.minus(requestedAt),
            soundLatencyMs = attempt.soundAt?.minus(requestedAt),
            playbackMs = if (audibleFrom != null && lastDoneAt != null) lastDoneAt - audibleFrom else null,
            events = attempt.events(),
            error = attempt.error ?: when {
                !started -> "start timeout"
                !ended -> "done timeout"
                else -> null
            },
        )
        log(
            "TTS $label/$speaker: onStart ${result.startLatencyMs}ms, 소리 ${result.soundLatencyMs}ms, " +
                "재생 ${result.playbackMs}ms (${result.events}) ${result.error ?: ""}",
        )
        return result
    }

    /**
     * Separate requests may each report an end, or a later request may cut an earlier one off
     * without any callback; either way playback is over once callbacks stop arriving.
     */
    private fun awaitQuiet(attempt: TtsAttempt) {
        val deadline = SystemClock.elapsedRealtime() + DONE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline &&
            SystemClock.elapsedRealtime() - attempt.lastEventAt < QUIET_AFTER_END_MS
        ) {
            SystemClock.sleep(POLL_MS)
        }
    }
}

/** Callback state for one answer. Written from SDK callback threads, read by the bench thread. */
private class TtsAttempt(startedAt: Long) {
    val started = CountDownLatch(1)
    /** Opened by the first terminal callback (onDone, onStop, or onError). */
    val ended = CountDownLatch(1)
    val starts = AtomicInteger()
    val dones = AtomicInteger()
    val stops = AtomicInteger()

    @Volatile var startedAt: Long? = null
    @Volatile var soundAt: Long? = null
    @Volatile var lastDoneAt: Long? = null
    @Volatile var lastEventAt: Long = startedAt
    @Volatile var error: String? = null

    fun events(): String = "start=${starts.get()} done=${dones.get()} stop=${stops.get()}"
}

private class TtsRecorder : EventListener {
    @Volatile private var current: TtsAttempt? = null

    fun begin(): TtsAttempt = TtsAttempt(SystemClock.elapsedRealtime()).also { current = it }

    override fun onStart() {
        val attempt = current ?: return
        val now = SystemClock.elapsedRealtime()
        attempt.starts.incrementAndGet()
        if (attempt.startedAt == null) attempt.startedAt = now
        attempt.lastEventAt = now
        attempt.started.countDown()
    }

    override fun onDone() {
        val attempt = current ?: return
        val now = SystemClock.elapsedRealtime()
        attempt.dones.incrementAndGet()
        attempt.lastDoneAt = now
        attempt.lastEventAt = now
        attempt.ended.countDown()
    }

    override fun onStop() {
        val attempt = current ?: return
        attempt.stops.incrementAndGet()
        attempt.lastEventAt = SystemClock.elapsedRealtime()
        attempt.ended.countDown()
    }

    override fun onError(errorType: ErrorType) {
        val attempt = current ?: return
        attempt.error = errorType.name
        attempt.started.countDown()
        attempt.ended.countDown()
    }

    override fun onReady() = Unit

    override fun onUpdatedRms(rms: Double) {
        val attempt = current ?: return
        if (rms <= 0.0) return
        val now = SystemClock.elapsedRealtime()
        if (attempt.soundAt == null) attempt.soundAt = now
        attempt.lastEventAt = now
    }
}
