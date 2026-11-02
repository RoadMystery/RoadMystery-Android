package dev.voiceagent.agent

import dev.voiceagent.content.Scenario
import dev.voiceagent.content.Suspect
import dev.voiceagent.identify.SuspectIdentifier
import dev.voiceagent.llm.ChatMessage
import dev.voiceagent.llm.LlmClient
import dev.voiceagent.llm.LlmEvent
import dev.voiceagent.llm.LlmRequest
import dev.voiceagent.llm.LlmTask
import dev.voiceagent.llm.ProxyContract
import dev.voiceagent.llm.Role
import dev.voiceagent.prompt.SummaryPrompt
import dev.voiceagent.prompt.SuspectPrompt
import dev.voiceagent.prompt.Testimony
import dev.voiceagent.speech.SpeechException
import dev.voiceagent.speech.SpeechInput
import dev.voiceagent.speech.SpeechOutput
import dev.voiceagent.text.StreamingSentenceSplitter
import dev.voiceagent.text.phraseHits
import dev.voiceagent.text.pythonStrip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * F-05 심문 and F-06 수사 수첩 요약 by voice: picks the suspect, assembles the prompt from the clues
 * found so far, streams the LLM answer and speaks it sentence by sentence.
 *
 * One turn runs at a time. A new [ask] or [summarize], or [stop], cuts off the turn in progress
 * (barge-in). [state] is immutable and observable.
 */
class VoiceAgent(
    private val scenario: Scenario,
    private val llm: LlmClient,
    private val speech: SpeechOutput,
    private val config: AgentConfig = AgentConfig(),
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val random: Random = Random.Default,
) {
    private val identifier = SuspectIdentifier.forCase(scenario.case)
    private val clueIds = scenario.case.clues.map { it.id }.toSet()
    private val mutableState = MutableStateFlow(AgentState())
    private val activeTurn = AtomicReference<Job?>(null)

    val state: StateFlow<AgentState> = mutableState.asStateFlow()

    fun unlockClue(clueId: String) {
        require(clueId in clueIds) { "unknown clue id: $clueId" }
        mutableState.update { it.copy(unlockedClues = it.unlockedClues + clueId) }
    }

    /** Sets the suspect being questioned, e.g. from a touch on the suspect list; null clears it. */
    fun selectSuspect(suspectId: String?) {
        require(suspectId == null || suspectId in scenario.suspects) { "unknown suspect id: $suspectId" }
        mutableState.update { it.copy(currentSuspect = suspectId) }
    }

    /** Starts over: stops speaking and forgets the suspect, the clues and the notebook. */
    fun reset() {
        stop()
        mutableState.update { AgentState(game = it.game + 1) }
    }

    /** F-05: answers [utterance] in the voice of the suspect it is addressed to. */
    suspend fun ask(utterance: String): AgentReply = runTurn { progress -> askTurn(utterance, progress) }

    /**
     * Listens to the player and answers what was heard. Listening is part of the turn, so it cuts
     * off the answer being spoken and [stop] ends it; a failing recognizer counts as not heard.
     */
    suspend fun listenAndAsk(input: SpeechInput): AgentReply = runTurn { progress ->
        val heard = try {
            input.listen()
        } catch (e: SpeechException) {
            progress.update { it.copy(speechError = e.message) }
            null
        }
        askTurn(heard.orEmpty(), progress)
    }

    /** F-06: reads the notebook back in the narrator's voice ("지금까지 정리해줘"). */
    suspend fun summarize(): AgentReply = runTurn { progress -> summaryTurn(progress) }

    /** Stops speech and the request in flight at once (barge-in, 위험 상황 일시정지). */
    fun stop() {
        activeTurn.get()?.cancel()
        speech.stop()
    }

    private suspend fun runTurn(block: suspend (TurnProgress) -> AgentReply): AgentReply = coroutineScope {
        val progress = TurnProgress(mutableState.value.game)
        val turn = async(start = CoroutineStart.LAZY) { block(progress) }
        activeTurn.getAndSet(turn)?.cancelAndJoin()
        try {
            turn.await()
        } catch (e: CancellationException) {
            ensureActive() // The caller itself was cancelled, not just this turn.
            progress.reply(ReplyKind.INTERRUPTED)
        } finally {
            activeTurn.compareAndSet(turn, null)
        }
    }

    private suspend fun askTurn(utterance: String, progress: TurnProgress): AgentReply {
        val question = pythonStrip(utterance).take(ProxyContract.MAX_MESSAGE_CHARS)
        val state = mutableState.value
        if (question.isEmpty()) {
            val suspect = state.currentSuspect?.let(scenario::suspect)
            return speakLine(progress, ReplyKind.NOT_UNDERSTOOD, suspect, suspect?.fallbackNotUnderstood ?: NarratorLines.NOT_UNDERSTOOD)
        }
        val suspectId = identifier.identify(question, state.currentSuspect)
            ?: return speakLine(progress, ReplyKind.ASK_WHO, null, NarratorLines.askWho(scenario.case))
        val suspect = scenario.suspect(suspectId)
        mutableState.update { if (it.game == progress.game) it.copy(currentSuspect = suspectId) else it }
        progress.update { it.copy(suspectId = suspectId) }
        val request = LlmRequest(
            task = LlmTask.INTERROGATION,
            system = SuspectPrompt.render(scenario.case, suspect, state.unlockedClues, config.variant, scenario.suspectTemplate),
            messages = conversation(state.notebook, suspectId, question),
            requestId = UUID.randomUUID().toString(),
        )
        val answer = try {
            speakAnswer(progress, request, suspect.speaker, suspect.filler, config.maxAnswerSentences)
        } catch (e: CancellationException) {
            // The player cut in: keep what they heard so a follow-up question has its context.
            remember(progress.game, suspectId, question, progress.snapshot.sentences)
            throw e
        }
        if (answer.sentences.isNotEmpty()) {
            remember(progress.game, suspectId, question, answer.sentences)
            return progress.reply(ReplyKind.ANSWER)
        }
        if (answer.guarded) {
            val line = suspect.deflections.randomOrNull(random) ?: suspect.fallbackNotUnderstood
            remember(progress.game, suspectId, question, listOf(line))
            return speakLine(progress, ReplyKind.DEFLECTION, suspect, line)
        }
        return speakLine(progress, ReplyKind.FALLBACK, suspect, suspect.fallbackOffline)
    }

    private suspend fun summaryTurn(progress: TurnProgress): AgentReply {
        val state = mutableState.value
        if (state.unlockedClues.isEmpty() && state.notebook.isEmpty()) {
            return speakLine(progress, ReplyKind.OVERVIEW, null, SummaryPrompt.overviewFallback(scenario.case))
        }
        val request = LlmRequest(
            task = LlmTask.SUMMARY,
            system = SummaryPrompt.render(scenario.case, scenario.summaryTemplate, state.unlockedClues, state.notebook),
            messages = listOf(ChatMessage(Role.USER, SummaryPrompt.REQUEST)),
            requestId = UUID.randomUUID().toString(),
        )
        val answer = speakAnswer(progress, request, config.narratorSpeaker, NarratorLines.SUMMARY_FILLER, config.maxSummarySentences)
        if (answer.sentences.isNotEmpty()) return progress.reply(ReplyKind.SUMMARY)
        if (answer.guarded) progress.update { it.copy(failure = SPOILER_GUARD) }
        return speakLine(progress, ReplyKind.SUMMARY_FALLBACK, null, NarratorLines.offlineSummary(scenario.case, state.unlockedClues))
    }

    /** Earlier exchanges with this suspect, newest [AgentConfig.historyExchanges], then the new question. */
    private fun conversation(notebook: List<Testimony>, suspectId: String, question: String): List<ChatMessage> {
        val past = notebook.filter { it.suspect == suspectId }.takeLast(config.historyExchanges)
        val history = past.flatMap { listOf(ChatMessage(Role.USER, it.question), ChatMessage(Role.ASSISTANT, it.answer)) }
        return history + ChatMessage(Role.USER, question)
    }

    private fun remember(game: Int, suspectId: String, question: String, sentences: List<String>) {
        if (sentences.isEmpty()) return
        val testimony = Testimony(suspectId, question, sentences.joinToString(" ").take(ProxyContract.MAX_MESSAGE_CHARS))
        mutableState.update { if (it.game == game) it.copy(notebook = it.notebook + testimony) else it }
    }

    private data class Answer(val sentences: List<String>, val guarded: Boolean)

    private data class Line(val text: String, val isFiller: Boolean)

    /** Streams [request] into speech; returns once everything accepted has been spoken. */
    private suspend fun speakAnswer(
        progress: TurnProgress,
        request: LlmRequest,
        speaker: String,
        filler: String,
        maxSentences: Int,
    ): Answer = coroutineScope {
        val lines = Channel<Line>(Channel.UNLIMITED)
        val talker = launch {
            for (line in lines) say(progress, line.text, speaker, isFiller = line.isFiller)
        }
        val fillerJob = launch {
            delay(config.fillerDelayMs)
            // trySend: on a multi-threaded dispatcher the answer may already have closed the queue.
            lines.trySend(Line(filler, isFiller = true))
        }
        val builder = AnswerBuilder(maxSentences, scenario.case.forbiddenPhrases) { sentence ->
            fillerJob.cancel()
            lines.trySend(Line(sentence, isFiller = false))
        }
        val events = llm.stream(request)
            .catch { emit(LlmEvent.Failure(CLIENT_ERROR, it.javaClass.simpleName)) }
            .produceIn(this)
        try {
            val failure = receiveAnswer(events, builder, progress)
            progress.update { it.copy(failure = failure) }
        } finally {
            events.cancel() // Drops the request when the answer ended early (guard, limit, timeout).
            fillerJob.cancel()
            lines.close()
        }
        talker.join()
        Answer(builder.sentences, builder.guarded)
    }

    /** Feeds streamed text to [builder] until the answer is complete; returns a failure code or null. */
    private suspend fun receiveAnswer(events: ReceiveChannel<LlmEvent>, builder: AnswerBuilder, progress: TurnProgress): String? {
        val startedAt = clock()
        val deadline = startedAt + config.answerTimeoutMs
        var waitingForText = true
        while (true) {
            val limit = if (waitingForText) minOf(startedAt + config.firstTextTimeoutMs, deadline) else deadline
            val next = withTimeoutOrNull((limit - clock()).coerceAtLeast(0)) { events.receiveCatching() }
                ?: return if (waitingForText) FIRST_TEXT_TIMEOUT else ANSWER_TIMEOUT
            when (val event = next.getOrNull()) {
                null -> return INCOMPLETE
                is LlmEvent.Failure -> return event.code
                is LlmEvent.Done -> {
                    builder.finish()
                    return if (builder.sentences.isEmpty() && !builder.guarded) EMPTY_ANSWER else null
                }
                is LlmEvent.Delta -> {
                    if (event.text.isEmpty()) continue
                    if (waitingForText) {
                        waitingForText = false
                        progress.update { it.copy(firstTextMs = clock() - startedAt) }
                    }
                    if (!builder.add(event.text)) return null
                }
            }
        }
    }

    private suspend fun speakLine(progress: TurnProgress, kind: ReplyKind, suspect: Suspect?, line: String): AgentReply {
        say(progress, line, suspect?.speaker ?: config.narratorSpeaker, isFiller = false)
        return progress.reply(kind)
    }

    /** Speaks one line; a broken TTS engine is reported in the reply instead of failing the turn. */
    private suspend fun say(progress: TurnProgress, line: String, speaker: String, isFiller: Boolean) {
        progress.update { if (isFiller) it.copy(fillerPlayed = true) else it.copy(sentences = it.sentences + line) }
        try {
            speech.speak(line, speaker)
        } catch (e: SpeechException) {
            progress.update { it.copy(speechError = e.message) }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val FIRST_TEXT_TIMEOUT = "first_text_timeout"
        const val ANSWER_TIMEOUT = "answer_timeout"
        const val INCOMPLETE = "incomplete"
        const val EMPTY_ANSWER = "empty_answer"
        const val CLIENT_ERROR = "client_error"
        const val SPOILER_GUARD = "spoiler_guard"
    }
}

/** What a turn has said so far; read when it ends, including when it was cut off. */
private class TurnProgress(val game: Int) {
    data class Snapshot(
        val suspectId: String? = null,
        val sentences: List<String> = emptyList(),
        val fillerPlayed: Boolean = false,
        val firstTextMs: Long? = null,
        val failure: String? = null,
        val speechError: String? = null,
    )

    private val current = AtomicReference(Snapshot())

    val snapshot: Snapshot get() = current.get()

    fun update(change: (Snapshot) -> Snapshot) {
        current.updateAndGet(change)
    }

    fun reply(kind: ReplyKind): AgentReply = snapshot.let {
        AgentReply(kind, it.suspectId, it.sentences, it.fillerPlayed, it.firstTextMs, it.failure, it.speechError)
    }
}

/** Turns streamed text into speakable sentences under the spoiler guard and the sentence limit. */
private class AnswerBuilder(
    private val maxSentences: Int,
    private val forbiddenPhrases: List<String>,
    private val onSentence: (String) -> Unit,
) {
    private val splitter = StreamingSentenceSplitter()
    private val accepted = mutableListOf<String>()

    val sentences: List<String> get() = accepted.toList()

    var guarded = false
        private set

    private val isOpen: Boolean get() = !guarded && accepted.size < maxSentences

    /** Adds streamed text; false once the answer is complete (limit reached or guard tripped). */
    fun add(text: String): Boolean = accept(splitter.push(text))

    fun finish() {
        accept(splitter.finish())
    }

    private fun accept(candidates: List<String>): Boolean {
        for (sentence in candidates) {
            if (!isOpen) return false
            // The whole answer so far is checked with punctuation ignored, so a phrase split across
            // two sentences is caught at its second half, which is then not spoken.
            if (phraseHits((accepted + sentence).joinToString(" "), forbiddenPhrases).isNotEmpty()) {
                guarded = true
                return false
            }
            accepted += sentence
            onSentence(sentence)
        }
        return isOpen
    }
}
