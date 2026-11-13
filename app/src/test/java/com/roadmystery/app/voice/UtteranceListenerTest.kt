package com.roadmystery.app.voice

import ai.pleos.playground.stt.constant.ErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceListenerTest {
    private val results = mutableListOf<String?>()
    private val waits = mutableListOf<Pair<Long, () -> Unit>>()
    private var speech = 0
    private val listener = UtteranceListener(
        onListening = {},
        onSpeechDetected = { speech++ },
        later = { delayMs, block -> waits += delayMs to block },
        warn = {},
        done = { results += it },
    )

    @Test
    fun `the final result that follows the end notice is the one returned`() {
        listener.onUpdated("윤재희 씨", false)
        listener.onEndedRecognition()
        assertTrue("끝났다는 알림만으로는 끝내지 않는다", results.isEmpty())

        listener.onUpdated("윤재희 씨", true)
        assertEquals(listOf<String?>("윤재희 씨"), results)

        // 기다리던 시간이 지나도 한 번 더 끝내지 않는다.
        waits.single().second()
        assertEquals(1, results.size)
    }

    @Test
    fun `without a final result the words heard so far are returned after the wait`() {
        listener.onUpdated("커피는 왜", false)
        listener.onEndedRecognition()
        assertEquals(1_000L, waits.single().first)

        waits.single().second()
        assertEquals(listOf<String?>("커피는 왜"), results)
    }

    @Test
    fun `an error after the end notice keeps the words heard so far`() {
        listener.onUpdated("커피는 왜 두 잔 샀나요?", false)
        listener.onEndedRecognition()
        listener.onError(ErrorType.NETWORK_ERROR)
        assertEquals(listOf<String?>("커피는 왜 두 잔 샀나요?"), results)
    }

    @Test
    fun `an error while listening returns nothing`() {
        listener.onUpdated("커피는", false)
        listener.onError(ErrorType.MIC_ERROR)
        assertEquals(listOf<String?>(null), results)
    }

    @Test
    fun `what arrives after the result is ignored`() {
        listener.onUpdated("멈춰", true)
        val heard = speech

        listener.onUpdated("다음 말", true)
        listener.onUpdatedEpdData(0, 32_000)
        listener.onEndedRecognition()
        waits.forEach { it.second() }

        assertEquals(listOf<String?>("멈춰"), results)
        assertEquals(heard, speech)
    }

    @Test
    fun `a closed listener returns nothing`() {
        listener.onUpdated("윤재희 씨", false)
        listener.close()
        listener.onUpdated("윤재희 씨", true)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `a listening stopped from outside returns nothing, whatever was heard`() {
        listener.onUpdated("윤재희 씨", false)
        listener.abort()
        assertEquals(listOf<String?>(null), results)

        listener.onUpdated("윤재희 씨", true)
        assertEquals(1, results.size)
    }

    @Test
    fun `blank words count as nothing heard`() {
        listener.onUpdated("  ", true)
        assertEquals(listOf<String?>(null), results)
        assertEquals(0, speech)
    }
}
