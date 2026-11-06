package dev.voiceagent.agent

import dev.voiceagent.content.Clue
import dev.voiceagent.content.InvestigationStage
import dev.voiceagent.content.Scenario
import dev.voiceagent.content.Suspect
import dev.voiceagent.content.UnlockableFact
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
import dev.voiceagent.text.addressTerms
import dev.voiceagent.text.countedSentences
import dev.voiceagent.text.dropRepeatedOpening
import dev.voiceagent.text.looksLikeReadout
import dev.voiceagent.text.phraseHits
import dev.voiceagent.text.pythonStrip
import dev.voiceagent.text.stripStageDirections
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
 *
 * It also keeps the investigation's hidden progress ([AgentState.stage], see [Investigation]) from the
 * clues found and the key reactions drawn out; the narrator's hints and the nudge to accuse follow it.
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
    private val keyReactionIds = scenario.suspects.values.flatMap { it.unlockable }.filter { it.key }.map { it.id }.toSet()
    private val mutableState = MutableStateFlow(AgentState())
    private val activeTurn = AtomicReference<Job?>(null)

    val state: StateFlow<AgentState> = mutableState.asStateFlow()

    fun unlockClue(clueId: String) {
        require(clueId in clueIds) { "unknown clue id: $clueId" }
        mutableState.update { staged(it.copy(unlockedClues = it.unlockedClues + clueId)) }
    }

    /** Sets the suspect being questioned, e.g. from a touch on the suspect list; null clears it. */
    fun selectSuspect(suspectId: String?) {
        require(suspectId == null || suspectId in scenario.suspects) { "unknown suspect id: $suspectId" }
        mutableState.update { it.copy(currentSuspect = suspectId) }
    }

    /** Starts over: stops speaking and forgets the suspect, the clues, the notebook and the progress. */
    fun reset() {
        startGame(AgentState())
    }

    /**
     * Resumes a saved game (StoryEngine persists these): the clues found, the notebook and the key
     * reactions drawn out ([AgentState.confronted]). Like [reset] it stops the turn in progress; no
     * suspect is being questioned and no hint or nudge has been given yet. Each testimony's question
     * and answer are cut to the proxy's message limit, as [ask] records them. An unknown id throws
     * [IllegalArgumentException] before anything changes.
     */
    fun restore(unlockedClues: Set<String>, notebook: List<Testimony>, confronted: Set<String> = emptySet()) {
        unlockedClues.forEach { require(it in clueIds) { "unknown clue id: $it" } }
        confronted.forEach { require(it in keyReactionIds) { "unknown key reaction id: $it" } }
        notebook.forEach { require(it.suspect in scenario.suspects) { "unknown suspect id: ${it.suspect}" } }
        // The notebook goes out again as conversation history, which must stay within the contract.
        val testimonies = notebook.map {
            it.copy(question = it.question.take(ProxyContract.MAX_MESSAGE_CHARS), answer = it.answer.take(ProxyContract.MAX_MESSAGE_CHARS))
        }
        // Copies, so a caller's mutable collection cannot change the state afterwards.
        startGame(AgentState(unlockedClues = unlockedClues.toSet(), notebook = testimonies, confronted = confronted.toSet()))
    }

    /**
     * F-05: answers [utterance] in the voice of the suspect it is addressed to. The narrator may end
     * the turn with one line of its own ([ReplyKind.HINT]).
     */
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
        val topics = SuspectPrompt.raisedTopics(scenario.case, state.unlockedClues, question)
        // The stage only adds pressure lines, and how the LLM answers with them is not measured yet, so
        // off every turn gets the OPENING prompt. Either way the golden file pins it to Python's.
        val stage = if (config.pressureLines) state.stage else InvestigationStage.OPENING
        // Cornered at READY by the clue that broke its story, the culprit may confess, and once the player heard
        // it answer such a question it stays able to; the stage counts here whether or not pressure lines are on.
        val confession = (state.cornered && Confession.canConfess(scenario.case, suspect, config.variant)) ||
            Confession.opens(scenario.case, suspect, config.variant, state.stage, listOf(question))
        val request = LlmRequest(
            task = LlmTask.INTERROGATION,
            system = SuspectPrompt.render(
                scenario.case, suspect, state.unlockedClues, config.variant, scenario.suspectTemplate, topics, stage, confession,
            ),
            messages = conversation(state.notebook, suspectId, question),
            requestId = UUID.randomUUID().toString(),
        )
        val blocked = unsentClueWords(request, state.unlockedClues)
        // The interjections the suspect's previous heard answer opened with are not said again.
        val previous = state.notebook.lastOrNull { it.suspect == suspectId }?.answer.orEmpty()
        val keep = addressTerms(suspect.speechRules)
        val answer = try {
            speakAnswer(
                progress, request, suspect.speaker, suspect.filler, config.maxAnswerSentences, blocked,
                // A confession is meant to give the culprit away; only what the prompt did not send stops it.
                forbidden = if (confession) emptyList() else scenario.case.forbiddenPhrases,
                countSentences = ::countedSentences, clean = ::stripStageDirections,
                cleanFirst = { dropRepeatedOpening(stripStageDirections(it), previous, keep) },
                isReadout = { looksLikeReadout(it, suspect.name) },
            )
        } catch (e: CancellationException) {
            // The player cut in: keep what they heard so a follow-up question has its context, and
            // count a reaction they heard as drawn out.
            val heard = progress.snapshot.sentences
            remember(progress.game, suspectId, question, heard)
            confront(progress.game, suspect, state.unlockedClues, question, heard)
            if (confession && heard.isNotEmpty()) corner(progress.game)
            throw e
        }
        return endTurn(progress, suspect, state.unlockedClues, question, answer, confession)
    }

    /**
     * Records what the player heard of [answer], then ends the turn with at most one narrator line: the
     * hint for a leak the guard cut, else on a heard answer the line comparing a claim the player heard with
     * the clue that broke it ([NarratorLines.compare]), else the nudge on the first turn that ends at READY,
     * else, after an answer-seeking question, a hint unless it is the one last given. An answer cut before
     * its first sentence is replaced by one of the suspect's deflections, with or without a line after it.
     * [unlocked]: the clues the prompt was made with. [confession]: the prompt had the culprit's confession,
     * so the narrator stays quiet over a heard answer.
     */
    private suspend fun endTurn(
        progress: TurnProgress,
        suspect: Suspect,
        unlocked: Set<String>,
        question: String,
        answer: Answer,
        confession: Boolean,
    ): AgentReply {
        val heard = answer.sentences.isNotEmpty()
        if (heard) remember(progress.game, suspect.id, question, answer.sentences)
        if (heard && confession) corner(progress.game)
        val drawn = confront(progress.game, suspect, unlocked, question, answer.sentences)
        if (heard) noteClaims(progress.game, suspect, unlocked, question)
        // A reaction the question went after but did not draw out stays pending; "ask about it" would
        // only repeat the question just asked, so the hint moves on. That holds when the answer was
        // heard but did not count (only an opener, or asking to repeat) as much as when it was cut.
        val tried = Investigation.drawnOut(scenario.case, suspect, unlocked, question) - drawn
        val kind = when {
            heard -> ReplyKind.ANSWER
            answer.guarded -> {
                // Even before a leak hint: the narrator alone would tell the player the answer was coming.
                val line = suspect.deflections.randomOrNull(random) ?: suspect.fallbackNotUnderstood
                remember(progress.game, suspect.id, question, listOf(line))
                say(progress, line, suspect.speaker, isFiller = false)
                ReplyKind.DEFLECTION
            }
            else -> return speakLine(progress, ReplyKind.FALLBACK, suspect, suspect.fallbackOffline)
        }
        val now = mutableState.value
        val leakHint = if (answer.leaked.isEmpty()) null else hint(tried)
        val catch = if (heard) pendingCatch(now) else null
        val compareLine = catch?.let { (who, _, clue) -> NarratorLines.compare(who, clue) }
        val line = when {
            leakHint != null -> leakHint
            // The culprit's answer to being cornered is the scene; a hint or the nudge would talk over it.
            confession && heard -> null
            // Like the nudge, on the first turn free for it once the clue that breaks the claim is found.
            compareLine != null -> compareLine
            // READY may have come with a clue the story unlocked, or a leak hint may have taken the turn
            // that got there: the nudge waits for the first turn free for it.
            now.stage == InvestigationStage.READY && !now.nudged -> NarratorLines.ACCUSE_NUDGE
            Investigation.isAnswerSeeking(question) -> hint(tried)?.takeIf { it != now.lastHint }
            else -> null
        }
        if (line == null) {
            // "Not twice in a row" counts answered turns: one without a narrator line ends the run.
            mutableState.update { if (it.game == progress.game) it.copy(lastHint = null) else it }
            return progress.reply(kind)
        }
        val caught = if (catch != null && line == compareLine) catch.first.id to catch.second.id else null
        narrate(progress, line, caught)
        return progress.reply(ReplyKind.HINT)
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

    /**
     * Keywords of clues not found yet that appear nowhere in [request]: neither the player nor the
     * prompt brought them up, so a suspect saying one is volunteering the clue.
     */
    private fun unsentClueWords(request: LlmRequest, unlocked: Set<String>): List<String> {
        val sent = (listOf(request.system) + request.messages.map { it.content }).joinToString("\n")
        val locked = SuspectPrompt.lockedKeywords(scenario.case, unlocked)
        val mentioned = phraseHits(sent, locked).toSet()
        return locked.filterNot { it in mentioned }
    }

    /** Keeps the culprit's confession in its prompt for the rest of [game] ([AgentState.cornered]). */
    private fun corner(game: Int) {
        mutableState.update { if (it.game == game) it.copy(cornered = true) else it }
    }

    private fun remember(game: Int, suspectId: String, question: String, sentences: List<String>) {
        if (sentences.isEmpty()) return
        val testimony = Testimony(suspectId, question, sentences.joinToString(" ").take(ProxyContract.MAX_MESSAGE_CHARS))
        mutableState.update { if (it.game == game) it.copy(notebook = it.notebook + testimony) else it }
    }

    /**
     * Adds the key reactions [question] drew out of [suspect] if what the player [heard] answers it
     * ([Investigation.isAnswer]), and returns them. [unlocked] is what the prompt was made with: it had
     * a reaction only if all its clues were in it.
     */
    private fun confront(game: Int, suspect: Suspect, unlocked: Set<String>, question: String, heard: List<String>): Set<String> {
        if (!Investigation.isAnswer(suspect, heard)) return emptySet()
        val drawn = Investigation.drawnOut(scenario.case, suspect, unlocked, question)
        if (drawn.isNotEmpty()) {
            mutableState.update { if (it.game == game) staged(it.copy(confronted = it.confronted + drawn)) else it }
        }
        return drawn
    }

    /** The narrator's hint for the game as it stands now, including what this turn drew out. */
    private fun hint(tried: Set<String>): String? = mutableState.value.let {
        NarratorLines.hint(scenario.case, scenario.suspects.values, it.unlockedClues, it.confronted, tried)
    }

    /**
     * Speaks a hint, the nudge or a comparison in the narrator's voice, never testimony. Only once it has
     * been spoken is it the last hint, the nudge given and [caught] (the suspect and reaction a comparison
     * pointed at) done: a barge-in that cuts it off leaves it for the next turn.
     */
    private suspend fun narrate(progress: TurnProgress, line: String, caught: Pair<String, String>? = null) {
        // Marked before say() adds it to the sentences, so a reply cut off here still keeps it apart.
        progress.update { it.copy(narratorLine = line) }
        say(progress, line, config.narratorSpeaker, isFiller = false)
        mutableState.update {
            if (it.game != progress.game) return@update it
            it.copy(
                lastHint = line,
                nudged = it.nudged || line == NarratorLines.ACCUSE_NUDGE,
                caught = if (caught == null) it.caught else it.caught + caught,
            )
        }
    }

    /**
     * Records the claims [suspect]'s prompt carried with [unlocked] when the player heard it answer, and
     * counts as caught the reactions taking a claim back that [question] already confronted it with: pointing
     * at them afterwards would repeat what the player just did.
     */
    private fun noteClaims(game: Int, suspect: Suspect, unlocked: Set<String>, question: String) {
        val held = Investigation.standingClaims(suspect, unlocked).map { suspect.id to it }
        val raised = Investigation.mentionedClues(scenario.case, question).map { it.id }.toSet()
        val confronting = Investigation.claimBreakers(suspect, unlocked)
            .filter { fact -> fact.unlockedBy.any(raised::contains) }
            .map { suspect.id to it.id }
        if (held.isEmpty() && confronting.isEmpty()) return
        mutableState.update {
            if (it.game == game) it.copy(claimsHeard = it.claimsHeard + held, caught = it.caught + confronting) else it
        }
    }

    /**
     * The first non-key reaction, in cast and sheet order, that takes back a claim the player heard and is
     * within reach now, with its suspect and the clue that broke the claim (the last of its clues in case
     * order); null once each has been pointed at or confronted. Key reactions are the way to the culprit and
     * are left to the hints, which name no one ([NarratorLines.confrontHint]).
     */
    private fun pendingCatch(state: AgentState): Triple<Suspect, UnlockableFact, Clue>? {
        val order = scenario.case.clues.map { it.id }
        for (suspect in scenario.suspects.values) {
            for (fact in Investigation.claimBreakers(suspect, state.unlockedClues)) {
                if (fact.key || (suspect.id to fact.id) in state.caught) continue
                if (fact.replaces.none { (suspect.id to it) in state.claimsHeard }) continue
                val clueId = fact.unlockedBy.maxBy(order::indexOf)
                return Triple(suspect, fact, scenario.case.clues.first { it.id == clueId })
            }
        }
        return null
    }

    /** [state] with its stage worked out again from its clues and confrontations. */
    private fun staged(state: AgentState): AgentState =
        state.copy(stage = Investigation.stage(scenario.case, scenario.suspects.values, state.unlockedClues, state.confronted))

    /** Replaces the game with [start] in a new [AgentState.game], so a turn cut off here cannot write into it. */
    private fun startGame(start: AgentState) {
        stop()
        mutableState.update { staged(start.copy(game = it.game + 1)) }
    }

    /** [leaked]: the blocked phrases that stopped the answer, if a leak is what stopped it. */
    private data class Answer(val sentences: List<String>, val guarded: Boolean, val leaked: List<String>)

    private data class Line(val text: String, val isFiller: Boolean)

    /** Streams [request] into speech; returns once everything accepted has been spoken. */
    private suspend fun speakAnswer(
        progress: TurnProgress,
        request: LlmRequest,
        speaker: String,
        filler: String,
        maxSentences: Int,
        blockedWords: List<String> = emptyList(),
        // A cornered culprit's confession may say them (Confession).
        forbidden: List<String> = scenario.case.forbiddenPhrases,
        // Suspects open with "하, 참." or "맨발이요?"; the narrator's summary counts every sentence.
        countSentences: (List<String>) -> Int = List<String>::size,
        // A suspect's "[짧게]" or "(sigh)" is dropped and read-out rules stop the answer; the narrator
        // may quote the overview's "-다".
        clean: (String) -> String = { it },
        // A suspect's first sentence also loses an opening its previous answer had (dropRepeatedOpening).
        cleanFirst: (String) -> String = clean,
        isReadout: (String) -> Boolean = { false },
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
        val builder = AnswerBuilder(maxSentences, forbidden + blockedWords, countSentences, clean, cleanFirst, isReadout) { sentence ->
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
        Answer(builder.sentences, builder.guarded, builder.leaked)
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

    /** Ends the turn with one fixed line, in [suspect]'s voice or, when null, the narrator's; the reply is theirs. */
    private suspend fun speakLine(progress: TurnProgress, kind: ReplyKind, suspect: Suspect?, line: String): AgentReply {
        progress.update { it.copy(suspectId = suspect?.id) }
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
        /** Set as the narrator's line is handed to speech, so it is always the last of [sentences]. */
        val narratorLine: String? = null,
    )

    private val current = AtomicReference(Snapshot())

    val snapshot: Snapshot get() = current.get()

    fun update(change: (Snapshot) -> Snapshot) {
        current.updateAndGet(change)
    }

    fun reply(kind: ReplyKind): AgentReply = snapshot.let {
        AgentReply(kind, it.suspectId, it.sentences, it.fillerPlayed, it.firstTextMs, it.failure, it.speechError, it.narratorLine)
    }
}

/** Turns streamed text into speakable sentences under the spoiler guard and the sentence limit. */
private class AnswerBuilder(
    private val maxSentences: Int,
    private val blockedPhrases: List<String>,
    /** How many of the accepted sentences count toward [maxSentences]. */
    private val countSentences: (List<String>) -> Int,
    /** The sentence as spoken; an empty result is skipped. */
    private val clean: (String) -> String,
    /** [clean] for a sentence while none has been accepted yet. */
    private val cleanFirst: (String) -> String,
    /** Sentences never to speak: the answer stops there, as it does at a blocked phrase. */
    private val isReadout: (String) -> Boolean,
    private val onSentence: (String) -> Unit,
) {
    private val splitter = StreamingSentenceSplitter()
    private val accepted = mutableListOf<String>()

    val sentences: List<String> get() = accepted.toList()

    var guarded = false
        private set

    /** The blocked phrases the answer was about to say, when they are what stopped it. */
    var leaked: List<String> = emptyList()
        private set

    private val isOpen: Boolean get() = !guarded && countSentences(accepted) < maxSentences

    /** Adds streamed text; false once the answer is complete (limit reached or guard tripped). */
    fun add(text: String): Boolean = accept(splitter.push(text))

    fun finish() {
        accept(splitter.finish())
    }

    private fun accept(candidates: List<String>): Boolean {
        for (candidate in candidates) {
            if (!isOpen) return false
            val sentence = if (accepted.isEmpty()) cleanFirst(candidate) else clean(candidate)
            if (sentence.isEmpty()) continue
            if (isReadout(sentence)) {
                guarded = true
                return false
            }
            // The whole answer so far is checked with punctuation ignored, so a phrase split across
            // two sentences is caught at its second half, which is then not spoken.
            val hits = phraseHits((accepted + sentence).joinToString(" "), blockedPhrases)
            if (hits.isNotEmpty()) {
                guarded = true
                leaked = hits
                return false
            }
            accepted += sentence
            onSentence(sentence)
        }
        return isOpen
    }
}
