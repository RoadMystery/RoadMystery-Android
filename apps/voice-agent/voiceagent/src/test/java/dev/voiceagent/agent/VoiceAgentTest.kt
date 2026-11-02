package dev.voiceagent.agent

import dev.voiceagent.RepoFiles
import dev.voiceagent.agent.FakeSpeech.Spoken
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

    private fun TestScope.speech() = FakeSpeech({ testScheduler.currentTime })

    private fun TestScope.agent(llm: ScriptedLlm, speech: FakeSpeech, config: AgentConfig = AgentConfig()) =
        VoiceAgent(scenario, llm, speech, config, clock = { testScheduler.currentTime }, random = Random(7))

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
        assertEquals(SuspectPrompt.render(scenario.case, butler, emptySet(), PromptVariant.COVER, scenario.suspectTemplate), request.system)
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
    fun `a spoiler in the first sentence is replaced by one of the suspect's deflections`() = runTest {
        val llm = ScriptedLlm(listOf(100L to delta("사실 제가 훔쳤습니다. "), 100L to delta("미안합니다."), 10L to done()))
        val speech = speech()
        val agent = agent(llm, speech)

        val reply = agent.ask("집사한테 범인이 누군지 물어봐")

        assertEquals(ReplyKind.DEFLECTION, reply.kind)
        val line = reply.sentences.single()
        assertTrue(line in butler.deflections)
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
    fun `answers stop after three sentences`() = runTest {
        val llm = ScriptedLlm(listOf(100L to delta("하나입니다. 둘입니다. 셋입니다. 넷입니다. "), 100L to done()))

        val reply = agent(llm, speech()).ask("집사한테 물어봐")

        assertEquals(listOf("하나입니다.", "둘입니다.", "셋입니다."), reply.sentences)
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
        val agent = agent(llm, speech(), AgentConfig(historyExchanges = 2))

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

        assertEquals(listOf(NarratorLines.NOT_UNDERSTOOD), agent.ask("   ").sentences)
        agent.selectSuspect("cook")
        val reply = agent.ask("")

        assertEquals(ReplyKind.NOT_UNDERSTOOD, reply.kind)
        assertEquals(listOf(cook.fallbackNotUnderstood), reply.sentences)
        assertEquals(cook.speaker, speech.spoken.last().speaker)
    }

    @Test
    fun `found clues go into the prompt and unknown ids are rejected`() = runTest {
        val llm = ScriptedLlm(answer("두꺼비집은 저장고 옆에 있습니다."))
        val agent = agent(llm, speech())

        agent.unlockClue("clue-fusebox")
        agent.ask("집사한테 두꺼비집 얘기 물어봐")

        val expected = SuspectPrompt.render(scenario.case, butler, setOf("clue-fusebox"), PromptVariant.COVER, scenario.suspectTemplate)
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
