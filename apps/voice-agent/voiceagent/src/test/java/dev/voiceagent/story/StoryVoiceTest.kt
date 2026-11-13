package dev.voiceagent.story

import dev.voiceagent.RepoFiles
import dev.voiceagent.agent.ReplyKind
import dev.voiceagent.agent.ScriptedLlm
import dev.voiceagent.agent.answer
import dev.voiceagent.content.Suspect
import dev.voiceagent.llm.LlmEvent
import dev.voiceagent.llm.LlmTask
import dev.voiceagent.prompt.SummaryPrompt
import dev.voiceagent.prompt.Testimony
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

/**
 * The team app's StoryEngine questions StoryVoice the way its DefaultStoryEngine does (CaseContext per question).
 * By default the answers are case lines the model names by id, so the fake model replies with ids.
 */
class StoryVoiceTest {
    private val scenario = RepoFiles.scenario(CASE_DIR)
    private val seojin = scenario.suspect("s_seojin")
    private val minhyuk = scenario.suspect("s_minhyuk")

    private fun line(suspect: Suspect, id: String): String =
        (suspect.public.firstOrNull { it.id == id }?.line ?: suspect.unlockable.first { it.id == id }.line)!!

    @Test
    fun `the team scenario is ours under the same ids`() {
        assertEquals("midnight_rest_area", scenario.case.id)
        assertEquals(listOf("s_seojin", "s_minhyuk", "s_jaehee"), scenario.suspects.keys.toList())
        assertEquals(
            listOf("clue_receipt", "clue_dashcam", "clue_pill", "clue_call", "clue_locker"),
            scenario.case.clues.map { it.id },
        )
        assertEquals("s_seojin", scenario.case.solution.culprit)
        // The team's F-03 voices: female_calm, male_rough, female_bright; the narrator reads with sanghyeok.
        assertEquals(listOf("seoyoon", "eunwoo", "haeun"), scenario.suspects.values.map { it.speaker })
    }

    @Test
    fun `the suspect chosen on screen answers even when the question names another`() = runTest {
        val llm = ScriptedLlm(answer("k-seojin-culprit"))
        val voice = StoryVoice(scenario, llm)

        val reply = voice.ask("s_seojin", "기사님한테 물어볼게요, 블랙박스 왜 껐대요?", emptySet())

        assertEquals(line(seojin, "k-seojin-culprit"), reply.text)
        assertEquals(listOf("k-seojin-culprit"), reply.lineIds)
        assertTrue(reply.isTestimony)
        assertEquals(ReplyKind.ANSWER, reply.kind)
        assertTrue(llm.requests.single().system.contains("${seojin.name}(${seojin.role})"))
        assertEquals("s_seojin", voice.state.value.currentSuspect)
    }

    @Test
    fun `whatever else the model replies, only the case line it names comes back`() = runTest {
        val llm = ScriptedLlm(answer("k-seojin-alibi 사실 제가 가방을 로커에 넣었어요."), answer("제가 가방을 로커에 넣었어요."))
        val voice = StoryVoice(scenario, llm)

        val named = voice.ask("s_seojin", "자정 무렵 어디 있었나요?", emptySet())
        val none = voice.ask("s_seojin", "가방은 어디 있어요?", emptySet())

        assertEquals(line(seojin, "k-seojin-alibi"), named.text)
        // A reply naming no line gets one of the suspect's deflections; nothing of the reply is said.
        assertEquals(ReplyKind.DEFLECTION, none.kind)
        assertTrue(none.text in seojin.deflections)
        assertFalse(listOf(named.text, none.text).any { "로커에 넣었" in it })
    }

    @Test
    fun `the narrator's line stays out of what the suspect said`() = runTest {
        val llm = ScriptedLlm(answer("k-seojin-culprit"))
        val voice = StoryVoice(scenario, llm)

        val reply = voice.ask("s_seojin", "서진 씨, 범인이 누구예요?", emptySet())

        // An answer-seeking question before any clue: the narrator points at the first clue's scene.
        assertEquals(ReplyKind.HINT, reply.kind)
        assertEquals(line(seojin, "k-seojin-culprit"), reply.text)
        assertEquals(scenario.case.clues.first().hint, reply.narratorLine)
    }

    @Test
    fun `it follows the clues StoryEngine found and skips ids the case lacks`() = runTest {
        val llm = ScriptedLlm(answer("k-seojin-receipt"), answer("k-seojin-coffee"))
        val voice = StoryVoice(scenario, llm)

        voice.ask("s_seojin", "커피는 왜 두 잔 샀나요?", setOf("clue_receipt", "clue_from_a_newer_script"))
        assertEquals(setOf("clue_receipt"), voice.state.value.unlockedClues)
        assertTrue(llm.requests[0].system.contains(scenario.case.clue("clue_receipt").summary))

        voice.ask("s_seojin", "블랙박스는요?", setOf("clue_receipt", "clue_dashcam"))
        assertEquals(setOf("clue_receipt", "clue_dashcam"), voice.state.value.unlockedClues)
        // The earlier exchange goes along as history, its answer as the id of the line it was.
        assertEquals(3, llm.requests[1].messages.size)
        assertEquals("k-seojin-receipt", llm.requests[1].messages[1].content)
    }

    @Test
    fun `fewer clues than the last question means a new game`() = runTest {
        val llm = ScriptedLlm(answer("k-seojin-receipt"), answer("k-seojin-alibi"))
        val voice = StoryVoice(scenario, llm)
        voice.ask("s_seojin", "커피는 왜 두 잔 샀나요?", setOf("clue_receipt", "clue_dashcam"))

        voice.ask("s_seojin", "자정 무렵 어디 있었나요?", emptySet())

        assertEquals(emptySet<String>(), voice.state.value.unlockedClues)
        assertEquals(1, llm.requests[1].messages.size)
        assertEquals(listOf(line(seojin, "k-seojin-alibi")), voice.state.value.notebook.map { it.answer })
    }

    @Test
    fun `no answer from the proxy gives the suspect's own line, never an empty reply`() = runTest {
        val llm = ScriptedLlm(listOf(10L to LlmEvent.Failure("network", "IOException")))
        val voice = StoryVoice(scenario, llm)

        val reply = voice.ask("s_minhyuk", "자정 무렵 어디 있었나요?", emptySet())

        assertEquals(ReplyKind.FALLBACK, reply.kind)
        assertEquals(minhyuk.fallbackOffline, reply.text)
        assertEquals("network", reply.failure)
        // It says nothing about the case, so the team app does not keep it as testimony.
        assertFalse(reply.isTestimony)
    }

    @Test
    fun `an unknown suspect is refused before anything is sent`() = runTest {
        val llm = ScriptedLlm()
        val voice = StoryVoice(scenario, llm)

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { voice.ask("s_nobody", "누구세요?", emptySet()) }
        }
        assertTrue(llm.requests.isEmpty())
    }

    @Test
    fun `the summary reads StoryEngine's notebook without the lines for a dropped connection`() = runTest {
        val llm = ScriptedLlm(answer("박서진은 편의점과 화장실에 있었다고 했어요."))
        val voice = StoryVoice(scenario, llm)
        val heard = Testimony("s_seojin", "자정 무렵 어디 있었나요?", "편의점에서 물을 사고 화장실에 다녀왔습니다.")
        val offline = Testimony("s_minhyuk", "블랙박스는 왜 꺼져 있었죠?", minhyuk.fallbackOffline)

        val summary = voice.summarize(listOf("clue_receipt"), listOf(heard, offline))

        assertEquals("박서진은 편의점과 화장실에 있었다고 했어요.", summary)
        val request = llm.requests.single()
        assertEquals(LlmTask.SUMMARY, request.task)
        assertTrue(request.system.contains(heard.answer))
        assertFalse(request.system.contains(minhyuk.fallbackOffline))
        // The agent's own game is left alone: the notebook stays StoryEngine's.
        assertTrue(voice.state.value.notebook.isEmpty())
        assertTrue(voice.state.value.unlockedClues.isEmpty())
    }

    @Test
    fun `an empty notebook replays the case overview without the LLM`() = runTest {
        val llm = ScriptedLlm()
        val voice = StoryVoice(scenario, llm)

        assertEquals(SummaryPrompt.overviewFallback(scenario.case), voice.summarize(emptyList(), emptyList()))
        assertTrue(llm.requests.isEmpty())
    }

    @Test
    fun `in the question pause a no or silence lets the story go on`() {
        val voice = StoryVoice(scenario, ScriptedLlm())

        for (heard in listOf(null, "", "  ", "없어요", "질문 없어요", "아니요, 괜찮아요.", "계속해 주세요", "다음으로 넘어가요")) {
            assertEquals(heard.toString(), WindowTurn.Continue, voice.readWindow(heard))
        }
    }

    @Test
    fun `in the question pause a question goes to the suspect it names, else the last one, else nobody yet`() = runTest {
        val voice = StoryVoice(scenario, ScriptedLlm(answer("흡연구역에 있었어요.")))

        assertEquals(WindowTurn.AskWho("블랙박스는 왜 꺼져 있었죠?"), voice.readWindow("블랙박스는 왜 꺼져 있었죠?"))
        assertEquals(WindowTurn.Ask("s_seojin", "아니, 서진 씨 그때 어디 있었어요?"), voice.readWindow("아니, 서진 씨 그때 어디 있었어요?"))
        assertEquals(WindowTurn.Ask("s_minhyuk", "기사님한테 물어볼게요 블랙박스 왜 껐어요"), voice.readWindow("기사님한테 물어볼게요 블랙박스 왜 껐어요"))

        voice.ask("s_minhyuk", "자정 무렵 어디 있었나요?", emptySet())

        assertEquals(WindowTurn.Ask("s_minhyuk", "블랙박스는 왜 꺼져 있었죠?"), voice.readWindow("블랙박스는 왜 꺼져 있었죠?"))
        assertTrue(voice.askWhoLine().startsWith("누구에게 물어볼까요?"))
    }

    @Test
    fun `StoryVoices opens our case by scenario id and gives null for a scenario we have not ported`() {
        val errors = mutableListOf<Pair<String, Exception>>()
        val voices = StoryVoices({ RepoFiles.scenario(it) }, ScriptedLlm(), onLoadError = { id, e -> errors += id to e })

        val voice = voices.forScenario("midnight_rest_area")
        assertNotNull(voice)
        assertSame(voice, voices.forScenario("midnight_rest_area"))

        assertNull(voices.forScenario("another_scenario"))
        assertNull(voices.forScenario("another_scenario"))
        assertNull(voices.forScenario("../content"))

        assertEquals(listOf("another_scenario", "../content"), errors.map { it.first })
        assertTrue(errors[0].second is FileNotFoundException)
        assertTrue(errors[1].second is IllegalArgumentException)
    }

    private companion object {
        const val CASE_DIR = "cases/midnight_rest_area"
    }
}
