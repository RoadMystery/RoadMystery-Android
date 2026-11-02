package com.roadmystery.app.story

import com.roadmystery.app.audio.AudioEvent
import com.roadmystery.app.audio.AudioPlayer
import com.roadmystery.app.vehicle.FakeVehicleState
import com.roadmystery.app.vehicle.HazardReason
import com.roadmystery.app.vehicle.VehicleEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryEngineTest {
    private val audio = RecordingAudioPlayer()
    private val engine = DefaultStoryEngine(ScenarioRepository { testScenario }, audio)

    /** 실제 플레이어처럼 재생이 끝난 뒤 종료 이벤트를 보낸다. */
    private fun finish(sceneId: String) {
        if (audio.playing == sceneId) audio.playing = null
        engine.onAudioEvent(AudioEvent.SceneFinished(sceneId))
    }

    private fun send(vararg events: VehicleEvent) = events.forEach(engine::onVehicleEvent)

    @Test
    fun `시작하면 브리핑을 재생하고, 출발 후 브리핑이 끝나면 본편으로 넘어간다`() {
        engine.start("test")
        assertEquals(AppMode.BRIEFING, engine.mode.value)
        assertEquals("briefing", audio.playing)

        finish("briefing")
        assertNull("출발 전에는 본편을 시작하지 않는다", audio.playing)

        send(VehicleEvent.Driving)
        assertEquals(AppMode.DRIVING, engine.mode.value)
        assertEquals("m1", audio.playing)
    }

    @Test
    fun `장면이 끝나면 단서가 열리고, 정차 때 강조된 뒤 출발하면 강조가 풀린다`() {
        startDriving()
        finish("m1")
        assertEquals(listOf("c1"), engine.clues.value.map { it.id })
        assertEquals(listOf("c1"), engine.pendingClues.value.map { it.id })

        send(VehicleEvent.Stopped)
        assertEquals(AppMode.STOPPED, engine.mode.value)
        assertEquals("정차 중에도 낭독은 이어진다", "opt1", audio.playing)

        send(VehicleEvent.Driving)
        assertTrue(engine.pendingClues.value.isEmpty())
        assertEquals(1, engine.clues.value.size)
    }

    @Test
    fun `급제동이면 즉시 멈추고, 다시 출발하면 멈춘 문장부터 재개한다`() {
        startDriving()
        engine.onAudioEvent(AudioEvent.SentenceStarted("m1", 1))
        audio.pauseIndex = 1

        send(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
        assertEquals(AppMode.PAUSED, engine.mode.value)
        assertNull(audio.playing)

        send(VehicleEvent.Stopped)
        assertNull("정차해도 위험 정지된 낭독은 그대로 멈춰 있다", audio.playing)

        send(VehicleEvent.Driving)
        assertEquals("m1" to 1, audio.history.last())
    }

    @Test
    fun `터널에 들어가면 현재 장면 다음에 터널 장면이 끼어든다`() {
        startDriving()
        send(VehicleEvent.Place("tunnel"))
        assertEquals("m1", audio.playing)

        finish("m1")
        assertEquals("tunnel", audio.playing)

        send(VehicleEvent.Place("tunnel"))
        finish("tunnel")
        assertEquals("터널 장면은 한 번만 나온다", "opt1", audio.playing)
    }

    @Test
    fun `도착 시간이 부족하면 선택 장면을 뺀다`() {
        startDriving()
        send(VehicleEvent.EtaChanged(remainingSec = 30))
        finish("m1")
        assertEquals("m2", audio.playing)
    }

    @Test
    fun `도착하면 못 들은 필수 단서를 지급하고, 범인을 맞히면 정답 엔딩을 재생한다`() {
        startDriving()
        send(VehicleEvent.Arrived)
        assertEquals(AppMode.ENDING, engine.mode.value)
        assertNull(audio.playing)
        assertEquals(listOf("c1", "c2"), engine.clues.value.map { it.id })

        engine.choose(StoryEngine.DECISION_ACCUSE, "a")
        assertEquals(true, engine.outcome.value?.correct)
        assertEquals("ending_correct", audio.playing)

        engine.choose(StoryEngine.DECISION_ACCUSE, "b")
        assertEquals("지목은 한 번만 받는다", "a", engine.outcome.value?.accusedId)
    }

    @Test
    fun `범인을 틀리면 오답 엔딩을 재생하고, 도착 전에는 지목을 받지 않는다`() {
        startDriving()
        engine.choose(StoryEngine.DECISION_ACCUSE, "b")
        assertNull(engine.outcome.value)

        send(VehicleEvent.Arrived)
        engine.choose(StoryEngine.DECISION_ACCUSE, "b")
        assertEquals(false, engine.outcome.value?.correct)
        assertEquals("ending_wrong", audio.playing)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `FakeVehicleState로 넣은 이벤트 순서대로 모드가 바뀐다`() =
        runTest(UnconfinedTestDispatcher()) {
            val vehicle = FakeVehicleState()
            engine.bind(vehicle, backgroundScope)
            engine.start("test")

            val modes = mutableListOf<AppMode>()
            listOf(
                VehicleEvent.Driving,
                VehicleEvent.Stopped,
                VehicleEvent.Driving,
                VehicleEvent.Hazard(HazardReason.ADAS_WARNING),
                VehicleEvent.Driving,
                VehicleEvent.Arrived,
                VehicleEvent.Driving,
            ).forEach {
                vehicle.send(it)
                modes += engine.mode.value
            }

            assertEquals(
                listOf(
                    AppMode.DRIVING,
                    AppMode.STOPPED,
                    AppMode.DRIVING,
                    AppMode.PAUSED,
                    AppMode.DRIVING,
                    AppMode.ENDING,
                    AppMode.ENDING,
                ),
                modes,
            )
        }

    private fun startDriving() {
        engine.start("test")
        finish("briefing")
        send(VehicleEvent.Driving)
    }
}

private class RecordingAudioPlayer : AudioPlayer {
    override val events = MutableSharedFlow<AudioEvent>()
    val history = mutableListOf<Pair<String, Int>>()
    var playing: String? = null
    var pauseIndex = 0

    override fun play(
        scene: Scene,
        fromSentence: Int,
    ) {
        history += scene.id to fromSentence
        playing = scene.id
    }

    override fun pause(): Int {
        playing = null
        return pauseIndex
    }

    override fun stop() {
        playing = null
    }
}

private fun scene(
    id: String,
    required: Boolean = true,
    durationSec: Int = 40,
    unlocks: List<String> = emptyList(),
    trigger: String? = null,
) = Scene(
    id = id,
    title = id,
    required = required,
    durationSec = durationSec,
    script = listOf("$id 첫 문장", "$id 둘째 문장", "$id 셋째 문장"),
    unlocks = unlocks,
    trigger = trigger,
)

private val testScenario =
    Scenario(
        id = "test",
        title = "테스트 사건",
        culprit = "a",
        suspects =
            listOf(
                Suspect("a", "가", "비서", "v1", Persona("", "", "")),
                Suspect("b", "나", "기사", "v2", Persona("", "", "")),
            ),
        clues = listOf(Clue("c1", "단서1", ""), Clue("c2", "단서2", "")),
        chapters =
            listOf(
                Chapter(
                    id = "ch1",
                    title = "1장",
                    scenes =
                        listOf(
                            scene("briefing", trigger = SceneTrigger.BRIEFING),
                            scene("m1", unlocks = listOf("c1")),
                            scene("opt1", required = false, durationSec = 20),
                            scene("m2", unlocks = listOf("c2")),
                            scene("tunnel", required = false, trigger = "tunnel"),
                            scene("ending_correct", trigger = SceneTrigger.ENDING_CORRECT),
                            scene("ending_wrong", trigger = SceneTrigger.ENDING_WRONG),
                        ),
                ),
            ),
    )
