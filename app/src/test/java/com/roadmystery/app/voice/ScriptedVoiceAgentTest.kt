package com.roadmystery.app.voice

import com.roadmystery.app.story.Progress
import com.roadmystery.app.story.ScenarioParser
import com.roadmystery.app.story.Testimony
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScriptedVoiceAgentTest {
    private val scenario = ScenarioParser.parse(File("src/main/assets/scenarios/midnight_rest_area.json").readText())
    private val seojin = scenario.suspect("s_seojin")!!
    private val agent = ScriptedVoiceAgent()

    private fun context(found: Set<String> = emptySet()) = CaseContext(scenario, seojin, found, emptySet())

    @Test
    fun `질문 키워드에 맞는 답을 고르고 공개 가능한 단서를 돌려준다`() =
        runTest {
            val reply = agent.ask("영수증에 커피가 두 잔이던데요", context())
            assertEquals("q_coffee", reply.answerId)
            assertEquals(listOf("clue_receipt"), reply.reveals)
        }

    @Test
    fun `이미 찾은 단서는 다시 공개하지 않는다`() =
        runTest {
            val reply = agent.ask("커피", context(found = setOf("clue_receipt")))
            assertTrue(reply.reveals.isEmpty())
        }

    @Test
    fun `맞는 답이 없으면 대체 대답을 돌아가며 쓴다`() =
        runTest {
            val first = agent.ask("오늘 날씨 어때요", context())
            val second = agent.ask("점심 뭐 먹었어요", context())
            assertEquals(seojin.fallback[0], first.text)
            assertEquals(seojin.fallback[1], second.text)
        }

    @Test
    fun `어떤 답변도 범인이 자신이라고 밝히지 않는다`() {
        scenario.suspects.flatMap { s -> s.interrogation.map { it.text } + s.fallback }.forEach { text ->
            assertFalse(text, "범인" in text || "제가 넣었" in text || "제가 훔쳤" in text)
        }
    }

    @Test
    fun `수첩 요약에 찾은 단서와 증언이 들어간다`() =
        runTest {
            val progress =
                Progress(
                    scenarioId = scenario.id,
                    chapterId = "ch1",
                    cluesFound = listOf("clue_receipt"),
                    testimonies = listOf(Testimony("s_minhyuk", "블랙박스", "내가 뽑았어요.")),
                )
            val summary = agent.summarize(scenario, progress)
            assertTrue(summary, "찢어진 영수증" in summary)
            assertTrue(summary, "오민혁" in summary)
            assertTrue(summary, "4개" in summary)
        }
}
