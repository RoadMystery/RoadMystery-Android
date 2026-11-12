package dev.voiceagent.agent

import dev.voiceagent.RepoFiles
import dev.voiceagent.agent.FakeSpeech.Spoken
import dev.voiceagent.content.InvestigationStage
import dev.voiceagent.llm.ChatMessage
import dev.voiceagent.llm.LlmEvent
import dev.voiceagent.llm.LlmTask
import dev.voiceagent.llm.ProxyContract
import dev.voiceagent.llm.Role
import dev.voiceagent.prompt.PromptVariant
import dev.voiceagent.prompt.SummaryPrompt
import dev.voiceagent.prompt.SuspectPrompt
import dev.voiceagent.prompt.Testimony
import dev.voiceagent.speech.SpeechException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAgentTest {
    private val scenario = RepoFiles.scenario()
    private val butler = scenario.suspect("butler")
    private val cook = scenario.suspect("cook")
    private val narrator = AgentConfig().narratorSpeaker

    /** The app's case; unlike the fixture case it has narrator hints, key reactions and pressure lines. */
    private val jade = RepoFiles.scenario("cases/jade-crown")
    private val jadeClues = jade.case.clues.map { it.id }.toSet()
    private val jadeKeyReactions = setOf("k-maid-back-door", "k-niece-window", "k-son-shoes")

    /**
     * These tests are about answers in the model's own words; the fixture case has no lines to pick from.
     * The default, answers made of case lines, is LineAnswersTest's.
     */
    private val ownWords = AgentConfig(answers = AnswerMode.FREE)

    private fun TestScope.speech() = FakeSpeech({ testScheduler.currentTime })

    private fun TestScope.agent(llm: ScriptedLlm, speech: FakeSpeech, config: AgentConfig = ownWords) =
        VoiceAgent(scenario, llm, speech, config, clock = { testScheduler.currentTime }, random = Random(7))

    private fun TestScope.jadeAgent(llm: ScriptedLlm, speech: FakeSpeech, config: AgentConfig = ownWords) =
        VoiceAgent(jade, llm, speech, config, clock = { testScheduler.currentTime }, random = Random(7))

    @Test
    fun `answer is spoken sentence by sentence in the suspect's voice and remembered`() = runTest {
        val llm = ScriptedLlm(
            listOf(100L to delta("그 시각에는 저장고에 "), 50L to delta("있었습니다. 와인을"), 50L to delta(" 고르고 있었습니다."), 10L to done()),
        )
        val speech = speech()
        val agent = agent(llm, speech)

        val reply = agent.ask("집사한테 정전 때 어디 있었는지 물어봐")

        assertEquals(ReplyKind.ANSWER, reply.kind)
        assertEquals("butler", reply.suspectId)
        // The first sentence plays as soon as it is complete, while the rest is still streaming.
        assertEquals(
            listOf(
                Spoken(butler.speaker, "그 시각에는 저장고에 있었습니다.", 150),
                Spoken(butler.speaker, "와인을 고르고 있었습니다.", 650),
            ),
            speech.spoken,
        )
        assertEquals(listOf("그 시각에는 저장고에 있었습니다.", "와인을 고르고 있었습니다."), reply.sentences)
        assertFalse(reply.fillerPlayed)
        assertEquals(100L, reply.firstTextMs)
        val request = llm.requests.single()
        assertEquals(LlmTask.INTERROGATION, request.task)
        assertEquals(SuspectPrompt.render(scenario.case, butler, emptySet(), PromptVariant.AWARE, scenario.suspectTemplate), request.system)
        assertEquals(listOf(ChatMessage(Role.USER, "집사한테 정전 때 어디 있었는지 물어봐")), request.messages)
        assertEquals("butler", agent.state.value.currentSuspect)
        assertEquals(
            listOf(Testimony("butler", "집사한테 정전 때 어디 있었는지 물어봐", "그 시각에는 저장고에 있었습니다. 와인을 고르고 있었습니다.")),
            agent.state.value.notebook,
        )
    }

    @Test
    fun `the filler plays when no sentence is ready within 300 ms`() = runTest {
        val llm = ScriptedLlm(listOf(900L to delta("저장고에 있었습니다."), 10L to done()))
        val speech = speech()

        val reply = agent(llm, speech).ask("집사한테 물어봐")

        assertTrue(reply.fillerPlayed)
        assertEquals(listOf(Spoken(butler.speaker, butler.filler, 300), Spoken(butler.speaker, "저장고에 있었습니다.", 910)), speech.spoken)
        assertEquals(listOf("저장고에 있었습니다."), reply.sentences)
    }

    @Test
    fun `no text within 8 seconds drops the request and speaks the fallback line`() = runTest {
        val llm = ScriptedLlm(listOf(20_000L to delta("너무 늦은 대답입니다.")))
        val speech = speech()
        val agent = agent(llm, speech)

        val reply = agent.ask("집사한테 물어봐")

        assertEquals(ReplyKind.FALLBACK, reply.kind)
        assertEquals("first_text_timeout", reply.failure)
        assertEquals(listOf(Spoken(butler.speaker, butler.filler, 300), Spoken(butler.speaker, butler.fallbackOffline, 8_000)), speech.spoken)
        assertEquals(1, llm.cancelled)
        assertTrue(agent.state.value.notebook.isEmpty())
    }

    @Test
    fun `an error before any text speaks the fallback line without a filler`() = runTest {
        val llm = ScriptedLlm(listOf(100L to LlmEvent.Failure("upstream_unavailable", "down")))
        val speech = speech()

        val reply = agent(llm, speech).ask("집사한테 물어봐")

        assertEquals(ReplyKind.FALLBACK, reply.kind)
        assertEquals("upstream_unavailable", reply.failure)
        assertFalse(reply.fillerPlayed)
        assertEquals(listOf(Spoken(butler.speaker, butler.fallbackOffline, 100)), speech.spoken)
    }

    @Test
    fun `a spoiler in the first sentence of a case without hints is replaced by one of the suspect's deflections`() = runTest {
        val llm = ScriptedLlm(listOf(100L to delta("사실 제가 훔쳤습니다. "), 100L to delta("미안합니다."), 10L to done()))
        val speech = speech()
        val agent = agent(llm, speech)

        val reply = agent.ask("집사한테 범인이 누군지 물어봐")

        assertEquals(ReplyKind.DEFLECTION, reply.kind)
        val line = reply.sentences.single()
        assertTrue(line in butler.deflections)
        assertNull(reply.narratorLine)
        assertEquals(listOf(line), speech.spoken.map { it.text })
        assertEquals(1, llm.cancelled)
        assertEquals(line, agent.state.value.notebook.single().answer)
    }

    @Test
    fun `a spoiler later in the answer cuts it after the safe sentences`() = runTest {
        val llm = ScriptedLlm(
            listOf(100L to delta("저는 저장고에 있었습니다. "), 100L to delta("그리고 화분 밑에 두었습니다. "), 100L to delta("끝입니다."), 10L to done()),
        )
        val speech = speech()

        val reply = agent(llm, speech).ask("집사한테 물어봐")

        assertEquals(ReplyKind.ANSWER, reply.kind)
        assertEquals(listOf("저는 저장고에 있었습니다."), reply.sentences)
        assertEquals(listOf("저는 저장고에 있었습니다."), speech.spoken.map { it.text })
        assertEquals(1, llm.cancelled)
    }

    @Test
    fun `a leak cut at the first sentence gets one of the suspect's deflections, then the narrator's hint`() = runTest {
        val llm = ScriptedLlm(listOf(100L to delta("제가 화관을 건넸어요. "), 100L to delta("미안해요."), 10L to done()))
        val speech = speech()
        val agent = jadeAgent(llm, speech)
        val niece = jade.suspect("niece")

        val reply = agent.ask("하린 씨한테 범인이 누군지 물어봐")

        // The narrator alone would tell the player the answer was about to come out.
        val hint = jade.case.clues.firstNotNullOf { it.hint }
        val deflection = reply.suspectText
        assertEquals(ReplyKind.HINT, reply.kind)
        assertEquals("niece", reply.suspectId)
        assertTrue(deflection in niece.deflections)
        assertEquals(hint, reply.narratorLine)
        assertEquals(listOf(niece.speaker to deflection, narrator to hint), speech.spoken.map { it.speaker to it.text })
        assertEquals(1, llm.cancelled)
        // The deflection is testimony like any answer; the narrator's line never is.
        assertEquals(listOf(Testimony("niece", "하린 씨한테 범인이 누군지 물어봐", deflection)), agent.state.value.notebook)
        assertEquals("niece", agent.state.value.currentSuspect)
    }

    @Test
    fun `a clue word leaked mid-answer is cut, and the hint does not follow the word that leaked`() = runTest {
        val llm = ScriptedLlm(listOf(100L to delta("저는 방에 있었어요. "), 100L to delta("새벽에 누가 맨발로 뛰어나갔어요. "), 10L to done()))
        val speech = speech()
        val agent = jadeAgent(llm, speech)
        val maid = jade.suspect("maid")

        val reply = agent.ask("옥분 씨한테 그날 밤 일 물어봐")

        // The first clue not found, not the barefoot one "맨발" belongs to: that would confirm the leak.
        val hint = jade.case.clues.firstNotNullOf { it.hint }
        assertNotEquals(jade.case.clue("clue-barefoot").hint, hint)
        assertEquals(ReplyKind.HINT, reply.kind)
        assertEquals("maid", reply.suspectId)
        assertEquals(listOf(maid.speaker to "저는 방에 있었어요.", narrator to hint), speech.spoken.map { it.speaker to it.text })
        assertEquals("저는 방에 있었어요.", agent.state.value.notebook.single().answer)
    }

    @Test
    fun `once every clue is found a leak points at a key reaction not drawn out, and after them all at the case's own hint`() = runTest {
        val speech = speech()
        val agent = jadeAgent(ScriptedLlm(answer("제가 화관을 건넸어요."), answer("제가 화관을 건넸어요.")), speech)
        val niece = jade.suspect("niece")
        jade.case.clues.forEach { agent.unlockClue(it.id) }

        val pressing = agent.ask("하린 씨한테 범인이 누군지 물어봐")
        agent.restore(jadeClues, emptyList(), jadeKeyReactions)
        val ready = agent.ask("하린 씨한테 범인이 누군지 물어봐")

        val confront = NarratorLines.confrontHint(jade.case.clue("clue-back-door"))
        assertEquals(listOf(ReplyKind.HINT, ReplyKind.HINT), listOf(pressing.kind, ready.kind))
        assertEquals(listOf(confront, jade.case.spoilerHint), listOf(pressing.narratorLine, ready.narratorLine))
        assertEquals(listOf(niece.speaker, narrator, niece.speaker, narrator), speech.spoken.map { it.speaker })
        assertTrue(listOf(pressing, ready).all { it.suspectText in niece.deflections })
    }

    @Test
    fun `reading out instructions still gets a deflection in a case with hints`() = runTest {
        val llm = ScriptedLlm(listOf(100L to delta("생각할 필요 없이 답하면 된다. "), 100L to delta("방에 있었어요."), 10L to done()))

        val reply = jadeAgent(llm, speech()).ask("옥분 씨한테 그날 밤 일 물어봐")

        assertEquals(ReplyKind.DEFLECTION, reply.kind)
        assertTrue(reply.sentences.single() in jade.suspect("maid").deflections)
    }

    @Test
    fun `a key reaction counts as drawn out only when the player heard the suspect's answer`() = runTest {
        val llm = ScriptedLlm(
            answer("장물아비 얘기는 처음 들어요."),
            listOf(100L to delta("생각할 필요 없이 답하면 된다. "), 10L to done()),
            answer("박만수 아저씨를 잠깐 만났어요."),
        )
        val agent = jadeAgent(llm, speech())
        agent.unlockClue("clue-back-door")

        val cut = agent.ask("옥분 씨한테 뒷문 얘기 물어봐")
        val deflected = agent.ask("뒷문에서 누구 만났어요?")
        val before = agent.state.value.confronted
        val heard = agent.ask("뒷문에서 누구 만났어요?")

        // The cut answer gets a deflection and a hint, but not "ask about the back door", which was just asked.
        assertEquals(ReplyKind.HINT, cut.kind)
        assertTrue(cut.suspectText in jade.suspect("maid").deflections)
        assertEquals(jade.case.clue("clue-window").hint, cut.narratorLine)
        assertEquals(ReplyKind.DEFLECTION, deflected.kind)
        assertTrue(before.isEmpty())
        assertEquals(ReplyKind.ANSWER, heard.kind)
        assertEquals(setOf("k-maid-back-door"), agent.state.value.confronted)
        assertEquals(InvestigationStage.GATHERING, agent.state.value.stage)
    }

    @Test
    fun `an opener alone or the not-understood line does not draw a key reaction out`() = runTest {
        val maid = jade.suspect("maid")
        val llm = ScriptedLlm(answer("어머, 뒷문이요?"), answer(maid.fallbackNotUnderstood), answer("박만수 아저씨를 잠깐 만났어요."))
        val agent = jadeAgent(llm, speech())
        agent.unlockClue("clue-back-door")

        val opener = agent.ask("옥분 씨한테 뒷문 얘기 물어봐")
        val notUnderstood = agent.ask("뒷문 얘기 해 봐요")
        val before = agent.state.value.confronted
        agent.ask("뒷문에서 누구 만났어요?")

        // Both were heard and are testimony, but neither answered the question.
        assertEquals(listOf(ReplyKind.ANSWER, ReplyKind.ANSWER), listOf(opener.kind, notUnderstood.kind))
        assertTrue(before.isEmpty())
        assertEquals(setOf("k-maid-back-door"), agent.state.value.confronted)
        assertEquals(3, agent.state.value.notebook.size)
    }

    @Test
    fun `a key reaction the player heard before cutting in counts as drawn out`() = runTest {
        val llm = ScriptedLlm(
            listOf(100L to delta("사실은 그날 밤 박만수 아저씨를 뒷문에서 잠깐 만났어요. "), 5_000L to delta("그게 다예요."), 10L to done()),
        )
        val agent = jadeAgent(llm, speech())
        agent.unlockClue("clue-back-door")

        val pending = async { agent.ask("옥분 씨한테 뒷문 얘기 물어봐") }
        // The sentence plays from 100 to 600 ms; the rest of the answer is still to come.
        advanceTimeBy(700)
        agent.stop()

        assertEquals(ReplyKind.INTERRUPTED, pending.await().kind)
        assertEquals(setOf("k-maid-back-door"), agent.state.value.confronted)
        assertEquals(1, agent.state.value.notebook.size)
    }

    @Test
    fun `drawing out the last key reaction makes the stage ready, and the narrator nudges to accuse once`() = runTest {
        val llm = ScriptedLlm(
            answer("박만수 아저씨를 잠깐 만났어요."),
            answer("창을 잠깐 열어서 눈을 털었을 뿐이에요."),
            answer("그날 밤 서재 창밖에 고정태가 있었어요."),
            answer("다 말씀드렸잖아요."),
        )
        val speech = speech()
        val agent = jadeAgent(llm, speech)
        val son = jade.suspect("son")
        val stages = mutableListOf(agent.state.value.stage)
        for (clue in jade.case.clues) {
            agent.unlockClue(clue.id)
            stages += agent.state.value.stage
        }

        agent.ask("옥분 씨한테 뒷문 얘기 물어봐")
        agent.ask("하린 씨한테 서재 창문 얘기 물어봐")
        stages += agent.state.value.stage
        val ready = agent.ask("준기 씨한테 고정태 구두 얘기 물어봐")
        val later = agent.ask("구두 얘기 다시 해 봐요")

        val (opening, gathering, pressing) = listOf(InvestigationStage.OPENING, InvestigationStage.GATHERING, InvestigationStage.PRESSING)
        assertEquals(listOf(opening, gathering, gathering, gathering, pressing, pressing), stages)
        assertEquals(ReplyKind.HINT, ready.kind)
        assertEquals("son", ready.suspectId)
        assertEquals(
            listOf(son.speaker to "그날 밤 서재 창밖에 고정태가 있었어요.", narrator to NarratorLines.ACCUSE_NUDGE),
            speech.spoken.drop(2).take(2).map { it.speaker to it.text },
        )
        assertEquals(ReplyKind.ANSWER, later.kind)
        assertEquals(listOf("다 말씀드렸잖아요."), later.sentences)
        val state = agent.state.value
        assertEquals(InvestigationStage.READY, state.stage)
        assertEquals(jadeKeyReactions, state.confronted)
        assertTrue(state.nudged)
        // The later turn ended without a narrator line, which ends the run of the last one.
        assertNull(state.lastHint)
        assertTrue(state.notebook.none { it.answer.contains(NarratorLines.ACCUSE_NUDGE) })
    }

    @Test
    fun `a nudge cut off by barge-in is not counted, so it comes again on the next turn`() = runTest {
        val llm = ScriptedLlm(answer("그날 밤 서재 창밖에 고정태가 있었어요."), answer("다 말씀드렸잖아요."))
        val agent = jadeAgent(llm, speech())
        agent.restore(jadeClues, emptyList(), setOf("k-maid-back-door", "k-niece-window"))

        val pending = async { agent.ask("준기 씨한테 고정태 구두 얘기 물어봐") }
        // The answer plays from 110 to 610 ms, then the nudge.
        advanceTimeBy(800)
        agent.stop()
        val cut = pending.await()
        val afterCut = agent.state.value
        val next = agent.ask("그래서요?")

        assertEquals(ReplyKind.INTERRUPTED, cut.kind)
        assertEquals(NarratorLines.ACCUSE_NUDGE, cut.narratorLine)
        assertEquals("그날 밤 서재 창밖에 고정태가 있었어요.", cut.suspectText)
        assertEquals(InvestigationStage.READY, afterCut.stage)
        assertFalse(afterCut.nudged)
        assertNull(afterCut.lastHint)
        assertEquals(listOf("다 말씀드렸잖아요.", NarratorLines.ACCUSE_NUDGE), next.sentences)
        assertTrue(agent.state.value.nudged)
    }

    @Test
    fun `a case without key reactions is ready with its last clue, and the next answered turn brings the nudge`() = runTest {
        val llm = ScriptedLlm(listOf(100L to LlmEvent.Failure("upstream_unavailable", "down")), answer("저장고에 있었습니다."))
        val speech = speech()
        val agent = agent(llm, speech)
        scenario.case.clues.forEach { agent.unlockClue(it.id) }
        val stage = agent.state.value.stage

        val offline = agent.ask("집사한테 정전 때 어디 있었는지 물어봐")
        val answered = agent.ask("정전 때 어디 있었어요?")

        assertEquals(InvestigationStage.READY, stage)
        // Unlocking is no turn, and a fallback line is no answer to follow.
        assertEquals(ReplyKind.FALLBACK, offline.kind)
        assertEquals(ReplyKind.HINT, answered.kind)
        assertEquals(listOf("저장고에 있었습니다.", NarratorLines.ACCUSE_NUDGE), answered.sentences)
        assertEquals(1, speech.spoken.count { it.text == NarratorLines.ACCUSE_NUDGE })
    }

    @Test
    fun `a story clue that completes the investigation brings the nudge on the next turn, once`() = runTest {
        val llm = ScriptedLlm(
            answer("박만수 아저씨를 잠깐 만났어요."),
            answer("창을 잠깐 열어서 눈을 털었을 뿐이에요."),
            answer("그날 밤 서재 창밖에 고정태가 있었어요."),
            answer("그날 밤엔 열 시쯤 잤어요."),
            answer("정말이에요."),
        )
        val speech = speech()
        val agent = jadeAgent(llm, speech)
        (jadeClues - "clue-barefoot").forEach(agent::unlockClue)
        agent.ask("옥분 씨한테 뒷문 얘기 물어봐")
        agent.ask("하린 씨한테 서재 창문 얘기 물어봐")
        agent.ask("준기 씨한테 고정태 구두 얘기 물어봐")
        val beforeLastClue = agent.state.value.stage

        agent.unlockClue("clue-barefoot")
        val next = agent.ask("옥분 씨, 그날 밤 어디 있었어요?")
        val after = agent.ask("정말이에요?")

        assertEquals(InvestigationStage.GATHERING, beforeLastClue)
        assertEquals(ReplyKind.HINT, next.kind)
        assertEquals(listOf("그날 밤엔 열 시쯤 잤어요.", NarratorLines.ACCUSE_NUDGE), next.sentences)
        assertEquals(ReplyKind.ANSWER, after.kind)
        assertEquals(1, speech.spoken.count { it.text == NarratorLines.ACCUSE_NUDGE })
    }

    @Test
    fun `a claim heard before its clue is found brings the narrator's comparison once that clue breaks it`() = runTest {
        val llm = ScriptedLlm(answer("방에만 있었어요."), answer("열 시쯤 잤어요."), answer("정말이에요."))
        val speech = speech()
        val agent = jadeAgent(llm, speech)
        val son = jade.suspect("son")
        val barefoot = jade.case.clue("clue-barefoot")
        val compare = NarratorLines.compare(son, barefoot)

        agent.ask("준기 씨, 그날 밤 어디 있었어요?")
        agent.unlockClue(barefoot.id)
        val next = agent.ask("옥분 씨, 그날 밤 어디 있었어요?")
        val after = agent.ask("정말이에요?")

        assertEquals("탐정님, 서준기 씨가 아까 한 말을 골목의 맨발 자국 이야기와 견줘 보세요.", compare)
        assertEquals(ReplyKind.HINT, next.kind)
        assertEquals(listOf("열 시쯤 잤어요.", compare), next.sentences)
        assertEquals(ReplyKind.ANSWER, after.kind)
        assertEquals(1, speech.spoken.count { it.text == compare })
        assertEquals(setOf("son" to "k-son-barefoot"), agent.state.value.caught)
        assertTrue(agent.state.value.notebook.none { it.answer.contains(compare) })
    }

    @Test
    fun `confronting the suspect with the clue that breaks its claim leaves the narrator nothing to compare`() = runTest {
        val llm = ScriptedLlm(answer("방에만 있었어요."), answer("그래요, 골목까지 뛰어나갔어요."), answer("열 시쯤 잤어요."))
        val speech = speech()
        val agent = jadeAgent(llm, speech)
        val compare = NarratorLines.compare(jade.suspect("son"), jade.case.clue("clue-barefoot"))

        agent.ask("준기 씨, 그날 밤 어디 있었어요?")
        agent.unlockClue("clue-barefoot")
        val confronted = agent.ask("준기 씨, 골목 맨발 자국은 뭐예요?")
        val next = agent.ask("옥분 씨, 그날 밤 어디 있었어요?")

        assertEquals(ReplyKind.ANSWER, confronted.kind)
        assertEquals(ReplyKind.ANSWER, next.kind)
        assertTrue(speech.spoken.none { it.text == compare })
        assertEquals(setOf("son" to "k-son-barefoot"), agent.state.value.caught)
    }

    @Test
    fun `a claim already broken when the player first hears the suspect brings no comparison`() = runTest {
        val llm = ScriptedLlm(answer("방에만 있었어요."), answer("열 시쯤 잤어요."))
        val speech = speech()
        val agent = jadeAgent(llm, speech)
        agent.unlockClue("clue-barefoot")

        agent.ask("준기 씨, 그날 밤 어디 있었어요?")
        val next = agent.ask("옥분 씨, 그날 밤 어디 있었어요?")

        assertEquals(ReplyKind.ANSWER, next.kind)
        assertEquals(setOf("son" to "k-son-crown"), agent.state.value.claimsHeard)
        assertTrue(speech.spoken.none { it.text.startsWith("탐정님, 서준기 씨가 아까 한 말을") })
    }

    @Test
    fun `a key reaction that takes a claim back is left to the hints, which name no one`() = runTest {
        val llm = ScriptedLlm(answer("방에만 있었어요."), answer("열 시쯤 잤어요."))
        val speech = speech()
        val agent = jadeAgent(llm, speech)

        agent.ask("준기 씨, 그날 밤 어디 있었어요?")
        // The window and the shoes bring k-son-shoes within reach, the key reaction taking both his claims back.
        agent.unlockClue("clue-window")
        agent.unlockClue("clue-shoes")
        val next = agent.ask("옥분 씨, 그날 밤 어디 있었어요?")

        assertEquals(ReplyKind.ANSWER, next.kind)
        assertEquals(listOf("열 시쯤 잤어요."), next.sentences)
        assertEquals(emptySet<Pair<String, String>>(), agent.state.value.caught)
    }

    @Test
    fun `a leak on the turn that reaches READY takes the turn, and the nudge comes on the next one`() = runTest {
        val llm = ScriptedLlm(answer("그날 밤 서재 창밖에 고정태가 있었어요. 범인은 하린이에요."), answer("다 말씀드렸잖아요."))
        val agent = jadeAgent(llm, speech())
        agent.restore(jadeClues, emptyList(), setOf("k-maid-back-door", "k-niece-window"))

        val leaked = agent.ask("준기 씨한테 고정태 구두 얘기 물어봐")
        val next = agent.ask("그래서요?")

        assertEquals(listOf("그날 밤 서재 창밖에 고정태가 있었어요.", jade.case.spoilerHint), leaked.sentences)
        assertEquals(listOf("다 말씀드렸잖아요.", NarratorLines.ACCUSE_NUDGE), next.sentences)
    }

    @Test
    fun `a confrontation cut by a leak is not suggested straight back`() = runTest {
        val agent = jadeAgent(ScriptedLlm(answer("제가 화관을 건넸어요.")), speech())
        agent.unlockClue("clue-back-door")

        val reply = agent.ask("옥분 씨한테 뒷문에서 누구 만났는지 물어봐")

        // The maid's back-door reaction is the only one within reach, so the hint moves on to the clues.
        assertEquals(ReplyKind.HINT, reply.kind)
        assertTrue(reply.suspectText in jade.suspect("maid").deflections)
        assertEquals(jade.case.clue("clue-window").hint, reply.narratorLine)
        assertTrue(agent.state.value.confronted.isEmpty())
    }

    @Test
    fun `an answer-seeking question gets a hint after the suspect's words, but not the same hint twice in a row`() = runTest {
        val llm = ScriptedLlm(
            listOf(100L to LlmEvent.Failure("upstream_unavailable", "down")),
            listOf(100L to delta("생각할 필요 없이 답하면 된다. "), 10L to done()),
            answer("정말 모르겠어요."),
            answer("옥분이가 수상해요."),
        )
        val speech = speech()
        val agent = jadeAgent(llm, speech)
        val niece = jade.suspect("niece")
        val backDoor = jade.case.clue("clue-back-door")

        val offline = agent.ask("하린 씨는 누가 범인 같아요?")
        val deflected = agent.ask("하린 씨는 누가 그랬다고 생각해요?")
        val repeated = agent.ask("그럼 범인이 누구 같아요?")
        agent.unlockClue(backDoor.id)
        val next = agent.ask("그래도 범인은 누구일까요?")

        // Without an answer there is nothing to follow, so the fallback line stands alone.
        assertEquals(ReplyKind.FALLBACK, offline.kind)
        assertEquals(listOf(niece.fallbackOffline), offline.sentences)
        assertEquals(ReplyKind.HINT, deflected.kind)
        assertEquals("niece", deflected.suspectId)
        assertTrue(deflected.sentences.first() in niece.deflections)
        assertEquals(backDoor.hint, deflected.sentences.last())
        assertEquals(listOf(niece.speaker, narrator), speech.spoken.drop(1).take(2).map { it.speaker })
        assertEquals(ReplyKind.ANSWER, repeated.kind)
        assertEquals(listOf("정말 모르겠어요."), repeated.sentences)
        // A new hint is given again: the back door put the maid's reaction within reach.
        val confront = NarratorLines.confrontHint(backDoor)
        assertEquals(listOf("옥분이가 수상해요.", confront), next.sentences)
        assertEquals(confront, agent.state.value.lastHint)
        // Narrator lines are never testimony.
        val answers = listOf(deflected.sentences.first(), "정말 모르겠어요.", "옥분이가 수상해요.")
        assertEquals(answers, agent.state.value.notebook.map { it.answer })
    }

    @Test
    fun `a quiet turn ends the run, so the same hint can come again two turns later`() = runTest {
        val llm = ScriptedLlm(answer("정말 모르겠어요."), answer("방에서 잤어요."), answer("모르겠어요."))
        val agent = jadeAgent(llm, speech())
        val hint = jade.case.clues.firstNotNullOf { it.hint }

        val first = agent.ask("하린 씨는 누가 범인 같아요?")
        val quiet = agent.ask("그날 밤 어디 있었어요?")
        val cleared = agent.state.value.lastHint
        val again = agent.ask("그럼 범인이 누구 같아요?")

        assertEquals(listOf("정말 모르겠어요.", hint), first.sentences)
        assertEquals(ReplyKind.ANSWER, quiet.kind)
        assertNull(cleared)
        assertEquals(listOf("모르겠어요.", hint), again.sentences)
        assertEquals(hint, agent.state.value.lastHint)
    }

    @Test
    fun `a reply keeps the suspect's words apart from the narrator's line`() = runTest {
        val llm = ScriptedLlm(answer("정말 모르겠어요."), answer("방에서 잤어요."))
        val agent = jadeAgent(llm, speech())
        val hint = jade.case.clues.firstNotNullOf { it.hint }

        val overview = agent.summarize()
        val askWho = agent.ask("오늘 날씨 어때?")
        val hinted = agent.ask("하린 씨는 누가 범인 같아요?")
        val answered = agent.ask("그날 밤 어디 있었어요?")

        // The narrator alone: nothing of it is the suspect's.
        for (reply in listOf(overview, askWho)) {
            assertNull(reply.suspectId)
            assertNull(reply.narratorLine)
            assertEquals("", reply.suspectText)
        }
        assertEquals(NarratorLines.askWho(jade.case), askWho.text)
        assertEquals(ReplyKind.HINT, hinted.kind)
        assertEquals(hint, hinted.narratorLine)
        assertEquals("정말 모르겠어요.", hinted.suspectText)
        assertEquals("정말 모르겠어요. $hint", hinted.text)
        assertEquals(ReplyKind.ANSWER, answered.kind)
        assertNull(answered.narratorLine)
        assertEquals("방에서 잤어요.", answered.suspectText)
        // suspectText is what the notebook keeps.
        assertEquals(listOf(hinted.suspectText, answered.suspectText), agent.state.value.notebook.map { it.answer })
    }

    @Test
    fun `pressure lines follow the stage from before the turn when configured and stay out otherwise`() = runTest {
        val son = jade.suspect("son")
        val off = ScriptedLlm(answer("창밖에 고정태가 있었어요."), answer("다 말씀드렸어요."))
        val on = ScriptedLlm(answer("창밖에 고정태가 있었어요."), answer("다 말씀드렸어요."))
        for ((llm, config) in listOf(off to ownWords, on to ownWords.copy(pressureLines = true))) {
            val agent = jadeAgent(llm, speech(), config)
            agent.restore(jadeClues, emptyList(), setOf("k-maid-back-door", "k-niece-window"))
            // Draws out the last key reaction, so the stage is READY from the next turn on.
            agent.ask("준기 씨한테 고정태 구두 얘기 물어봐")
            agent.ask("그날 밤 일 다시 말해 봐요")
        }

        val template = jade.suspectTemplate
        val plain = SuspectPrompt.render(jade.case, son, jadeClues, PromptVariant.AWARE, template)
        assertEquals(listOf(plain, plain), off.requests.map { it.system })
        val staged = listOf(InvestigationStage.PRESSING, InvestigationStage.READY).map {
            SuspectPrompt.render(jade.case, son, jadeClues, PromptVariant.AWARE, template, stage = it)
        }
        assertEquals(staged, on.requests.map { it.system })
        assertTrue(son.pressure.getValue(InvestigationStage.PRESSING) in on.requests[0].system)
        assertTrue(son.pressure.getValue(InvestigationStage.READY) in on.requests[1].system)
        assertFalse(son.pressure.getValue(InvestigationStage.PRESSING) in plain)
    }

    @Test
    fun `a culprit cornered at READY by the clue that broke its story confesses unguarded, and stays cornered`() = runTest {
        val llm = ScriptedLlm(answer("제가 화관을 건넸어요."), answer("같이 떠나자는 그 사람 말을 믿었어요."))
        val agent = jadeAgent(llm, speech())
        agent.restore(jadeClues, emptyList(), jadeKeyReactions)
        val confession = jade.suspect("niece").confession!!

        val cornered = agent.ask("하린 씨, 창 안쪽 실내화 자국 당신 거잖아요. 당신이 범인이죠?")
        val after = agent.ask("하린 씨, 왜 그랬어요?")

        // The confession is meant to give her away, so the forbidden phrases do not cut it, and the narrator stays quiet.
        assertEquals(ReplyKind.ANSWER, cornered.kind)
        assertEquals(listOf("제가 화관을 건넸어요."), cornered.sentences)
        assertNull(cornered.narratorLine)
        assertTrue(llm.requests[0].system.contains(confession))
        // Once the player heard her answer, a question that brings up no clue keeps the confession.
        assertTrue(agent.state.value.cornered)
        assertTrue(llm.requests[1].system.contains(confession))
        assertEquals(ReplyKind.ANSWER, after.kind)
    }

    @Test
    fun `accused without the clue, or before READY, the culprit stays guarded`() = runTest {
        val llm = ScriptedLlm(answer("제가 화관을 건넸어요."), answer("제가 화관을 건넸어요."))
        val agent = jadeAgent(llm, speech())
        val confession = jade.suspect("niece").confession!!

        agent.restore(jadeClues, emptyList(), jadeKeyReactions)
        val bare = agent.ask("하린 씨, 당신이 범인이죠?")
        agent.restore(jadeClues, emptyList(), emptySet())
        val early = agent.ask("하린 씨, 창 안쪽 실내화 자국 당신 거잖아요. 당신이 범인이죠?")

        assertEquals(InvestigationStage.PRESSING, agent.state.value.stage)
        assertTrue(llm.requests.none { it.system.contains(confession) })
        assertTrue(llm.requests.all { it.system.contains("# 너만 아는 것") })
        // The guard cut both admissions, as it does any leak.
        assertTrue((bare.sentences + early.sentences).none { it.contains("화관을 건넸") })
        assertFalse(agent.state.value.cornered)
    }

    @Test
    fun `an answer does not open with the interjections the suspect's previous answer opened with`() = runTest {
        val llm = ScriptedLlm(answer("아이고, 저는 저장고에 있었습니다."), answer("아이고, 그 뒤로는 모릅니다."), answer("아이고. 정말 모릅니다."))
        val agent = agent(llm, speech())

        val first = agent.ask("집사한테 정전 때 어디 있었는지 물어봐")
        val second = agent.ask("집사님, 그 뒤에는요?")
        val third = agent.ask("집사님, 정말요?")

        assertEquals(listOf("아이고, 저는 저장고에 있었습니다."), first.sentences)
        assertEquals(listOf("그 뒤로는 모릅니다."), second.sentences)
        // The notebook keeps what was heard, so the third answer's opening is not a repeat and stays.
        assertEquals(listOf("아이고.", "정말 모릅니다."), third.sentences)
    }

    @Test
    fun `restore resumes a saved game with its progress and rejects unknown ids`() = runTest {
        val agent = jadeAgent(ScriptedLlm(answer("장물아비요?")), speech())
        agent.ask("옥분 씨한테 그날 밤 일 물어봐") // A deflection and a hint, and the maid is being questioned.
        val notebook = listOf(Testimony("maid", "뒷문 얘기 해 봐요", "박만수 아저씨를 잠깐 만났어요."))

        agent.restore(setOf("clue-back-door"), notebook, setOf("k-maid-back-door"))
        val gathering = agent.state.value
        agent.restore(jadeClues, notebook, setOf("k-maid-back-door"))
        val pressing = agent.state.value.stage
        agent.restore(jadeClues, notebook, jadeKeyReactions)

        val expected = AgentState(
            unlockedClues = setOf("clue-back-door"), notebook = notebook, game = 1,
            confronted = setOf("k-maid-back-door"), stage = InvestigationStage.GATHERING,
        )
        assertEquals(expected, gathering)
        assertEquals(InvestigationStage.PRESSING, pressing)
        assertEquals(InvestigationStage.READY, agent.state.value.stage)
        assertThrows(IllegalArgumentException::class.java) { agent.restore(setOf("clue-nope"), emptyList()) }
        // The maid's window reaction is not key, and the banker is in the cast but no suspect.
        assertThrows(IllegalArgumentException::class.java) { agent.restore(emptySet(), emptyList(), setOf("k-maid-window")) }
        assertThrows(IllegalArgumentException::class.java) { agent.restore(emptySet(), listOf(Testimony("banker", "질문", "대답"))) }
        assertEquals(3, agent.state.value.game)
        assertEquals(jadeKeyReactions, agent.state.value.confronted)
    }

    @Test
    fun `restore cuts testimony to the proxy's message limit, as asking does`() = runTest {
        val llm = ScriptedLlm(answer("네, 탐정님."))
        val agent = jadeAgent(llm, speech())
        val long = Testimony("maid", "질".repeat(2_000), "답".repeat(2_000))

        agent.restore(emptySet(), listOf(long))
        agent.ask("옥분 씨, 그날 밤 일 다시 말해 봐요")

        val restored = agent.state.value.notebook.first()
        assertEquals(ProxyContract.MAX_MESSAGE_CHARS, restored.question.length)
        assertEquals(ProxyContract.MAX_MESSAGE_CHARS, restored.answer.length)
        // So the history sent along with the next question stays within the contract too.
        assertTrue(llm.requests.single().messages.all { it.content.length <= ProxyContract.MAX_MESSAGE_CHARS })
    }

    @Test
    fun `restore stops the turn in progress, which cannot write into the restored game`() = runTest {
        val llm = ScriptedLlm(listOf(100L to delta("첫 문장이에요. "), 5_000L to done()))
        val agent = jadeAgent(llm, speech())

        val pending = async { agent.ask("옥분 씨한테 물어봐") }
        advanceTimeBy(200)
        agent.restore(setOf("clue-back-door"), emptyList())
        val reply = pending.await()

        assertEquals(ReplyKind.INTERRUPTED, reply.kind)
        assertEquals(AgentState(unlockedClues = setOf("clue-back-door"), game = 1, stage = InvestigationStage.GATHERING), agent.state.value)
    }

    @Test
    fun `an answer that opens by reading out its instructions is replaced by a deflection`() = runTest {
        val llm = ScriptedLlm(listOf(100L to delta("생각할 필요 없이 답하면 된다. "), 100L to delta("저장고에 있었습니다."), 10L to done()))
        val speech = speech()

        val reply = agent(llm, speech).ask("집사한테 물어봐")

        assertEquals(ReplyKind.DEFLECTION, reply.kind)
        assertTrue(reply.sentences.single() in butler.deflections)
        assertEquals(reply.sentences, speech.spoken.map { it.text })
        assertEquals(1, llm.cancelled)
    }

    @Test
    fun `an answer that opens by talking about the suspect by name is replaced by a deflection`() = runTest {
        val llm = ScriptedLlm(listOf(100L to delta("${butler.name}은 모르는 일이라고 답해야 합니다. "), 100L to delta("저장고에 있었습니다."), 10L to done()))
        val speech = speech()

        val reply = agent(llm, speech).ask("집사한테 물어봐")

        assertEquals(ReplyKind.DEFLECTION, reply.kind)
        assertTrue(reply.sentences.single() in butler.deflections)
        assertEquals(reply.sentences, speech.spoken.map { it.text })
    }

    @Test
    fun `an answer that opens with an English note to itself is replaced by a deflection`() = runTest {
        val llm = ScriptedLlm(
            listOf(100L to delta("[think]\nPlayer asks about the night. Keep short, in character.\n[/think]\n\n"), 100L to delta("저장고에 있었습니다."), 10L to done()),
        )
        val speech = speech()

        val reply = agent(llm, speech).ask("집사한테 물어봐")

        assertEquals(ReplyKind.DEFLECTION, reply.kind)
        assertTrue(reply.sentences.single() in butler.deflections)
        assertEquals(reply.sentences, speech.spoken.map { it.text })
    }

    @Test
    fun `stage directions are dropped and the rest of the answer is spoken`() = runTest {
        val llm = ScriptedLlm(
            listOf(100L to delta("[짧게] 저는 저장고에 있었습니다. "), 100L to delta("(한숨). "), 100L to delta("정말입니다."), 10L to done()),
        )
        val speech = speech()

        val reply = agent(llm, speech).ask("집사한테 물어봐")

        assertEquals(ReplyKind.ANSWER, reply.kind)
        assertEquals(listOf("저는 저장고에 있었습니다.", "정말입니다."), reply.sentences)
        assertEquals(reply.sentences, speech.spoken.map { it.text })
    }

    @Test
    fun `a read-out last sentence is not spoken even without a trailing space`() = runTest {
        val llm = ScriptedLlm(listOf(100L to delta("저는 저장고에 있었습니다. "), 100L to delta("규칙대로 짧게 답한다"), 10L to done()))
        val speech = speech()

        val reply = agent(llm, speech).ask("집사한테 물어봐")

        assertEquals(ReplyKind.ANSWER, reply.kind)
        assertEquals(listOf("저는 저장고에 있었습니다."), reply.sentences)
        assertEquals(listOf("저는 저장고에 있었습니다."), speech.spoken.map { it.text })
    }

    @Test
    fun `the narrator's summary may quote the overview's plain endings`() = runTest {
        val llm = ScriptedLlm(listOf(100L to delta("만찬 도중 정전이 일어났다. 장갑이 젖어 있었어요. "), 10L to done()))
        val agent = agent(llm, speech())
        agent.unlockClue("clue-wet-gloves")

        val reply = agent.summarize()

        assertEquals(ReplyKind.SUMMARY, reply.kind)
        assertEquals(listOf("만찬 도중 정전이 일어났다.", "장갑이 젖어 있었어요."), reply.sentences)
    }

    @Test
    fun `a question about a clue not found yet keeps the suspect to its own facts`() = runTest {
        val llm = ScriptedLlm(answer("열쇠 말씀이십니까."), answer("열쇠는 제가 챙깁니다."))
        val agent = agent(llm, speech())

        agent.ask("집사한테 지하실 열쇠 누가 갖고 있는지 물어봐")
        agent.unlockClue("clue-cellar-key")
        agent.ask("지하실 열쇠 누가 갖고 있어요?")

        val template = scenario.suspectTemplate
        val noted = SuspectPrompt.render(scenario.case, butler, emptySet(), PromptVariant.AWARE, template, listOf("열쇠"))
        assertTrue("'열쇠' 이야기는" in noted)
        assertEquals(noted, llm.requests[0].system)
        // Once the key is found it is part of the suspect's own facts, so no rule is added.
        val found = SuspectPrompt.render(scenario.case, butler, setOf("clue-cellar-key"), PromptVariant.AWARE, template)
        assertEquals(found, llm.requests[1].system)
    }

    @Test
    fun `a clue the player has not found or brought up is cut before it is spoken`() = runTest {
        val llm = ScriptedLlm(
            listOf(100L to delta("저는 저장고에 있었습니다. "), 100L to delta("손목시계는 아가씨가 맡겼습니다. "), 100L to delta("끝입니다."), 10L to done()),
            answer("영수증은 처음 봅니다. 전당포도 모릅니다."),
        )
        val agent = agent(llm, speech())

        val volunteered = agent.ask("집사한테 어젯밤 일 물어봐")
        val asked = agent.ask("전당포 영수증 본 적 있어요?")

        assertEquals(listOf("저는 저장고에 있었습니다."), volunteered.sentences)
        assertEquals(1, llm.cancelled)
        // The player raised these words, so saying them answers the question instead of leaking.
        assertEquals(listOf("영수증은 처음 봅니다.", "전당포도 모릅니다."), asked.sentences)
    }

    @Test
    fun `a clue word the player used in an earlier turn may come up again`() = runTest {
        val llm = ScriptedLlm(answer("열쇠는 잘 모릅니다."), answer("저장고에 있었습니다. 열쇠 일은 아까 말씀드린 대로입니다."))
        val agent = agent(llm, speech())

        agent.ask("집사한테 열쇠 얘기 물어봐")
        val later = agent.ask("정전 때 어디 있었어요?")

        // The topic rule looks at the new question only, but the earlier exchange is still sent.
        assertTrue("지금 꺼낸" !in llm.requests[1].system)
        assertEquals(listOf("저장고에 있었습니다.", "열쇠 일은 아까 말씀드린 대로입니다."), later.sentences)
    }

    @Test
    fun `answers stop after three sentences`() = runTest {
        val llm = ScriptedLlm(listOf(100L to delta("저장고에 있었습니다. 와인을 고르고 있었습니다. 불이 나갔습니다. 그 뒤로는 모릅니다. "), 100L to done()))

        val reply = agent(llm, speech()).ask("집사한테 물어봐")

        assertEquals(listOf("저장고에 있었습니다.", "와인을 고르고 있었습니다.", "불이 나갔습니다."), reply.sentences)
        assertEquals(1, llm.cancelled)
    }

    @Test
    fun `a short opener does not push the last sentence out`() = runTest {
        val llm = ScriptedLlm(listOf(100L to delta("하, 참. 창밖이요? 못 봤어요, 탐정님. 그때는 화관만 보고 있었거든요."), 100L to done()))

        val reply = agent(llm, speech()).ask("집사한테 물어봐")

        assertEquals(listOf("하, 참.", "창밖이요?", "못 봤어요, 탐정님.", "그때는 화관만 보고 있었거든요."), reply.sentences)
        assertEquals(0, llm.cancelled)
    }

    @Test
    fun `openers are free but the third counting sentence still ends the answer`() = runTest {
        val llm = ScriptedLlm(
            listOf(100L to delta("네? 하, 참. 저장고에 있었습니다. 와인을 고르고 있었습니다. 불이 나갔습니다. 그 뒤로는 모릅니다. "), 100L to done()),
        )

        val reply = agent(llm, speech()).ask("집사한테 물어봐")

        assertEquals(listOf("네?", "하, 참.", "저장고에 있었습니다.", "와인을 고르고 있었습니다.", "불이 나갔습니다."), reply.sentences)
        assertEquals(1, llm.cancelled)
    }

    @Test
    fun `naming an addressee switches suspect while a mere mention keeps the current one`() = runTest {
        val llm = ScriptedLlm(answer("저장고에 있었습니다."), answer("부엌에 있었어요."), answer("집사님은 못 봤어요."))
        val speech = speech()
        val agent = agent(llm, speech)

        agent.ask("집사한테 정전 때 어디 있었는지 물어봐")
        val second = agent.ask("요리사한테 부엌 얘기 물어봐")
        val third = agent.ask("집사는 그때 어디 있었어요?")

        assertEquals("cook", second.suspectId)
        assertEquals("cook", third.suspectId)
        assertEquals(listOf(butler.speaker, cook.speaker, cook.speaker), speech.spoken.map { it.speaker })
        assertEquals(listOf(ChatMessage(Role.USER, "요리사한테 부엌 얘기 물어봐")), llm.requests[1].messages)
        assertEquals(
            listOf(
                ChatMessage(Role.USER, "요리사한테 부엌 얘기 물어봐"),
                ChatMessage(Role.ASSISTANT, "부엌에 있었어요."),
                ChatMessage(Role.USER, "집사는 그때 어디 있었어요?"),
            ),
            llm.requests[2].messages,
        )
    }

    @Test
    fun `only the configured number of earlier exchanges is sent`() = runTest {
        val llm = ScriptedLlm(*Array(4) { answer("대답 ${it}입니다.") })
        val agent = agent(llm, speech(), ownWords.copy(historyExchanges = 2))

        repeat(4) { agent.ask("집사한테 질문 $it") }

        val messages = llm.requests.last().messages
        assertEquals(5, messages.size)
        assertEquals("집사한테 질문 1", messages.first().content)
        assertThrows(IllegalArgumentException::class.java) { AgentConfig(historyExchanges = AgentConfig.MAX_HISTORY_EXCHANGES + 1) }
    }

    @Test
    fun `without an addressee or a current suspect the narrator asks whom to question`() = runTest {
        val llm = ScriptedLlm()
        val speech = speech()

        val reply = agent(llm, speech).ask("오늘 날씨 어때?")

        assertEquals(ReplyKind.ASK_WHO, reply.kind)
        assertNull(reply.suspectId)
        assertEquals(listOf(Spoken(narrator, NarratorLines.askWho(scenario.case), 0)), speech.spoken)
        assertTrue(NarratorLines.askWho(scenario.case).contains("집사 한도윤"))
        assertTrue(llm.requests.isEmpty())
    }

    @Test
    fun `an empty utterance asks the player to repeat, in the current suspect's voice`() = runTest {
        val speech = speech()
        val agent = agent(ScriptedLlm(), speech)

        val narrated = agent.ask("   ")
        agent.selectSuspect("cook")
        val reply = agent.ask("")

        assertEquals(listOf(NarratorLines.NOT_UNDERSTOOD), narrated.sentences)
        assertNull(narrated.suspectId)
        assertEquals(ReplyKind.NOT_UNDERSTOOD, reply.kind)
        // Said in the cook's voice, so the reply is hers.
        assertEquals("cook", reply.suspectId)
        assertEquals(listOf(cook.fallbackNotUnderstood), reply.sentences)
        assertEquals(cook.speaker, speech.spoken.last().speaker)
    }

    @Test
    fun `found clues go into the prompt and unknown ids are rejected`() = runTest {
        val llm = ScriptedLlm(answer("두꺼비집은 저장고 옆에 있습니다."))
        val agent = agent(llm, speech())

        agent.unlockClue("clue-fusebox")
        agent.ask("집사한테 두꺼비집 얘기 물어봐")

        val expected = SuspectPrompt.render(scenario.case, butler, setOf("clue-fusebox"), PromptVariant.AWARE, scenario.suspectTemplate)
        assertEquals(expected, llm.requests.single().system)
        assertThrows(IllegalArgumentException::class.java) { agent.unlockClue("clue-nope") }
        assertThrows(IllegalArgumentException::class.java) { agent.selectSuspect("chairman") }
    }

    @Test
    fun `an empty notebook replays the case overview without the LLM`() = runTest {
        val llm = ScriptedLlm()
        val speech = speech()

        val reply = agent(llm, speech).summarize()

        assertEquals(ReplyKind.OVERVIEW, reply.kind)
        assertEquals(listOf(SummaryPrompt.overviewFallback(scenario.case)), reply.sentences)
        assertEquals(narrator, speech.spoken.single().speaker)
        assertTrue(llm.requests.isEmpty())
    }

    @Test
    fun `the summary reads the notebook in the narrator's voice, at most five sentences`() = runTest {
        val llm = ScriptedLlm(
            answer("저장고에 있었습니다."),
            listOf(100L to delta("하나예요. 둘이에요. 셋이에요. 넷이에요. 다섯이에요. 여섯이에요. "), 10L to done()),
        )
        val speech = speech()
        val agent = agent(llm, speech)
        agent.unlockClue("clue-wet-gloves")
        agent.ask("집사한테 정전 때 어디 있었는지 물어봐")

        val reply = agent.summarize()

        assertEquals(ReplyKind.SUMMARY, reply.kind)
        assertNull(reply.suspectId)
        assertEquals(5, reply.sentences.size)
        val request = llm.requests[1]
        assertEquals(LlmTask.SUMMARY, request.task)
        val notebook = agent.state.value.notebook
        assertEquals(SummaryPrompt.render(scenario.case, scenario.summaryTemplate, setOf("clue-wet-gloves"), notebook), request.system)
        assertEquals(listOf(ChatMessage(Role.USER, SummaryPrompt.REQUEST)), request.messages)
        assertTrue(speech.spoken.takeLast(5).all { it.speaker == narrator })
    }

    @Test
    fun `a failed summary reads the titles of the clues found`() = runTest {
        val llm = ScriptedLlm(listOf(100L to LlmEvent.Failure("rate_limited", "slow down")))
        val agent = agent(llm, speech())
        agent.unlockClue("clue-fusebox")
        agent.unlockClue("clue-wet-gloves")

        val reply = agent.summarize()

        assertEquals(ReplyKind.SUMMARY_FALLBACK, reply.kind)
        assertEquals("rate_limited", reply.failure)
        val line = reply.sentences.single()
        assertEquals(NarratorLines.offlineSummary(scenario.case, setOf("clue-fusebox", "clue-wet-gloves")), line)
        assertTrue(line.contains("지하실 두꺼비집, 젖은 흰 장갑이에요."))
    }

    @Test
    fun `stop cuts the answer off, stops speech and keeps what was heard`() = runTest {
        val llm = ScriptedLlm(listOf(100L to delta("첫 문장입니다. 둘째 문장입니다. 셋째"), 5_000L to delta(" 문장입니다."), 10L to done()))
        val speech = speech()
        val agent = agent(llm, speech)

        val pending = async { agent.ask("집사한테 물어봐") }
        advanceTimeBy(700)
        agent.stop()
        val reply = pending.await()

        assertEquals(ReplyKind.INTERRUPTED, reply.kind)
        assertEquals(listOf("첫 문장입니다.", "둘째 문장입니다."), reply.sentences)
        assertEquals(1, speech.stops)
        assertEquals(1, llm.cancelled)
        assertEquals("첫 문장입니다. 둘째 문장입니다.", agent.state.value.notebook.single().answer)
    }

    @Test
    fun `a new question interrupts the answer in progress`() = runTest {
        val llm = ScriptedLlm(listOf(100L to delta("길게 설명드리겠습니다. "), 10_000L to done()), answer("부엌에 있었어요."))
        val speech = speech()
        val agent = agent(llm, speech)

        val first = async { agent.ask("집사한테 물어봐") }
        advanceTimeBy(300)
        val second = agent.ask("요리사한테 물어봐")

        assertEquals(ReplyKind.INTERRUPTED, first.await().kind)
        assertEquals(ReplyKind.ANSWER, second.kind)
        assertEquals(listOf("길게 설명드리겠습니다.", "부엌에 있었어요."), speech.spoken.map { it.text })
        assertEquals(1, llm.cancelled)
    }

    @Test
    fun `reset forgets the game and a cut-off turn cannot write into the new one`() = runTest {
        val llm = ScriptedLlm(listOf(100L to delta("첫 문장입니다. "), 5_000L to done()))
        val agent = agent(llm, speech())
        agent.unlockClue("clue-fusebox")

        val pending = async { agent.ask("집사한테 물어봐") }
        advanceTimeBy(200)
        agent.reset()
        pending.await()

        assertEquals(AgentState(game = 1), agent.state.value)
    }

    @Test
    fun `a broken TTS engine is reported without failing the turn`() = runTest {
        val speech = speech().apply { failWith = "ENGINE_ERROR" }

        val reply = agent(ScriptedLlm(answer("저장고에 있었습니다.")), speech).ask("집사한테 물어봐")

        assertEquals(ReplyKind.ANSWER, reply.kind)
        assertEquals("ENGINE_ERROR", reply.speechError)
    }

    @Test
    fun `requests stay within the proxy contract`() = runTest {
        val llm = ScriptedLlm(answer("네."))

        agent(llm, speech()).ask("집사한테 " + "아".repeat(2_000))

        val request = llm.requests.single()
        assertEquals(ProxyContract.MAX_MESSAGE_CHARS, request.messages.single().content.length)
        val largest = scenario.suspects.values.maxOf {
            SuspectPrompt.render(scenario.case, it, scenario.case.clues.map { clue -> clue.id }.toSet(), PromptVariant.TRUTH, scenario.suspectTemplate).length
        }
        assertTrue("largest prompt has $largest characters", largest <= ProxyContract.MAX_SYSTEM_CHARS)
    }

    @Test
    fun `listening hands the recognized question to the agent`() = runTest {
        val llm = ScriptedLlm(answer("부엌에 있었어요."))
        val agent = agent(llm, speech())

        val reply = agent.listenAndAsk { "요리사한테 부엌 얘기 물어봐" }
        val nothing = agent.listenAndAsk { null }

        assertEquals("cook", reply.suspectId)
        assertEquals(ReplyKind.NOT_UNDERSTOOD, nothing.kind)
    }

    @Test
    fun `listening is part of the turn, so stop cancels it and a broken microphone is reported`() = runTest {
        val speech = speech()
        val agent = agent(ScriptedLlm(), speech)

        val listening = async { agent.listenAndAsk { kotlinx.coroutines.awaitCancellation() } }
        advanceTimeBy(1_000)
        agent.stop()
        val broken = agent.listenAndAsk { throw SpeechException("STT MIC_ERROR") }

        assertEquals(ReplyKind.INTERRUPTED, listening.await().kind)
        assertEquals(ReplyKind.NOT_UNDERSTOOD, broken.kind)
        assertEquals("STT MIC_ERROR", broken.speechError)
        assertEquals(listOf(NarratorLines.NOT_UNDERSTOOD), speech.spoken.map { it.text })
    }
}
