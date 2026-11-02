package dev.voiceagent.platform

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import dev.voiceagent.speech.SpeechException
import dev.voiceagent.speech.SpeechOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * [SpeechOutput] on the Pleos on-device TextToSpeech (SDK 3.3.1). Each line is one
 * `speak(text, isFinal = true)` request and the next waits for onDone: on-device, a request sent
 * while another plays cuts it off, and split `isFinal = false` input does not start any earlier
 * (docs/voice-agent/gleo-sdk-findings.md). Lines must not be spoken concurrently.
 */
class PleosSpeechOutput private constructor(private val tts: TextToSpeech) : SpeechOutput {
    private class Playback {
        @Volatile var started = false
        val ended = CompletableDeferred<Unit>()
    }

    private val playing = AtomicReference<Playback?>(null)

    /** Completed by the engine's last callback for a stopped line; the next line waits for it. */
    private val settling = AtomicReference<CompletableDeferred<Unit>?>(null)

    /** After release the SDK would bind its service again on stop(), so everything is a no-op. */
    @Volatile private var released = false

    private val listener = object : EventListener {
        override fun onStart() {
            playing.get()?.started = true
        }

        override fun onDone() = ended(null)

        override fun onStop() = ended(null)

        override fun onError(errorType: ErrorType) = ended(SpeechException("TTS ${errorType.name}"))

        override fun onReady() = Unit

        override fun onUpdatedRms(rms: Double) = Unit
    }

    private fun ended(error: SpeechException?) {
        settling.getAndSet(null)?.complete(Unit)
        val playback = playing.get() ?: return
        // An end before this line's onStart belongs to the line that was stopped.
        if (!playback.started && error == null) return
        if (error == null) playback.ended.complete(Unit) else playback.ended.completeExceptionally(error)
    }

    override suspend fun speak(text: String, speaker: String) {
        if (released) throw SpeechException("TTS released")
        settling.get()?.let { withTimeoutOrNull(STOP_SETTLE_MS) { it.await() } }
        settling.set(null)
        val playback = Playback()
        playing.set(playback)
        try {
            tts.setSpeakerType(speaker)
            tts.speak(text, true)
            // onDone can go missing; never hold the conversation longer than the line could take.
            if (withTimeoutOrNull(maxPlaybackMs(text)) { playback.ended.await() } == null) stop()
        } catch (e: CancellationException) {
            stop()
            throw e
        } finally {
            playing.compareAndSet(playback, null)
        }
    }

    override fun stop() {
        if (released) return
        val stopped = playing.getAndSet(null)
        if (stopped != null) {
            settling.compareAndSet(null, CompletableDeferred())
            stopped.ended.complete(Unit)
        }
        tts.stop()
    }

    /** Ends playback and unbinds the engine; later calls do nothing. */
    fun release() {
        if (released) return
        released = true
        playing.getAndSet(null)?.ended?.complete(Unit)
        tts.removeEventListener(listener)
        tts.release()
    }

    companion object {
        private const val INIT_TIMEOUT_MS = 15_000L
        private const val STOP_SETTLE_MS = 500L
        private const val PLAYBACK_BASE_MS = 3_000L
        private const val PLAYBACK_MS_PER_CHAR = 300L

        /** Connects to the on-device engine. Create it at game start: the first line is the slowest. */
        suspend fun create(context: Context): PleosSpeechOutput {
            val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
            val error = try {
                withTimeoutOrNull(INIT_TIMEOUT_MS) { initialize(tts) } ?: "timeout"
            } catch (e: CancellationException) {
                tts.release()
                throw e
            }
            if (error.isNotEmpty()) {
                tts.release()
                throw SpeechException("TTS initialize: $error")
            }
            tts.setLocale(Locale.KOREA)
            return PleosSpeechOutput(tts).also { tts.addEventListener(it.listener) }
        }

        /** Empty on success, else the engine's error name. */
        private suspend fun initialize(tts: TextToSpeech): String = suspendCancellableCoroutine { continuation ->
            tts.initialize(
                object : OnInitializeListener {
                    override fun onSuccess() {
                        if (continuation.isActive) continuation.resume("")
                    }

                    override fun onFailure(errorType: ErrorType) {
                        if (continuation.isActive) continuation.resume(errorType.name)
                    }
                },
            )
        }

        private fun maxPlaybackMs(text: String): Long = PLAYBACK_BASE_MS + PLAYBACK_MS_PER_CHAR * text.length
    }
}
