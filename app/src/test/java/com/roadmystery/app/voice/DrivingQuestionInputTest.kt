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
    fun `no question continues even when a previous suspect is available`() {
        val scenario = ScenarioParser.parse(File("src/main/assets/scenarios/midnight_rest_area.json").readText())
        val suspects = scenario.suspects
        assertNull(resolveDrivingQuestionSuspect("아니요, 괜찮아요.", suspects, suspects.first().id))
    }

    private fun input(read: suspend (() -> Unit) -> String?) = object : SpeechInput {
        override suspend fun listen(onSpeechDetected: () -> Unit) = read(onSpeechDetected)
        override fun cancel() = Unit
    }
}
