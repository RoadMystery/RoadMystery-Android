package com.roadmystery.app.voice

import com.roadmystery.app.audio.AudioEvent
import com.roadmystery.app.audio.AudioPlayer
import com.roadmystery.app.story.AppMode
import com.roadmystery.app.story.DefaultStoryEngine
import com.roadmystery.app.story.Scene
import com.roadmystery.app.story.ScenarioParser
import com.roadmystery.app.story.ScenarioRepository
import com.roadmystery.app.vehicle.VehicleEvent
import dev.voiceagent.llm.LlmClient
import dev.voiceagent.llm.LlmEvent
import dev.voiceagent.llm.LlmRequest
import dev.voiceagent.llm.LlmTask
import dev.voiceagent.story.StoryVoices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import dev.voiceagent.content.Scenario as CaseScenario

/**
 * LlmVoiceAgent를 이 앱의 DefaultStoryEngine에 끼워 데모 시나리오로 심문과 수첩 요약을 해 본다. LLM은 가짜이고,
 * 기본값인 대사 고르기 방식대로 대사 id로 답한다. 앱은 그 id의 원고 대사만 읽고, 요약은 LLM 없이 수첩을 읽는다.
 * 사건 원고를 VoiceAgent 저장소에서 읽으므로 `-Dvoiceagent.repo=<저장소 경로>`(content/와 tools/voice-eval/prompts/가
 * 있는 곳)를 줄 때만 돌고, 없으면 건너뛴다. JVM 단위 테스트의 Android org.json은 빈 껍데기라
 * `testImplementation("org.json:json:20240303")`이 있어야 사건을 연다(기기에서는 필요 없음). 엔진의 심문은 backgroundScope에서 돌므로 팀 StoryEngineTest처럼
 * UnconfinedTestDispatcher로 바로 끝까지 돌린다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlmVoiceAgentTest {
    private val repo = System.getProperty("voiceagent.repo")?.let(::File)
    private val scenario = ScenarioParser.parse(File("src/main/assets/scenarios/midnight_rest_area.json").readText())
    private val audio = RecordingAudioPlayer()

    /** 사건을 열지 못한 까닭. 조용히 대본으로 넘어가지 않았는지 본다. */
    private val loadErrors = mutableListOf<String>()

    private fun voices(llm: LlmClient): StoryVoices {
        val dirs = listOf(File(repo, "content"), File(repo, "tools/voice-eval/prompts"))
        val read = { path: String -> dirs.map { File(it, path) }.first { it.isFile }.readText() }
        return StoryVoices({ dir -> CaseScenario.load(read, dir) }, llm, onLoadError = { id, e -> loadErrors += "$id: $e" })
    }

    private fun finish(sceneId: String) {
        if (audio.playing == sceneId) audio.playing = null
        engine.onAudioEvent(AudioEvent.SceneFinished(sceneId))
    }

    private lateinit var engine: DefaultStoryEngine

    @Test
    fun `정차 중 심문은 고른 용의자의 원고 대사를 증언으로 남기고 대답 장면으로 읽는다`() =
        runTest(UnconfinedTestDispatcher()) {
            assumeTrue("-Dvoiceagent.repo가 없어 건너뜀", repo?.isDirectory == true)
            // 대사 id 뒤에 덧붙인 말은 읽지 않는다.
            val llm = FakeLlm("k-seojin-receipt 사실 가방은 제가 로커에 넣었어요.")
            engine = DefaultStoryEngine(ScenarioRepository { scenario }, audio, voice = LlmVoiceAgent(voices(llm)), scope = backgroundScope)
            val receipt = "대표님은 졸음을 쫓으려고 늘 진하게 두 잔을 드십니다. 제 법인카드로 사는 건 매일 있는 일이에요."

            engine.start(scenario.id)
            finish("briefing")
            engine.onVehicleEvent(VehicleEvent.Driving)
            finish("s01_scene")
            engine.onVehicleEvent(VehicleEvent.Stopped)
            assertEquals(AppMode.STOPPED, engine.mode.value)

            engine.ask("s_seojin", "오민혁 씨한테도 물었는데, 커피는 왜 두 잔 샀나요?")

            assertEquals("사건을 열지 못함(JVM 테스트는 org.json:json이 필요)", emptyList<String>(), loadErrors)
            val testimony = engine.testimonies.value.single()
            assertEquals("s_seojin", testimony.suspectId)
            assertEquals(receipt, testimony.answer)
            assertTrue("대답 장면을 읽는다", audio.playing!!.startsWith("reply_"))
            // 찾은 단서(영수증)와 그 단서에 하는 대사가 고를 수 있는 대사로 들어갔다.
            val ask = llm.requests.single()
            assertEquals(LlmTask.INTERROGATION, ask.task)
            assertTrue(ask.system.contains("박서진(수행 비서)"))
            assertTrue(ask.system.contains("찢어진 영수증"))
            assertTrue(ask.system.contains("- k-seojin-receipt (찢어진 영수증 이야기에): $receipt"))

            finish(audio.playing!!)
            engine.requestSummary()

            assertEquals(
                "지금까지 찾은 단서는 찢어진 영수증이에요. 박서진 씨가 마지막으로 한 말은 이래요. $receipt 아직 찾지 못한 단서가 4개 남아 있어요.",
                engine.interrogation.value?.answer,
            )
            // 요약은 LLM을 부르지 않는다.
            assertEquals(1, llm.requests.size)
        }

    @Test
    fun `우리 원고가 없는 시나리오는 대본으로 답한다`() =
        runTest(UnconfinedTestDispatcher()) {
            assumeTrue("-Dvoiceagent.repo가 없어 건너뜀", repo?.isDirectory == true)
            val other = scenario.copy(id = "not_ported_yet")
            val llm = FakeLlm()
            engine = DefaultStoryEngine(ScenarioRepository { other }, audio, voice = LlmVoiceAgent(voices(llm)), scope = backgroundScope)

            engine.start(other.id)
            finish("briefing")
            engine.onVehicleEvent(VehicleEvent.Stopped)
            engine.ask("s_seojin", "커피는 왜 두 잔 샀나요?")

            assertEquals(scenario.suspect("s_seojin")!!.interrogation.first().text, engine.testimonies.value.single().answer)
            assertTrue(llm.requests.isEmpty())
            assertEquals(listOf("not_ported_yet"), loadErrors.map { it.substringBefore(':') })
        }
}

/** 요청마다 다음 답을 한 번에 흘려 보내는 가짜 LLM 프록시. */
private class FakeLlm(
    vararg answers: String,
) : LlmClient {
    private val pending = ArrayDeque(answers.toList())
    val requests = mutableListOf<LlmRequest>()

    override fun stream(request: LlmRequest): Flow<LlmEvent> =
        flow {
            requests += request
            emit(LlmEvent.Delta(pending.removeFirst()))
            emit(LlmEvent.Done("end_turn", "fake", 1, 1))
        }
}

private class RecordingAudioPlayer : AudioPlayer {
    override val events = MutableSharedFlow<AudioEvent>()
    var playing: String? = null

    override fun play(
        scene: Scene,
        fromSentence: Int,
    ) {
        playing = scene.id
    }

    override fun pause(): Int {
        playing = null
        return 0
    }

    override fun stop() {
        playing = null
    }
}
