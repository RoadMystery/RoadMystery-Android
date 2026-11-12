package dev.voiceagent.story

import dev.voiceagent.agent.AgentConfig
import dev.voiceagent.agent.AgentState
import dev.voiceagent.agent.NarratorLines
import dev.voiceagent.agent.ReplyKind
import dev.voiceagent.agent.VoiceAgent
import dev.voiceagent.content.Scenario
import dev.voiceagent.identify.SuspectIdentifier
import dev.voiceagent.llm.LlmClient
import dev.voiceagent.prompt.Testimony
import dev.voiceagent.speech.SpeechOutput
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * F-05 심문 and F-06 수사 수첩 요약 for the team app's StoryEngine (RoadMystery-Android, the
 * `com.roadmystery.app.voice.VoiceAgent` interface; docs/voice-agent/team-integration.md).
 *
 * StoryEngine keeps the game: the player picks the suspect on its interrogation screen, it keeps the clues
 * found and the testimonies in its Progress, and it plays an answer as a reply scene in the suspect's voice
 * (F-03). So this answers in text and speaks nothing itself, and it follows the clues StoryEngine passes
 * with every question. Within one game the clues found only grow: a question with fewer clues than the last
 * one starts a new game and forgets the conversation, as StoryEngine.start does with its own state.
 * Questions and summaries run one at a time. By default (AnswerMode.LINES) an answer is lines of the
 * suspect's persona file, so it says nothing the case file does not (docs/voice-agent/line-answers.md).
 */
class StoryVoice(
    private val scenario: Scenario,
    llm: LlmClient,
    config: AgentConfig = AgentConfig(),
) {
    private val agent = VoiceAgent(scenario, llm, SilentSpeech, config)
    private val identifier = SuspectIdentifier.forCase(scenario.case)
    private val clueIds = scenario.case.clues.map { it.id }.toSet()
    private val turns = Mutex()

    /** StoryEngine's scenario id, which a case ported from the team app keeps as its own (case.schema.json). */
    val scenarioId: String get() = scenario.case.id

    /** The agent's investigation state, for logs and tests; never shown to the player. */
    val state: StateFlow<AgentState> get() = agent.state

    /**
     * Answers [utterance] as [suspectId], the suspect chosen on screen, with [foundClueIds] the clues
     * StoryEngine has found (ids this case lacks are skipped). The text is the suspect's alone: a narrator
     * line the agent ended the turn with is in [StoryReply.narratorLine], since StoryEngine reads the whole
     * reply in the suspect's voice and keeps it as testimony. An unknown suspect throws
     * [IllegalArgumentException]; cancelling the call (StoryEngine does when the car moves off) drops the request.
     */
    suspend fun ask(suspectId: String, utterance: String, foundClueIds: Set<String>): StoryReply = turns.withLock {
        require(suspectId in scenario.suspects) { "unknown suspect id: $suspectId" }
        follow(foundClueIds)
        val reply = agent.ask(utterance, suspectId)
        val text = reply.suspectText.ifBlank { scenario.suspect(suspectId).fallbackOffline }
        StoryReply(text, reply.narratorLine, reply.kind, reply.failure, reply.lineIds)
    }

    /**
     * F-06 from StoryEngine's Progress: its clues found and testimonies, which outlive an app restart where
     * the agent's own notebook does not. Ids this case lacks are skipped, and so are the suspects' lines for
     * a dropped connection, which StoryEngine keeps as testimony like any reply but which say nothing.
     */
    suspend fun summarize(foundClueIds: Collection<String>, testimonies: List<Testimony>): String = turns.withLock {
        val clues = foundClueIds.filterTo(mutableSetOf()) { it in clueIds }
        val notebook = testimonies.filter { it.suspect in scenario.suspects && !isFallback(it) }
        agent.summarize(clues, notebook).text
    }

    /**
     * What to do with what the player said in the pause after a scene while driving (F-03 asks "질문할 것이
     * 있나요?" and waits), hands-free: silence or a no ([WindowTurn.Continue]) lets the story go on, and a
     * question goes to the suspect it names, else to the one asked last, else to nobody yet ([WindowTurn.AskWho]).
     */
    fun readWindow(heard: String?): WindowTurn {
        val question = heard?.trim().orEmpty()
        if (QuestionWindow.isNoQuestion(question)) return WindowTurn.Continue
        val suspectId = identifier.identify(question, agent.state.value.currentSuspect)
        return if (suspectId == null) WindowTurn.AskWho(question) else WindowTurn.Ask(suspectId, question)
    }

    /** The narrator asking whom to question, for [WindowTurn.AskWho]. */
    fun askWhoLine(): String = NarratorLines.askWho(scenario.case)

    /** Unlocks the clues StoryEngine found since the last question; fewer than before means a new game. */
    private fun follow(found: Set<String>) {
        val known = found.filterTo(mutableSetOf()) { it in clueIds }
        if (!known.containsAll(agent.state.value.unlockedClues)) agent.reset()
        (known - agent.state.value.unlockedClues).forEach(agent::unlockClue)
    }

    private fun isFallback(testimony: Testimony): Boolean =
        testimony.answer.trim() == scenario.suspect(testimony.suspect).fallbackOffline

    /** StoryEngine's AudioPlayer reads the reply, so the agent's own speech goes nowhere. */
    private object SilentSpeech : SpeechOutput {
        override suspend fun speak(text: String, speaker: String) = Unit

        override fun stop() = Unit
    }
}

data class StoryReply(
    /** What the suspect said, never empty: StoryEngine plays it in the suspect's voice and keeps it as testimony. */
    val text: String,
    /**
     * The narrator's line the agent ended the turn with (a hint, the nudge toward the accusation, or a
     * comparison), left out of [text]: StoryEngine has nowhere to voice it as the narrator yet.
     */
    val narratorLine: String?,
    val kind: ReplyKind,
    /** Why no LLM answer came, when [text] is the suspect's line for that (VoiceAgent's failure codes). */
    val failure: String?,
    /**
     * The ids of the case lines [text] reads (AnswerMode.LINES, the default), in order: the team's
     * SuspectReply.answerId takes the first. Empty for a deflection or fallback the agent chose itself.
     */
    val lineIds: List<String> = emptyList(),
)
