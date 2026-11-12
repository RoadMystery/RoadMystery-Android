package com.roadmystery.app.audio

import com.roadmystery.app.story.Scene
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecoverableTtsOutputTest {
    @Test
    fun `missing stop acknowledgment reconnects and ignores callbacks from the closed connection`() = runTest {
        val connections = mutableListOf<RecordingTtsConnection>()
        val output = RecoverableTtsOutput(backgroundScope) { RecordingTtsConnection(it).also(connections::add) }
        val first = backgroundScope.async { output.speak("첫 문장", "sanghyeok") }
        runCurrent()
        val old = connections.single()

        output.stop()
        val next = backgroundScope.async { output.speak("다음 문장", "sanghyeok") }
        runCurrent()
        advanceTimeBy(2_999)
        assertEquals(1, connections.size)
        assertTrue(first.isCancelled)
        assertFalse(next.isCompleted)

        advanceTimeBy(1)
        runCurrent()
        val replacement = connections.last()
        assertEquals(2, connections.size)
        assertTrue(old.closed)
        assertEquals(listOf("다음 문장"), replacement.sent)
        old.callbacks.done()
        old.callbacks.stopped()
        old.callbacks.failed(IllegalStateException("late SDK error"))
        runCurrent()
        assertFalse(next.isCompleted)

        replacement.callbacks.done()
        runCurrent()
        next.await()
    }

    @Test
    fun `a confirmed stop reuses the initialized connection`() = runTest {
        val connections = mutableListOf<RecordingTtsConnection>()
        val output = RecoverableTtsOutput(backgroundScope) { RecordingTtsConnection(it).also(connections::add) }
        backgroundScope.async { output.speak("첫 문장", "sanghyeok") }
        runCurrent()
        val connection = connections.single()
        output.stop()
        val next = backgroundScope.async { output.speak("재개한 문장", "sanghyeok") }
        runCurrent()
        assertEquals(listOf("첫 문장"), connection.sent)

        connection.callbacks.stopped()
        runCurrent()
        assertEquals(listOf("첫 문장", "재개한 문장"), connection.sent)
        assertEquals(1, connections.size)
        connection.callbacks.done()
        runCurrent()
        next.await()
    }

    @Test
    fun `playback retry recovers when the service never starts speaking`() = runTest {
        val connections = mutableListOf<RecordingTtsConnection>()
        val output = RecoverableTtsOutput(backgroundScope) { RecordingTtsConnection(it).also(connections::add) }
        val player = SentenceAudioPlayer(backgroundScope, output)
        player.play(Scene("briefing", "브리핑", durationSec = 5, script = listOf("다시 읽을 문장"), trigger = "briefing"))
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(PlaybackPhase.ERROR, player.status.value.phase)
        assertTrue(connections.single().closed)

        player.retry()
        runCurrent()
        val replacement = connections.last()
        assertEquals(2, connections.size)
        assertEquals(listOf("다시 읽을 문장"), replacement.sent)
        replacement.callbacks.done()
        runCurrent()
        assertEquals(PlaybackPhase.IDLE, player.status.value.phase)
    }

    private class RecordingTtsConnection(val callbacks: RecoverableTtsOutput.Callbacks) : TtsConnection {
        val sent = mutableListOf<String>()
        var closed = false

        override fun initialize() = callbacks.ready()

        override fun speak(text: String, speaker: String) {
            sent += text
        }

        override fun stop() = Unit

        override fun close() {
            closed = true
        }
    }
}
