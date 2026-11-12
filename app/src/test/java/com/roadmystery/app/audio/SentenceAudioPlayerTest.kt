package com.roadmystery.app.audio

import com.roadmystery.app.story.AppMode
import com.roadmystery.app.story.DefaultStoryEngine
import com.roadmystery.app.story.Scenario
import com.roadmystery.app.story.ScenarioParser
import com.roadmystery.app.story.ScenarioRepository
import com.roadmystery.app.story.Scene
import com.roadmystery.app.vehicle.FakeVehicleState
import com.roadmystery.app.vehicle.HazardReason
import com.roadmystery.app.vehicle.VehicleEvent
import com.roadmystery.app.voice.resolveDrivingQuestionSuspect
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SentenceAudioPlayerTest {
    @Test
    fun `실제 문장 플레이어와 F10 엔진이 중단 안내 재개 반복 보류까지 연결된다`() = runTest {
        val output = ControlledSpeech()
        val player = SentenceAudioPlayer(backgroundScope, output)
        val original = ScenarioParser.parse(File("src/main/assets/scenarios/midnight_rest_area.json").readText())
        val scenario = original.copy(
            chapters = original.chapters.map { chapter ->
                chapter.copy(scenes = chapter.scenes.map { it.copy(script = listOf("${it.id} 첫 문장", "${it.id} 둘째 문장")) })
            }
        )
        val engine = DefaultStoryEngine(ScenarioRepository { scenario }, player, scope = backgroundScope, clock = { testScheduler.currentTime })
        engine.bind(FakeVehicleState(), backgroundScope)
        runCurrent()
        engine.start(scenario.id)
        engine.onVehicleEvent(VehicleEvent.Driving)
        runCurrent()
        repeat(3) {
            output.finish()
            runCurrent()
        }
        assertEquals("s01_scene 둘째 문장", output.calls.last().first)
        engine.onVehicleEvent(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
        runCurrent()
        assertEquals(AppMode.PAUSED, engine.mode.value)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals("이어서 들려드릴게요.", output.calls.last().first)
        output.finish()
        runCurrent()
        assertEquals("s01_scene 둘째 문장", output.calls.last().first)
        // 재개 후에도 최근 60초의 위험 이력은 남아 있어야 한다.
        advanceTimeBy(10_000)
        engine.onVehicleEvent(VehicleEvent.Hazard(HazardReason.ADAS_WARNING))
        runCurrent()
        val count = output.calls.size
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(count, output.calls.size)
        assertEquals(AppMode.PAUSED, engine.mode.value)
        engine.onVehicleEvent(VehicleEvent.Stopped)
        runCurrent()
        assertEquals("이어서 들려드릴게요.", output.calls.last().first)
    }

    @Test
    fun `driving question after the spoken prompt is answered before the next scene`() = runTest {
        val output = ControlledSpeech()
        val player = SentenceAudioPlayer(backgroundScope, output)
        val scenario = shortDemoScenario()
        val engine = DefaultStoryEngine(ScenarioRepository { scenario }, player, scope = backgroundScope)
        val suspect = scenario.suspects.first()
        val question = "${suspect.name} 씨, 어디에 있었어요?"
        var listened = false
        player.onQuestionWindow = {
            listened = true
            val target = resolveDrivingQuestionSuspect(question, scenario.suspects, null)
            checkNotNull(target)
            engine.askBetweenScenes(target, question)
            true
        }
        engine.bind(FakeVehicleState(), backgroundScope)
        runCurrent()
        engine.start(scenario.id)
        engine.onVehicleEvent(VehicleEvent.Driving)
        runCurrent()
        repeat(2) {
            output.finish()
            runCurrent()
        }
        assertEquals(SentenceAudioPlayer.QUESTION, output.calls.last().first)
        assertFalse(listened)
        output.finish()
        runCurrent()

        assertTrue(listened)
        assertEquals(suspect.id, engine.testimonies.value.single().suspectId)
        assertTrue(engine.nowPlaying.value?.sceneId.orEmpty().startsWith("reply_"))
        engine.onVehicleEvent(VehicleEvent.Driving)
        assertTrue(engine.nowPlaying.value?.sceneId.orEmpty().startsWith("reply_"))
        repeat(6) {
            if (engine.nowPlaying.value?.sceneId.orEmpty().startsWith("reply_")) {
                output.finish()
                runCurrent()
            }
        }
        assertEquals("s02_driver", engine.nowPlaying.value?.sceneId)
    }

    @Test
    fun `hazard cancels the active question window`() = runTest {
        val output = ControlledSpeech()
        val player = SentenceAudioPlayer(backgroundScope, output)
        val scenario = shortDemoScenario()
        val engine = DefaultStoryEngine(ScenarioRepository { scenario }, player, scope = backgroundScope)
        val transcript = CompletableDeferred<String>()
        var cancelled = false
        player.onQuestionWindow = {
            try {
                transcript.await()
                true
            } finally {
                cancelled = true
            }
        }
        engine.bind(FakeVehicleState(), backgroundScope)
        runCurrent()
        engine.start(scenario.id)
        engine.onVehicleEvent(VehicleEvent.Driving)
        runCurrent()
        repeat(3) {
            output.finish()
            runCurrent()
        }
        assertEquals(PlaybackPhase.WAITING, player.status.value.phase)
        engine.onVehicleEvent(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
        runCurrent()
        assertTrue(cancelled)
        assertEquals(AppMode.PAUSED, engine.mode.value)
        assertTrue(engine.testimonies.value.isEmpty())
    }

    private fun shortDemoScenario(): Scenario {
        val scenario = ScenarioParser.parse(File("src/main/assets/scenarios/midnight_rest_area.json").readText())
        val chapters = scenario.chapters.map { chapter ->
            val scenes = chapter.scenes.map { it.copy(script = listOf(it.id), sentenceVoices = emptyList()) }
            chapter.copy(scenes = scenes)
        }
        return scenario.copy(chapters = chapters)
    }

    private val scene = Scene("scene", "장면", durationSec = 10, script = listOf("첫 문장", "둘째 문장"), sentenceVoices = listOf("narrator", "female_calm"))

    @Test
    fun `문장 완료 전에는 다음 문장을 보내지 않고 화자를 바꾼다`() = runTest {
        val output = ControlledSpeech()
        val player = SentenceAudioPlayer(backgroundScope, output)
        player.play(scene)
        runCurrent()
        assertEquals(listOf("첫 문장" to "sanghyeok"), output.calls)
        advanceTimeBy(5_000)
        assertEquals(1, output.calls.size)
        output.finish()
        runCurrent()
        assertEquals("둘째 문장" to "seoyoon", output.calls.last())
    }

    @Test
    fun `중단 후 늦은 완료가 와도 진행하지 않고 같은 문장에서 다시 시작한다`() = runTest {
        val output = ControlledSpeech()
        val player = SentenceAudioPlayer(backgroundScope, output)
        player.play(scene)
        runCurrent()
        val old = output.active
        val index = player.pause()
        old.complete(Unit)
        runCurrent()
        assertEquals(1, output.calls.size)
        player.play(scene, index)
        runCurrent()
        assertEquals(listOf("첫 문장", "첫 문장"), output.calls.map { it.first })
        assertTrue(output.stops > 0)
    }

    @Test
    fun `질문 안내와 대기 시간이 끝나야 장면 종료를 발행한다`() = runTest {
        val output = ControlledSpeech()
        val player = SentenceAudioPlayer(backgroundScope, output)
        val events = mutableListOf<AudioEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.events.collect { events += it } }
        player.play(scene)
        runCurrent()
        output.finish()
        runCurrent()
        output.finish()
        runCurrent()
        assertEquals(SentenceAudioPlayer.QUESTION, output.calls.last().first)
        output.finish()
        runCurrent()
        advanceTimeBy(2_999)
        assertFalse(events.any { it is AudioEvent.SceneFinished })
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, events.count { it is AudioEvent.SceneFinished })
    }

    @Test
    fun `질문 대기 중 위험 중단이면 타이머가 완료되지 않는다`() = runTest {
        val output = ControlledSpeech()
        val player = SentenceAudioPlayer(backgroundScope, output)
        val events = mutableListOf<AudioEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.events.collect { events += it } }
        player.play(scene, scene.script.size)
        runCurrent()
        output.finish()
        runCurrent()
        assertEquals(scene.script.size, player.pause())
        advanceTimeBy(10_000)
        assertFalse(events.any { it is AudioEvent.SceneFinished })
    }

    @Test
    fun `재생 실패는 완료로 넘기지 않고 재시도하면 같은 문장을 읽는다`() = runTest {
        val output = ControlledSpeech()
        val player = SentenceAudioPlayer(backgroundScope, output)
        val events = mutableListOf<AudioEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.events.collect { events += it } }
        player.play(scene)
        runCurrent()
        output.active.completeExceptionally(IllegalStateException("TTS 실패"))
        runCurrent()
        assertEquals(PlaybackPhase.ERROR, player.status.value.phase)
        assertFalse(events.any { it is AudioEvent.SceneFinished })
        player.retry()
        runCurrent()
        assertEquals("첫 문장", output.calls.last().first)
    }

    @Test
    fun `포커스 복구는 같은 문장을 재개하지만 위험 중단을 덮어쓰지 않는다`() = runTest {
        val output = ControlledSpeech()
        val player = SentenceAudioPlayer(backgroundScope, output)
        player.play(scene)
        runCurrent()
        player.onFocusLost(transient = true)
        player.onFocusGained()
        runCurrent()
        assertEquals(2, output.calls.size)
        player.onFocusLost(transient = true)
        player.pause()
        player.onFocusGained()
        runCurrent()
        assertEquals(2, output.calls.size)
    }

    @Test
    fun `브리핑과 답변은 질문 대기 구간을 추가하지 않는다`() = runTest {
        val output = ControlledSpeech()
        val player = SentenceAudioPlayer(backgroundScope, output)
        player.play(scene.copy(script = listOf("안내"), sentenceVoices = emptyList(), trigger = "reply"))
        runCurrent()
        output.finish()
        runCurrent()
        assertEquals(1, output.calls.size)
        assertEquals(PlaybackPhase.IDLE, player.status.value.phase)
    }
}

private class ControlledSpeech : SpeechOutput {
    val calls = mutableListOf<Pair<String, String>>()
    var active = CompletableDeferred<Unit>()
    var stops = 0
    override suspend fun speak(text: String, speaker: String) {
        calls += text to speaker
        active = CompletableDeferred()
        active.await()
    }
    fun finish() {
        active.complete(Unit)
    }
    override fun stop() {
        stops++
    }
    override fun release() = Unit
}
