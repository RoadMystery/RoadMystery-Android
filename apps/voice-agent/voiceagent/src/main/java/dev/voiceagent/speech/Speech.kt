package dev.voiceagent.speech

/** The speech engine failed (initialization or playback). */
class SpeechException(message: String) : RuntimeException(message)

/**
 * Plays one line at a time. On-device Pleos TTS cuts a playing request off when the next one
 * arrives, so VoiceAgent waits for each line to finish before handing over the next.
 */
interface SpeechOutput {
    /**
     * Speaks [text] in [speaker]'s voice (a Pleos speaker type) and returns when playback has
     * ended. Cancelling stops playback; an engine error throws [SpeechException].
     */
    suspend fun speak(text: String, speaker: String)

    /** Stops playback at once (barge-in, 위험 상황 일시정지). */
    fun stop()
}

/** One spoken question from the player. */
fun interface SpeechInput {
    /** Listens until the player has finished; returns the recognized text, or null if nothing was heard. */
    suspend fun listen(): String?
}
