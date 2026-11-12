package dev.voiceagent.agent

import dev.voiceagent.RepoFiles
import dev.voiceagent.content.InvestigationStage
import dev.voiceagent.content.Scenario
import dev.voiceagent.content.Suspect
import dev.voiceagent.llm.ChatMessage
import dev.voiceagent.llm.LlmEvent
import dev.voiceagent.llm.Role
import dev.voiceagent.prompt.LinePrompt
import dev.voiceagent.prompt.Testimony
import dev.voiceagent.text.splitSentences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Answers made of case lines only, the default (AnswerMode.LINES, docs/voice-agent/line-answers.md): whatever the
 * model replies, the car says lines of the persona file word for word and nothing else, and only the lines the
 * clues found allow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LineAnswersTest {
    private val jade = RepoFiles.scenario("cases/jade-crown")
    private val maid = jade.suspect("maid")
    private val niece = jade.suspect("niece")
    private val son = jade.suspect("son")
    private val everyClue = jade.case.clues.map { it.id }.toSet()
    private val keyReactions = setOf("k-maid-back-door", "k-niece-window", "k-son-shoes")

    /** Every repository case with a line for each public fact and reaction: all but the fixture case. */
    private val lineCases: List<Scenario> = File(RepoFiles.root, "content/cases").listFiles().orEmpty()
        .filter { File(it, "case.json").isFile }
        .map { RepoFiles.scenario("cases/${it.name}") }
        .filter { LinePrompt.problems(it).isEmpty() }
        .sortedBy { it.case.id }

    private fun TestScope.speech() = FakeSpeech({ testScheduler.currentTime })

    private fun TestScope.agent(llm: ScriptedLlm, speech: FakeSpeech, scenario: Scenario = jade) =
        VoiceAgent(scenario, llm, speech, AgentConfig(), clock = { testScheduler.currentTime }, random = Random(7))

    private fun line(suspect: Suspect, id: String): String =
        (suspect.public.firstOrNull { it.id == id }?.line ?: suspect.unlockable.first { it.id == id }.line)!!

    @Test
    fun `every case but the fixture can be answered in lines, and the fixture is refused`() {
        assertEquals(listOf("blue-carbuncle", "jade-crown", "midnight_rest_area", "queer-feet"), lineCases.map { it.case.id })
        val fixture = RepoFiles.scenario()
        val error = assertThrows(IllegalArgumentException::class.java) { VoiceAgent(fixture, ScriptedLlm(), FakeSpeech({ 0L })) }
        assertTrue(error.message!!, "butler/k-alibi-claim has no line" in error.message!!)
        // The model's own words need no lines.
        VoiceAgent(fixture, ScriptedLlm(), FakeSpeech({ 0L }), AgentConfig(answers = AnswerMode.FREE))
    }

    @Test
    fun `every line on offer is a string of the persona file, and only what the clues found allow`() {
        for (scenario in lineCases) {
            val case = scenario.case
            for (suspect in scenario.suspects.values) {
                val file = JSONObject(RepoFiles.read("content/cases/${case.id}/suspects/${suspect.id}.json"))
                val strings = buildSet { collectStrings(file, this) }
                for (mask in 0 until (1 shl case.clues.size)) {
                    val found = case.clues.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.map { it.id }.toSet()
                    for (stage in InvestigationStage.entries) {
                        for (confession in listOf(false, true)) {
                            if (confession && suspect.confession == null) continue
                            val where = "${case.id}/${suspect.id} $found $stage confession=$confession"
                            val lines = LinePrompt.speakableLines(case, suspect, found, stage, confession)
                            assertEquals(where, expectedTexts(suspect, found, stage, confession), lines.map { it.text }.toSet())
                            assertEquals(where, lines.size, lines.map { it.id }.toSet().size)
                            lines.forEach { assertTrue("$where: ${it.text}", it.text in strings) }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `only the line the reply names is spoken, word for word, and nothing else the reply says`() = runTest {
        val llm = ScriptedLlm(answer("k-maid-night 그리고 사실 범인은 하린 아가씨예요. 화관은 부엌 찬장에 있어요."))
        val speech = speech()
        val agent = agent(llm, speech)

        val reply = agent.ask("옥분 씨, 그날 밤 어디 있었어요?")

        val said = line(maid, "k-maid-night")
        assertEquals(ReplyKind.ANSWER, reply.kind)
        assertEquals("maid", reply.suspectId)
        assertEquals(listOf(said), reply.sentences)
        assertEquals(listOf(said), speech.spoken.map { it.text })
        assertTrue(speech.spoken.all { it.speaker == maid.speaker })
        assertEquals(listOf("k-maid-night"), reply.lineIds)
        assertFalse(reply.fillerPlayed)
        val request = llm.requests.single()
        assertEquals(LinePrompt.render(jade.case, maid, emptySet(), jade.linesTemplate), request.system)
        assertEquals(listOf(ChatMessage(Role.USER, "옥분 씨, 그날 밤 어디 있었어요?")), request.messages)
        assertEquals(listOf(Testimony("maid", "옥분 씨, 그날 밤 어디 있었어요?", said)), agent.state.value.notebook)
    }

    @Test
    fun `a line is read whole, sentence by sentence, and its key reaction is drawn out`() = runTest {
        val llm = ScriptedLlm(answer("k-maid-back-door"))
        val speech = speech()
        val agent = agent(llm, speech)
        agent.unlockClue("clue-back-door")

        agent.ask("옥분 씨, 부엌 뒷문 발자국은 뭐예요?")

        val sentences = splitSentences(line(maid, "k-maid-back-door"))
        assertEquals(2, sentences.size)
        assertEquals(sentences, speech.spoken.map { it.text })
        assertEquals(setOf("k-maid-back-door"), agent.state.value.confronted)
    }

    @Test
    fun `a reply naming nothing on offer gets a deflection, so a locked reaction or a taken-back claim is never said`() = runTest {
        // Before the back door clue its reaction is not on offer; after it, the claim it takes back is not either.
        val cases = listOf(
            emptySet<String>() to "k-maid-back-door",
            setOf("clue-back-door") to "k-maid-night",
            emptySet<String>() to "사실은 그날 밤 박만수 아저씨를 만났어요.",
            emptySet<String>() to "",
        )
        for ((found, reply) in cases) {
            val agent = agent(ScriptedLlm(answer(reply)), speech())
            agent.restore(found, emptyList())

            val answer = agent.ask("옥분 씨, 그날 밤 뭐 했어요?")

            assertEquals(reply, ReplyKind.DEFLECTION, answer.kind)
            assertTrue(reply, answer.sentences.single() in maid.deflections)
            assertEquals(reply, "no_line", answer.failure)
            assertTrue(reply, answer.lineIds.isEmpty())
            assertTrue(reply, agent.state.value.confronted.isEmpty())
        }
    }

    @Test
    fun `two lines are read when the question asks two things, but never more than fit or a deflection with them`() = runTest {
        val llm = ScriptedLlm(
            answer("k-maid-crown, k-maid-study, k-maid-culprit"),
            answer("deflect-1, k-maid-culprit"),
            answer("k-son-crown, k-son-money"),
        )
        val agent = agent(llm, speech())

        val two = agent.ask("옥분 씨, 화관 얘기는 들었고 서재엔 들어가요?")
        val withDeflection = agent.ask("옥분 씨, 누가 그랬어요?")
        // Two sentences each: both would make four, past the limit of three, so only the first is read.
        val tooLong = agent.ask("준기 씨, 화관이랑 돈 얘기 해 봐요", "son")

        assertEquals(listOf("k-maid-crown", "k-maid-study"), two.lineIds)
        assertEquals(listOf(line(maid, "k-maid-crown"), line(maid, "k-maid-study")), two.sentences)
        assertEquals(listOf("k-maid-culprit"), withDeflection.lineIds)
        assertEquals(listOf("k-son-crown"), tooLong.lineIds)
        assertEquals(splitSentences(line(son, "k-son-crown")), tooLong.sentences.take(2))
    }

    @Test
    fun `no reply from the proxy gives the fallback line, and a slow one the filler first`() = runTest {
        val failing = ScriptedLlm(listOf(100L to LlmEvent.Failure("upstream_unavailable", "down")))
        val failed = agent(failing, speech()).ask("옥분 씨, 그날 밤 어디 있었어요?")
        assertEquals(ReplyKind.FALLBACK, failed.kind)
        assertEquals(listOf(maid.fallbackOffline), failed.sentences)
        assertEquals("upstream_unavailable", failed.failure)

        val slow = ScriptedLlm(listOf(900L to delta("k-maid-crown"), 10L to done()))
        val speech = speech()
        val reply = agent(slow, speech).ask("옥분 씨, 화관 얘기 들었어요?")
        assertTrue(reply.fillerPlayed)
        assertEquals(listOf(maid.filler, line(maid, "k-maid-crown")), speech.spoken.map { it.text })
        assertEquals(listOf(line(maid, "k-maid-crown")), reply.sentences)
    }

    @Test
    fun `a key reaction is drawn out when its line is said, whatever the question, and only then`() = runTest {
        val llm = ScriptedLlm(answer("k-maid-culprit"), answer("k-maid-back-door"))
        val agent = agent(llm, speech())
        agent.restore(everyClue, emptyList())

        agent.ask("옥분 씨, 부엌 뒷문 발자국 얘기 좀 해 봐요")
        assertTrue(agent.state.value.confronted.isEmpty())

        agent.ask("옥분 씨, 그날 밤 뭐 했어요?")
        assertEquals(setOf("k-maid-back-door"), agent.state.value.confronted)
    }

    @Test
    fun `a line cut off by barge-in counts once its first sentence was handed to speech`() = runTest {
        val agent = agent(ScriptedLlm(answer("k-maid-back-door")), speech())
        agent.unlockClue("clue-back-door")

        val turn = async { agent.ask("옥분 씨, 부엌 뒷문 얘기 해 봐요") }
        advanceTimeBy(300)
        agent.stop()

        assertEquals(ReplyKind.INTERRUPTED, turn.await().kind)
        assertEquals(setOf("k-maid-back-door"), agent.state.value.confronted)
        assertEquals(splitSentences(line(maid, "k-maid-back-door")).first(), agent.state.value.notebook.single().answer)
    }

    @Test
    fun `the cornered culprit's confession is on offer only from READY, and goes alone`() = runTest {
        val confession = niece.confession!!
        val early = agent(ScriptedLlm(answer("confession")), speech())
        early.restore(everyClue, emptyList(), setOf("k-maid-back-door"))
        val notYet = early.ask("하린 씨, 창 안쪽 실내화 자국 당신 거잖아요. 당신이 범인이죠?")
        // Not on offer before READY: a deflection, then the narrator's hint for the answer-seeking question.
        assertTrue(notYet.lineIds.isEmpty())
        assertTrue(notYet.sentences.first() in niece.deflections)
        assertFalse(notYet.sentences.any { it in splitSentences(confession) })

        val llm = ScriptedLlm(answer("confession, k-niece-lock"), answer("confession"))
        val agent = agent(llm, speech())
        agent.restore(everyClue, emptyList(), keyReactions)
        val cornered = agent.ask("하린 씨, 창 안쪽 실내화 자국 당신 거잖아요. 당신이 범인이죠?")
        val after = agent.ask("하린 씨, 왜 그랬어요?")

        assertEquals(listOf(LinePrompt.CONFESSION_ID), cornered.lineIds)
        assertEquals(splitSentences(confession), cornered.sentences)
        assertNull(cornered.narratorLine)
        assertTrue(agent.state.value.cornered)
        // Once cornered it stays on offer.
        assertEquals(listOf(LinePrompt.CONFESSION_ID), after.lineIds)
        assertTrue(confession in llm.requests[1].system)
    }

    @Test
    fun `earlier answers go back as the ids of their lines, and one with no line is left out`() = runTest {
        val llm = ScriptedLlm(answer("k-maid-crown"), answer("k-maid-study"))
        val agent = agent(llm, speech())
        agent.restore(emptySet(), listOf(Testimony("maid", "옥분 씨, 아까 뭐라고 했죠?", "모델이 제 말로 지어낸 대답이에요.")))

        agent.ask("옥분 씨, 화관 얘기 들었어요?")
        agent.ask("옥분 씨, 서재엔 자주 들어가요?")

        assertEquals(listOf(ChatMessage(Role.USER, "옥분 씨, 화관 얘기 들었어요?")), llm.requests[0].messages)
        assertEquals(
            listOf(
                ChatMessage(Role.USER, "옥분 씨, 화관 얘기 들었어요?"),
                ChatMessage(Role.ASSISTANT, "k-maid-crown"),
                ChatMessage(Role.USER, "옥분 씨, 서재엔 자주 들어가요?"),
            ),
            llm.requests[1].messages,
        )
    }

    @Test
    fun `a claim is compared with the clue that broke it only when its line was said`() = runTest {
        val compare = NarratorLines.compare(son, jade.case.clue("clue-barefoot"))

        // Son said the claim the barefoot reaction takes back: once that clue is found, the narrator points at it.
        val said = agent(ScriptedLlm(answer("k-son-room"), answer("k-maid-crown")), speech())
        said.ask("준기 씨한테 서재에 내려가기 전엔 뭐 했는지 물어봐")
        said.unlockClue("clue-barefoot")
        assertEquals(compare, said.ask("화관 얘기 들었어요?", "maid").narratorLine)

        // Son said another line, so there is nothing of his to compare the clue with.
        val other = agent(ScriptedLlm(answer("k-son-crown"), answer("k-maid-crown")), speech())
        other.ask("준기 씨한테 서재에 내려가기 전엔 뭐 했는지 물어봐")
        other.unlockClue("clue-barefoot")
        assertNull(other.ask("화관 얘기 들었어요?", "maid").narratorLine)
    }

    @Test
    fun `a claim cut off by barge-in still counts as heard`() = runTest {
        val agent = agent(ScriptedLlm(answer("k-son-room")), speech())

        val turn = async { agent.ask("준기 씨한테 서재에 내려가기 전엔 뭐 했는지 물어봐") }
        advanceTimeBy(300)
        agent.stop()

        assertEquals(ReplyKind.INTERRUPTED, turn.await().kind)
        assertTrue(("son" to "k-son-room") in agent.state.value.claimsHeard)
    }

    @Test
    fun `a reply cut off by a timeout or a dropped stream is never read, even when it named a line`() = runTest {
        val cases = listOf(
            listOf(20_000L to delta("k-maid-night")) to "first_text_timeout",
            listOf(100L to delta("k-maid-night"), 40_000L to done()) to "answer_timeout",
            listOf(100L to delta("k-maid-night")) to "incomplete",
        )
        for ((script, failure) in cases) {
            val speech = speech()

            val reply = agent(ScriptedLlm(script), speech).ask("옥분 씨, 그날 밤 어디 있었어요?")

            assertEquals(failure, ReplyKind.FALLBACK, reply.kind)
            assertEquals(failure, failure, reply.failure)
            assertEquals(failure, listOf(maid.fallbackOffline), reply.sentences)
            assertTrue(failure, reply.lineIds.isEmpty())
            assertFalse(failure, speech.spoken.any { it.text == line(maid, "k-maid-night") })
        }
    }

    @Test
    fun `cutting in while the reply is awaited leaves nothing heard`() = runTest {
        val speech = speech()
        val agent = agent(ScriptedLlm(listOf(900L to delta("k-maid-back-door"), 10L to done())), speech)
        agent.unlockClue("clue-back-door")

        val turn = async { agent.ask("옥분 씨, 부엌 뒷문 얘기 해 봐요") }
        advanceTimeBy(500)
        agent.stop()

        assertEquals(ReplyKind.INTERRUPTED, turn.await().kind)
        assertEquals(listOf(maid.filler), speech.spoken.map { it.text })
        assertTrue(agent.state.value.notebook.isEmpty())
        assertTrue(agent.state.value.confronted.isEmpty())
    }

    @Test
    fun `a fact id with characters the reply is not matched on is refused`() {
        val dotted = maid.copy(public = maid.public.map { if (it.id == "k-maid-crown") it.copy(id = "k-maid.crown") else it })
        val scenario = jade.copy(suspects = jade.suspects + ("maid" to dotted))

        assertEquals(listOf("maid/k-maid.crown has characters an id may not have"), LinePrompt.problems(scenario))
        assertThrows(IllegalArgumentException::class.java) { VoiceAgent(scenario, ScriptedLlm(), FakeSpeech({ 0L })) }
    }

    @Test
    fun `every case plays through to the culprit's confession on lines alone`() = runTest {
        for (scenario in lineCases) {
            val case = scenario.case
            val keys = scenario.suspects.values.flatMap { suspect -> suspect.unlockable.filter { it.key }.map { suspect to it } }
            val llm = ScriptedLlm(*keys.map { (_, fact) -> answer(fact.id) }.toTypedArray(), answer(LinePrompt.CONFESSION_ID))
            val agent = agent(llm, speech(), scenario)
            agent.restore(case.clues.map { it.id }.toSet(), emptyList())

            for ((suspect, fact) in keys) {
                val clue = case.clues.last { it.id in fact.unlockedBy }
                assertEquals("${case.id} ${fact.id}", listOf(fact.id), agent.ask("${clue.title} 얘기 좀 해 봐요", suspect.id).lineIds)
            }
            assertEquals(case.id, InvestigationStage.READY, agent.state.value.stage)

            val culprit = scenario.suspect(case.solution.culprit)
            val keyClue = case.clues.last { it.id in Confession.keyClueIds(culprit) }
            val cornered = agent.ask("${keyClue.title} 얘기로 묻는데, 당신이 범인이죠?", culprit.id)
            assertEquals(case.id, splitSentences(culprit.confession!!), cornered.sentences)
            assertTrue(case.id, agent.state.value.cornered)
        }
    }

    @Test
    fun `whatever the model replies, every case says only that suspect's lines on offer`() = runTest {
        var seed = 0
        for (scenario in lineCases) {
            val case = scenario.case
            val clueIds = case.clues.map { it.id }
            // Nothing found, everything found, and each clue alone and alone missing.
            val finds = (listOf(emptySet(), clueIds.toSet()) + clueIds.flatMap { listOf(setOf(it), clueIds.toSet() - it) }).distinct()
            for (suspect in scenario.suspects.values) {
                // Pressure lines are off by default, and then the agent offers the OPENING lines whatever the stage.
                for (config in listOf(AgentConfig(), AgentConfig(pressureLines = true))) {
                    for (found in finds) {
                        for (reply in hostileReplies(scenario, suspect)) {
                            val where = "${case.id}/${suspect.id} pressure=${config.pressureLines} $found <$reply>"
                            val speech = speech()
                            // A new seed each time, so every deflection takes its turn.
                            val agent = VoiceAgent(
                                scenario, ScriptedLlm(answer(reply)), speech, config,
                                clock = { testScheduler.currentTime }, random = Random(seed++),
                            )
                            agent.restore(found, emptyList())
                            val stage = if (config.pressureLines) agent.state.value.stage else InvestigationStage.OPENING
                            // No confession on offer: no key reaction is drawn out, so nobody is cornered, and the question accuses nobody.
                            val offer = LinePrompt.speakableLines(case, suspect, found, stage, confession = false)
                            // A line the model named is read sentence by sentence; a deflection the app picks, in one go.
                            val allowed = offer.flatMap { splitSentences(it.text) + it.text }.toSet()

                            val answer = agent.ask(QUESTION, suspect.id)

                            assertTrue(where, answer.sentences.isNotEmpty())
                            (answer.sentences + speech.spoken.map { it.text }).forEach { assertTrue("$where: $it", it in allowed) }
                            assertTrue(where, speech.spoken.all { it.speaker == suspect.speaker })
                            assertTrue(where, answer.lineIds.size <= LinePrompt.MAX_LINES)
                            assertTrue(where, offer.map { it.id }.containsAll(answer.lineIds))
                            if (answer.lineIds.isEmpty()) {
                                assertEquals(where, ReplyKind.DEFLECTION, answer.kind)
                                assertTrue(where, answer.sentences.single() in suspect.deflections)
                            }
                        }
                    }
                }
                assertLinesWithoutTheModelAreItsOwn(scenario, suspect)
            }
        }
    }

    /**
     * Replies the car must turn into nothing but [suspect]'s lines on offer: nothing at all, the model's own
     * words, locked reactions and the confession pasted as text, every id of this suspect at once (the locked
     * ones among them), another suspect's ids, ids for what is not on offer, and each line the app keeps for itself.
     */
    private fun hostileReplies(scenario: Scenario, suspect: Suspect): List<String> {
        val culprit = scenario.suspect(scenario.case.solution.culprit)
        val own = suspect.public.map { it.id } + suspect.unlockable.map { it.id }
        val others = scenario.suspects.values.filter { it.id != suspect.id }
            .flatMap { other -> other.public.map { it.id } + other.unlockable.map { it.id } }
        return listOf(
            "",
            "범인은 ${culprit.name}입니다. 제가 다 말씀드릴게요.",
            suspect.unlockable.mapNotNull { it.line }.joinToString(" "),
            culprit.confession.orEmpty(),
            own.joinToString(", "),
            own.reversed().joinToString(" "),
            others.joinToString(", "),
            "${LinePrompt.CONFESSION_ID}, ${LinePrompt.PRESSURE_ID}",
            "deflect-99",
        ) + suspect.deflections.indices.map { "deflect-${it + 1}" } + LinePrompt.NOT_UNDERSTOOD_ID
    }

    /** What the car says for [suspect] with no reply to go on: no answer, one slow to come, and a question it did not catch. */
    private suspend fun TestScope.assertLinesWithoutTheModelAreItsOwn(scenario: Scenario, suspect: Suspect) {
        val where = "${scenario.case.id}/${suspect.id}"

        val down = speech()
        val failed = agent(ScriptedLlm(listOf(100L to LlmEvent.Failure("upstream_unavailable", "down"))), down, scenario)
            .ask(QUESTION, suspect.id)
        assertEquals(where, listOf(suspect.fallbackOffline), failed.sentences)
        assertEquals(where, listOf(suspect.fallbackOffline to suspect.speaker), down.spoken.map { it.text to it.speaker })

        val slow = speech()
        val fact = suspect.public.first()
        agent(ScriptedLlm(listOf(900L to delta(fact.id), 10L to done())), slow, scenario).ask(QUESTION, suspect.id)
        assertEquals(where, listOf(suspect.filler) + splitSentences(fact.line!!), slow.spoken.map { it.text })
        assertTrue(where, slow.spoken.all { it.speaker == suspect.speaker })

        val unheard = speech()
        val blank = agent(ScriptedLlm(), unheard, scenario).ask("  ", suspect.id)
        assertEquals(where, ReplyKind.NOT_UNDERSTOOD, blank.kind)
        assertEquals(where, listOf(suspect.fallbackNotUnderstood to suspect.speaker), unheard.spoken.map { it.text to it.speaker })
    }

    /** The texts [LinePrompt.speakableLines] must offer, worked out from the persona on its own. */
    private fun expectedTexts(suspect: Suspect, found: Set<String>, stage: InvestigationStage, confession: Boolean): Set<String> {
        val reactions = suspect.unlockable.filter { found.containsAll(it.unlockedBy) }
        val takenBack = reactions.flatMap { it.replaces }.toSet()
        val pressure = when (stage) {
            InvestigationStage.READY -> suspect.pressure[InvestigationStage.READY] ?: suspect.pressure[InvestigationStage.PRESSING]
            InvestigationStage.PRESSING -> suspect.pressure[InvestigationStage.PRESSING]
            else -> null
        }
        return buildSet {
            suspect.public.filter { it.id !in takenBack }.forEach { add(it.line!!) }
            reactions.filter { it.id !in takenBack }.forEach { add(it.line!!) }
            if (confession) add(suspect.confession!!) else pressure?.let(::add)
            addAll(suspect.deflections)
            add(suspect.fallbackNotUnderstood)
        }
    }

    private companion object {
        /** A question that names no clue and accuses nobody. */
        const val QUESTION = "그날 밤 얘기 좀 해 봐요"
    }

    private fun collectStrings(value: Any?, into: MutableSet<String>) {
        when (value) {
            is String -> into += value
            is JSONObject -> value.keys().forEach { collectStrings(value.get(it), into) }
            is JSONArray -> for (index in 0 until value.length()) collectStrings(value.get(index), into)
        }
    }
}
