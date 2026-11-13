package com.roadmystery.app.voice

import com.roadmystery.app.story.ScenarioParser
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `a question cut at a pause is joined with what follows`() = runTest {
        val input = pieces(
            { started -> delay(500); started(); delay(1_000); "윤재희 씨" },
            { started -> delay(300); started(); delay(2_000); "수면제를 본 적 있나요?" },
        )
        assertEquals("윤재희 씨 수면제를 본 적 있나요?", input.listenForDrivingQuestion())
        // 물음표로 끝맺었으니 더 기다리지 않는다.
        assertEquals(3_800L, testScheduler.currentTime)
    }

    @Test
    fun `a finished question is not kept waiting for more`() = runTest {
        var listens = 0
        val input = pieces(
            { started -> listens++; started(); delay(2_000); "커피는 왜 두 잔 샀나요?" },
            { listens++; awaitCancellation() },
        )
        assertEquals("커피는 왜 두 잔 샀나요?", input.listenForQuestion())
        assertEquals(2_000L, testScheduler.currentTime)
        assertEquals(1, listens)
    }

    @Test
    fun `words with nothing after them are returned once the follow-up wait is over`() = runTest {
        var closed = false
        val input = pieces(
            { started -> started(); delay(1_000); "그날 밤에" },
            {
                try {
                    awaitCancellation()
                } finally {
                    closed = true
                }
            },
        )
        assertEquals("그날 밤에", input.listenForDrivingQuestion())
        assertEquals(2_500L, testScheduler.currentTime)
        assertTrue(closed)
    }

    @Test
    fun `an answer that there is no question is not kept waiting for more`() = runTest {
        var listens = 0
        val input = pieces(
            { started -> listens++; started(); delay(1_000); "아니요 괜찮아요" },
            { listens++; awaitCancellation() },
        )
        assertEquals("아니요 괜찮아요", input.listenForDrivingQuestion())
        assertEquals(1_000L, testScheduler.currentTime)
        assertEquals(1, listens)
    }

    @Test
    fun `an ending said after a pause is joined, not taken as an answer that there is no question`() = runTest {
        val input = pieces(
            { started -> started(); delay(1_000); "윤재희 씨 그날 밤 일을 말씀해" },
            { started -> delay(300); started(); delay(500); "주세요" },
        )
        assertEquals("윤재희 씨 그날 밤 일을 말씀해 주세요", input.listenForDrivingQuestion())
    }

    @Test
    fun `speech that begins after the follow-up wait is not joined`() = runTest {
        var listens = 0
        val input = pieces(
            { started -> listens++; started(); delay(1_000); "윤재희 씨" },
            { started -> listens++; delay(1_600); started(); delay(1_000); "수면제를 본 적 있나요?" },
        )
        assertEquals("윤재희 씨", input.listenForQuestion())
        assertEquals(2_500L, testScheduler.currentTime)
        assertEquals(2, listens)
    }

    @Test
    fun `listening cancelled during a follow-up closes the microphone and does not listen again`() = runTest {
        var listens = 0
        var closed = false
        val input = pieces(
            { started -> listens++; started(); delay(1_000); "윤재희 씨" },
            { started ->
                listens++
                try {
                    delay(500)
                    started()
                    awaitCancellation()
                } finally {
                    closed = true
                }
            },
            { listens++; awaitCancellation() },
        )
        val job = launch { input.listenForQuestion() }
        advanceTimeBy(2_000)
        job.cancelAndJoin()
        assertTrue(closed)
        assertEquals(2, listens)
    }

    @Test
    fun `only words that all say there is no question count as no question`() {
        assertTrue(isNoQuestion("아니요, 괜찮아요."))
        assertFalse(isNoQuestion("아니요, 윤재희 씨는요?"))
        assertFalse(isNoQuestion(""))
        assertFalse(isNoQuestion("?!"))
    }

    @Test
    fun `the follow-up wait is counted from when the recognizer is ready again`() = runTest {
        val input = pieces(
            { started -> started(); delay(1_000); "윤재희 씨" },
            { started -> delay(1_400); started(); delay(1_000); "수면제를 본 적 있나요?" },
            readyAfter = 400,
        )
        assertEquals("윤재희 씨 수면제를 본 적 있나요?", input.listenForQuestion())
        assertEquals(4_200L, testScheduler.currentTime)
    }

    @Test
    fun `a follow-up that fails keeps what was already heard`() = runTest {
        val input = pieces({ started -> started(); delay(1_000); "윤재희 씨" }, { null })
        assertEquals("윤재희 씨", input.listenForQuestion())
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun `no more than two follow-ups are joined`() = runTest {
        var listens = 0
        val piece: suspend (() -> Unit) -> String? = { started -> listens++; started(); delay(500); "그러니까" }
        val input = pieces(piece, piece, piece, piece)
        assertEquals("그러니까 그러니까 그러니까", input.listenForQuestion())
        assertEquals(3, listens)
    }

    @Test
    fun `words that end a sentence or say there is no question are taken as finished`() {
        assertTrue(isCompleteUtterance("수면제를 본 적 있나요? "))
        assertTrue(isCompleteUtterance("그건 말씀드릴 수 없어요."))
        assertTrue(isCompleteUtterance("아니요, 괜찮아요"))
        assertFalse(isCompleteUtterance("윤재희 씨"))
        assertFalse(isCompleteUtterance(""))
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

    /** 듣기를 부를 때마다 [reads]를 차례로 쓰는 입력. 다 쓰고 나면 말이 없다. */
    private fun pieces(vararg reads: suspend (() -> Unit) -> String?, readyAfter: Long = 0): SpeechInput {
        val left = ArrayDeque(reads.toList())
        return input(readyAfter) { started ->
            val read = left.removeFirstOrNull()
            if (read == null) awaitCancellation() else read(started)
        }
    }
}
