package com.roadmystery.app.voice

import com.roadmystery.app.story.ScenarioParser
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrivingQuestionInputTest {
    @Test
    fun `silence closes the microphone window`() = runTest {
        var closed = false
        val input = input {
            try {
                awaitCancellation()
            } finally {
                closed = true
            }
        }
        assertNull(input.listenForDrivingQuestion())
        assertEquals(3_000L, testScheduler.currentTime)
        assertTrue(closed)
    }

    @Test
    fun `a question begun before the deadline can finish after it`() = runTest {
        val input = input { started ->
            delay(2_900)
            started()
            delay(2_000)
            "서진 씨 어디에 있었어요?"
        }
        assertEquals("서진 씨 어디에 있었어요?", input.listenForDrivingQuestion())
        assertEquals(4_900L, testScheduler.currentTime)
    }

    @Test
    fun `the window is counted from when the recognizer is ready`() = runTest {
        val input = input(readyAfter = 2_000) { started ->
            delay(2_500)
            started()
            delay(1_000)
            "서진 씨 어디에 있었어요?"
        }
        assertEquals("서진 씨 어디에 있었어요?", input.listenForDrivingQuestion())
        assertEquals(5_500L, testScheduler.currentTime)
    }

    @Test
    fun `a recognizer that never gets ready closes the window`() = runTest {
        val input = input(readyAfter = null) { awaitCancellation() }
        assertNull(input.listenForDrivingQuestion())
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    fun `a question asked while stopped ends after six seconds of silence`() = runTest {
        assertNull(input { awaitCancellation() }.listenForQuestion())
        assertEquals(6_000L, testScheduler.currentTime)
    }

    @Test
    fun `a question asked while stopped may begin late and still finish`() = runTest {
        val input = input { started ->
            delay(5_000)
            started()
            delay(3_000)
            "커피는 왜 두 잔 샀나요?"
        }
        assertEquals("커피는 왜 두 잔 샀나요?", input.listenForQuestion())
        assertEquals(8_000L, testScheduler.currentTime)
    }

    @Test
    fun `no question continues even when a previous suspect is available`() {
        val scenario = ScenarioParser.parse(File("src/main/assets/scenarios/midnight_rest_area.json").readText())
        val suspects = scenario.suspects
        assertNull(resolveDrivingQuestionSuspect("아니요, 괜찮아요.", suspects, suspects.first().id))
    }

    /** [readyAfter] ms 뒤에 들을 준비가 됐다고 알리는 입력. null이면 끝내 준비되지 않는다. */
    private fun input(readyAfter: Long? = 0, read: suspend (() -> Unit) -> String?) = object : SpeechInput {
        override suspend fun listen(onListening: () -> Unit, onSpeechDetected: () -> Unit): String? {
            if (readyAfter == null) awaitCancellation()
            delay(readyAfter)
            onListening()
            return read(onSpeechDetected)
        }

        override fun cancel() = Unit
    }
}
