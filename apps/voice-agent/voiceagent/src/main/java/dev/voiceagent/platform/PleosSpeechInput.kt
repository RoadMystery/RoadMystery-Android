package dev.voiceagent.platform

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.SystemClock
import dev.voiceagent.speech.SpeechException
import dev.voiceagent.speech.SpeechInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * [SpeechInput] on the Pleos on-device SpeechToText (SDK 3.3.1). The engine finalizes at every
 * sentence pause (both halves of each two-sentence question arrived as separate final results on
 * the emulator), so results are collected until it has been quiet for [graceMs] after the last
 * final one and are joined into one question.
 */
class PleosSpeechInput private constructor(
    private val stt: SpeechToText,
    private val graceMs: Long,
    private val maxListenMs: Long,
) : SpeechInput {
    private class Session {
        val finals = CopyOnWriteArrayList<String>()
        val updates = Channel<Unit>(Channel.CONFLATED)

        @Volatile var partial = ""

        @Volatile var ended = false

        @Volatile var error: ErrorType? = null

        @Volatile var lastEventAt = SystemClock.elapsedRealtime()

        fun text(): String = (finals + partial).filter { it.isNotBlank() }.joinToString(" ").trim()

        fun touch() {
            lastEventAt = SystemClock.elapsedRealtime()
            updates.trySend(Unit)
        }
    }

    private val current = AtomicReference<Session?>(null)

    /** Completed by the end (or error) of a recognition that stop() cut off; the next one waits for it. */
    private val settling = AtomicReference<CompletableDeferred<Unit>?>(null)

    /** After release the SDK would bind its service again on stop(), so everything is a no-op. */
    @Volatile private var released = false

    private val listener = object : ResultListener {
        override fun onUpdated(stt: String, completed: Boolean) {
            val session = current.get() ?: return
            if (completed) {
                if (stt.isNotBlank()) session.finals += stt
                session.partial = ""
            } else {
                session.partial = stt
            }
            session.touch()
        }

        override fun onUpdatedEpdData(on: Long, off: Long) {
            current.get()?.touch()
        }

        override fun onEndedRecognition() {
            settling.getAndSet(null)?.complete(Unit)
            current.get()?.apply { ended = true }?.touch()
        }

        override fun onError(errorType: ErrorType) {
            settling.getAndSet(null)?.complete(Unit)
            current.get()?.apply { error = errorType }?.touch()
        }

        override fun onReady() = Unit

        override fun onStartedRecognition() = Unit

        override fun onUpdatedRms(rms: Double) = Unit
    }

    /** Listens on the car microphone (not yet verified on a device; see the README). */
    override suspend fun listen(): String? = recognize(start = { stt.request() }, finish = ::stopMicrophone)

    /**
     * Recognizes 16 kHz 16-bit mono PCM fed at real-time pace like a microphone, so the whole voice
     * loop can run on an emulator without one.
     */
    suspend fun recognizePcm(pcm: ByteArray): String? = recognize(start = { feed(pcm) }, finish = {})

    /** Stops recognition and unbinds the engine; later calls do nothing. */
    fun release() {
        if (released) return
        released = true
        current.set(null)
        stt.removeListener(listener)
        stt.release()
    }

    private fun stopMicrophone(session: Session) {
        // A recognition cut off mid-way reports its end late; the next one must not take it as its own.
        if (!session.ended && session.error == null) settling.set(CompletableDeferred())
        stt.stop()
    }

    private suspend fun recognize(start: suspend () -> Unit, finish: (Session) -> Unit): String? {
        if (released) throw SpeechException("STT released")
        settling.get()?.let { withTimeoutOrNull(STOP_SETTLE_MS) { it.await() } }
        settling.set(null)
        val session = Session()
        current.set(session)
        try {
            start()
            withTimeoutOrNull(maxListenMs) { awaitQuiet(session) }
            val text = session.text()
            val error = session.error
            // TIMEOUT means nobody spoke; any other error without a result is an engine problem.
            if (text.isEmpty() && error != null && error != ErrorType.TIMEOUT) throw SpeechException("STT ${error.name}")
            return text.ifEmpty { null }
        } finally {
            current.compareAndSet(session, null)
            if (!released) finish(session)
        }
    }

    private suspend fun awaitQuiet(session: Session) {
        while (session.error == null) {
            if (session.ended && session.finals.isEmpty()) {
                if (!awaitLateFinal(session)) return
                continue
            }
            if (session.finals.isNotEmpty() && session.partial.isBlank()) {
                val quietFor = SystemClock.elapsedRealtime() - session.lastEventAt
                if (quietFor >= graceMs) return
                withTimeoutOrNull(graceMs - quietFor) { session.updates.receive() }
            } else {
                session.updates.receive()
            }
        }
    }

    /** The end can arrive just before the last final result; true if one came within the grace period. */
    private suspend fun awaitLateFinal(session: Session): Boolean {
        val deadline = SystemClock.elapsedRealtime() + graceMs
        while (session.finals.isEmpty() && session.error == null) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0 || withTimeoutOrNull(left) { session.updates.receive() } == null) return false
        }
        return session.finals.isNotEmpty()
    }

    private suspend fun feed(pcm: ByteArray) {
        for (offset in pcm.indices step BYTES_PER_CHUNK) {
            delay(CHUNK_MS) // A microphone delivers each 100 ms of audio after it has been spoken.
            stt.sendAudio(pcm.copyOfRange(offset, minOf(offset + BYTES_PER_CHUNK, pcm.size)))
        }
        stt.completeAudioSend()
    }

    companion object {
        /** How long to keep listening after a final result, for a second sentence. */
        const val DEFAULT_GRACE_MS = 1_000L
        const val DEFAULT_MAX_LISTEN_MS = 15_000L
        private const val BYTES_PER_CHUNK = 3_200 // 100 ms of 16 kHz, 16-bit, mono audio
        private const val CHUNK_MS = 100L
        private const val INIT_TIMEOUT_MS = 15_000L
        private const val STOP_SETTLE_MS = 500L

        /** Connects to the on-device recognizer (Korean). */
        suspend fun create(
            context: Context,
            graceMs: Long = DEFAULT_GRACE_MS,
            maxListenMs: Long = DEFAULT_MAX_LISTEN_MS,
        ): PleosSpeechInput {
            val stt = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
            val error = try {
                withTimeoutOrNull(INIT_TIMEOUT_MS) { initialize(stt) } ?: "timeout"
            } catch (e: CancellationException) {
                stt.release()
                throw e
            }
            if (error.isNotEmpty()) {
                stt.release()
                throw SpeechException("STT initialize: $error")
            }
            stt.setLocale(Locale.KOREA)
            return PleosSpeechInput(stt, graceMs, maxListenMs).also { stt.addListener(it.listener) }
        }

        /** Empty on success, else the engine's error name. */
        private suspend fun initialize(stt: SpeechToText): String = suspendCancellableCoroutine { continuation ->
            stt.initialize(
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
    }
}
