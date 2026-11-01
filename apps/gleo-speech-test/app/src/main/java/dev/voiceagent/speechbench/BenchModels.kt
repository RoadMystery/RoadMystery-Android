package dev.voiceagent.speechbench

/** One STT fixture from assets/stt/cases.json (made by scripts/make-stt-fixtures.ps1). */
data class SttCase(val id: String, val file: String, val expected: String)

data class SttResult(
    val id: String,
    val expected: String,
    /** All final segments joined; the engine splits an utterance at pauses between sentences. */
    val recognized: String,
    val segments: Int,
    val cer: Double?,
    val audioMs: Long,
    /** From the first audio chunk to the first non-empty partial result. */
    val firstPartialMs: Long?,
    /**
     * From the end of speech (EPD 'off' offset) to the last final result. Null when the engine
     * split the utterance into several segments: its offsets appear to restart per segment.
     */
    val finalAfterSpeechEndMs: Long?,
    /** From completeAudioSend() to the last final result; negative if the engine finished first. */
    val finalAfterAudioEndMs: Long?,
    val error: String?,
)

data class TtsResult(
    val label: String,
    val speaker: String,
    val text: String,
    /** From the first speak() call to onStart (request accepted; not necessarily audible yet). */
    val startLatencyMs: Long?,
    /** From the first speak() call to the first playback RMS above zero (sound is actually playing). */
    val soundLatencyMs: Long?,
    /** From the first audible sound (or onStart if no sound was reported) to the last onDone. */
    val playbackMs: Long?,
    /** Callback counts, e.g. "start=2 done=2 stop=0", to see whether requests queue or interrupt. */
    val events: String,
    val error: String?,
)
