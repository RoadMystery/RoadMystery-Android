package dev.voiceagent.agent

import dev.voiceagent.llm.LlmClient
import dev.voiceagent.llm.LlmEvent
import dev.voiceagent.llm.LlmRequest
import dev.voiceagent.speech.SpeechException
import dev.voiceagent.speech.SpeechOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Speech output that takes [msPerLine] of (virtual) time per line and records when each started. */
class FakeSpeech(private val clock: () -> Long, private val msPerLine: Long = 500) : SpeechOutput {
    data class Spoken(val speaker: String, val text: String, val at: Long)

    val spoken = mutableListOf<Spoken>()
    var stops = 0
        private set
    var failWith: String? = null

    override suspend fun speak(text: String, speaker: String) {
        spoken += Spoken(speaker, text, clock())
        failWith?.let { throw SpeechException(it) }
        delay(msPerLine)
    }

    override fun stop() {
        stops++
    }
}

/** Answers each request with the next script of (delay before the event, event) pairs. */
class ScriptedLlm(vararg scripts: List<Pair<Long, LlmEvent>>) : LlmClient {
    private val pending = ArrayDeque(scripts.toList())
    val requests = mutableListOf<LlmRequest>()
    var cancelled = 0
        private set

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        requests += request
        val script = pending.removeFirstOrNull() ?: error("no script for request ${requests.size}")
        try {
            for ((wait, event) in script) {
                delay(wait)
                emit(event)
            }
        } catch (e: CancellationException) {
            cancelled++
            throw e
        }
    }
}

fun delta(text: String) = LlmEvent.Delta(text)

fun done() = LlmEvent.Done("end_turn", "fake", 1, 1)

/** A one-delta answer that arrives after 100 ms. */
fun answer(text: String) = listOf(100L to delta(text), 10L to done())
