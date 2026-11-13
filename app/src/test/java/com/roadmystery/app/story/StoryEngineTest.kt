package com.roadmystery.app.story

import com.roadmystery.app.audio.AudioEvent
import com.roadmystery.app.audio.AudioPlayer
import com.roadmystery.app.director.Effect
import com.roadmystery.app.director.FakeDirector
import com.roadmystery.app.vehicle.FakeVehicleState
import com.roadmystery.app.vehicle.HazardReason
import com.roadmystery.app.vehicle.VehicleEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryEngineTest {
    private val audio = RecordingAudioPlayer()
    private var now = 0L
    private val engine = DefaultStoryEngine(ScenarioRepository { testScenario }, audio, clock = { now })

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
        assertNull("위험 직후의 출발 신호로는 재개하지 않는다", audio.playing)
        assertEquals(AppMode.PAUSED, engine.mode.value)

        now += 5_000
        send(VehicleEvent.Driving)
        assertEquals(AppMode.DRIVING, engine.mode.value)
        assertEquals("재개 전에 안내를 먼저 들려준다", "reply_0", audio.playing)

        finish("reply_0")
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
    fun `목적지가 없으면 10분 챕터로 보고 넘치는 선택 장면을 뺀다`() {
        val long =
            testScenario.copy(
                chapters =
                    listOf(
                        Chapter(
                            "ch1",
                            "1장",
                            listOf(
                                scene("briefing", trigger = SceneTrigger.BRIEFING),
                                scene("m1", durationSec = 300, unlocks = listOf("c1")),
                                scene("opt1", required = false, durationSec = 60),
                                scene("m2", durationSec = 300, unlocks = listOf("c2")),
                                scene("ending_correct", trigger = SceneTrigger.ENDING_CORRECT),
                                scene("ending_wrong", trigger = SceneTrigger.ENDING_WRONG),
                            ),
                        ),
                    ),
            )
        val engine = DefaultStoryEngine(ScenarioRepository { long }, audio)
        engine.start("test")
        engine.onAudioEvent(AudioEvent.SceneFinished("briefing"))
        engine.onVehicleEvent(VehicleEvent.Driving)
        engine.onAudioEvent(AudioEvent.SceneFinished("m1"))
        assertEquals("필수만으로 10분이 차서 선택 장면은 건너뛴다", "m2", audio.playing)
    }

    @Test
    fun `목적지가 없어도 10분이 남으면 선택 장면을 넣는다`() {
        startDriving()
        finish("m1")
        assertEquals("opt1", audio.playing)
    }

    @Test
    fun `끼어든 터널 장면도 남은 시간을 쓰므로 시간이 부족하면 뺀다`() {
        startDriving()
        send(VehicleEvent.EtaChanged(remainingSec = 100))
        send(VehicleEvent.Place("tunnel"))
        finish("m1")
        assertEquals("터널 장면은 시간이 모자라 건너뛰고 남는 시간에 맞는 장면을 튼다", "opt1", audio.playing)
        finish("opt1")
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
                    AppMode.PAUSED,
                    AppMode.ENDING,
                    AppMode.ENDING,
                ),
                modes,
            )
        }

    @Test
    fun `위험으로 멈춘 채 주차하고 도착해도 범인을 지목할 수 있다`() {
        startDriving()
        send(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
        now = 1_500
        send(VehicleEvent.Stopped)
        now = 10_000
        // F-01은 도착을 주차할 때까지 붙잡았다가 주차, 도착 순서로 보낸다.
        send(VehicleEvent.Parked, VehicleEvent.Arrived)
        assertEquals(AppMode.ENDING, engine.mode.value)
        assertTrue(engine.canInteract.value)
    }

    @Test
    fun `도착 후 다시 움직이면 지목을 받지 않는다`() {
        startDriving()
        send(VehicleEvent.Arrived)
        assertTrue(engine.canInteract.value)

        send(VehicleEvent.Driving)
        assertFalse(engine.canInteract.value)
        assertEquals(AppMode.ENDING, engine.mode.value)
        engine.choose(StoryEngine.DECISION_ACCUSE, "a")
        assertNull(engine.outcome.value)
    }

    @Test
    fun `장면이 시작되면 연출을 실행한다`() {
        val director = FakeDirector()
        val engine = DefaultStoryEngine(ScenarioRepository { testScenario }, audio, director = director)
        engine.start("test")
        engine.onAudioEvent(AudioEvent.SceneFinished("briefing"))
        engine.onVehicleEvent(VehicleEvent.Driving)
        assertEquals(Effect.TENSION, director.lastEffect.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `정차 중 심문하면 낭독을 멈추고 대답한 뒤 멈춘 문장부터 이어 읽는다`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = DefaultStoryEngine(ScenarioRepository { testScenario }, audio, scope = backgroundScope)
            engine.start("test")
            engine.onAudioEvent(AudioEvent.SceneFinished("briefing"))
            engine.onVehicleEvent(VehicleEvent.Driving)
            engine.onAudioEvent(AudioEvent.SentenceStarted("m1", 2))
            audio.pauseIndex = 2
            engine.onVehicleEvent(VehicleEvent.Stopped)

            engine.ask("a", "커피는 왜 샀나요?")
            val reply = audio.playing!!
            assertTrue(reply.startsWith("reply_"))
            assertEquals("대표님이 드실 커피였어요.", engine.interrogation.value?.answer)
            assertEquals(listOf("c2"), engine.clues.value.map { it.id })
            assertEquals(1, engine.testimonies.value.size)

            audio.playing = null
            engine.onAudioEvent(AudioEvent.SceneFinished(reply))
            assertEquals("m1" to 2, audio.history.last())
        }

    @Test
    fun `음성으로 묻기로 듣는 동안 낭독을 멈추고, 말이 없이 끝나면 멈춘 문장부터 잇는다`() {
        startDriving()
        send(VehicleEvent.Stopped)
        audio.pauseIndex = 1

        engine.beginListening()
        assertNull(audio.playing)
        assertNull(engine.nowPlaying.value)

        engine.endListening()
        assertEquals("m1" to 1, audio.history.last())
        // 끝난 듣기를 다시 끝내도 낭독을 다시 시작하지 않는다.
        val plays = audio.history.size
        engine.endListening()
        assertEquals(plays, audio.history.size)
    }

    @Test
    fun `듣는 중에 출발하면 멈춘 문장부터 낭독으로 돌아가고, 주행 중에는 듣기를 시작하지 않는다`() {
        startDriving()
        send(VehicleEvent.Stopped)
        audio.pauseIndex = 1
        engine.beginListening()

        send(VehicleEvent.Driving)
        assertEquals(AppMode.DRIVING, engine.mode.value)
        assertEquals("m1" to 1, audio.history.last())
        val plays = audio.history.size
        engine.endListening()
        assertEquals(plays, audio.history.size)

        engine.beginListening()
        assertEquals("m1", audio.playing)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `듣고 나서 질문하면 대답을 읽은 뒤 멈춘 문장부터 이어 읽는다`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = DefaultStoryEngine(ScenarioRepository { testScenario }, audio, scope = backgroundScope)
            engine.start("test")
            engine.onAudioEvent(AudioEvent.SceneFinished("briefing"))
            engine.onVehicleEvent(VehicleEvent.Driving)
            engine.onAudioEvent(AudioEvent.SentenceStarted("m1", 2))
            audio.pauseIndex = 2
            engine.onVehicleEvent(VehicleEvent.Stopped)

            engine.beginListening()
            assertNull(audio.playing)
            engine.ask("a", "커피는 왜 샀나요?")
            engine.endListening()
            val reply = audio.playing!!
            assertTrue(reply.startsWith("reply_"))

            audio.playing = null
            engine.onAudioEvent(AudioEvent.SceneFinished(reply))
            assertEquals("m1" to 2, audio.history.last())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `주행 중에는 심문을 받지 않고, 대답 중 출발하면 대답을 끊고 낭독으로 돌아간다`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = DefaultStoryEngine(ScenarioRepository { testScenario }, audio, scope = backgroundScope)
            engine.start("test")
            engine.onAudioEvent(AudioEvent.SceneFinished("briefing"))
            engine.onVehicleEvent(VehicleEvent.Driving)

            engine.ask("a", "커피")
            assertEquals("m1", audio.playing)

            audio.pauseIndex = 1
            engine.onVehicleEvent(VehicleEvent.Stopped)
            engine.ask("b", "아무 질문")
            assertEquals("몰라요.", engine.interrogation.value?.answer)

            engine.onVehicleEvent(VehicleEvent.Driving)
            assertNull(engine.interrogation.value)
            assertEquals("m1" to 1, audio.history.last())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `대답 중 급제동이면 즉시 멈추고, 다시 출발하면 낭독을 재개한다`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = DefaultStoryEngine(ScenarioRepository { testScenario }, audio, scope = backgroundScope, clock = { testScheduler.currentTime })
            engine.start("test")
            engine.onAudioEvent(AudioEvent.SceneFinished("briefing"))
            engine.onVehicleEvent(VehicleEvent.Driving)
            audio.pauseIndex = 1
            engine.onVehicleEvent(VehicleEvent.Stopped)
            engine.ask("a", "커피")

            engine.onVehicleEvent(VehicleEvent.Hazard(HazardReason.USER_STOP))
            assertEquals(AppMode.PAUSED, engine.mode.value)
            assertNull(audio.playing)

            testScheduler.advanceTimeBy(3_001)
            engine.onVehicleEvent(VehicleEvent.Driving)
            assertEquals("reply_1", audio.playing)

            engine.onAudioEvent(AudioEvent.SceneFinished("reply_1"))
            assertEquals("m1" to 1, audio.history.last())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun hazardEngine(scope: kotlinx.coroutines.CoroutineScope, clock: () -> Long): DefaultStoryEngine {
        val engine = DefaultStoryEngine(ScenarioRepository { testScenario }, audio, scope = scope, clock = clock)
        engine.start("test")
        engine.onAudioEvent(AudioEvent.SceneFinished("briefing"))
        engine.onVehicleEvent(VehicleEvent.Driving)
        audio.pauseIndex = 1
        return engine
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `테스트 화면 전환으로 종료하면 예약된 자동 재개도 취소된다`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = hazardEngine(backgroundScope) { testScheduler.currentTime }
            engine.onVehicleEvent(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
            engine.stop()
            testScheduler.advanceTimeBy(5_000)
            assertEquals(AppMode.IDLE, engine.mode.value)
            assertNull(audio.playing)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `주행 중 위험이 지나고 일정 시간 조용하면 안내 후 멈춘 문장부터 자동 재개한다`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = hazardEngine(backgroundScope) { testScheduler.currentTime }
            engine.onVehicleEvent(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
            testScheduler.advanceTimeBy(2_000)
            assertNull("아직 조용한 시간이 모자란다", audio.playing)
            assertEquals(AppMode.PAUSED, engine.mode.value)

            testScheduler.advanceTimeBy(1_100)
            assertEquals(AppMode.DRIVING, engine.mode.value)
            assertEquals("reply_0", audio.playing)

            engine.onAudioEvent(AudioEvent.SceneFinished("reply_0"))
            assertEquals("m1" to 1, audio.history.last())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `급제동 후 정차했다가 출발하면 주행 모드와 낭독이 함께 돌아온다`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = hazardEngine(backgroundScope) { testScheduler.currentTime }
            engine.onVehicleEvent(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
            testScheduler.advanceTimeBy(1_500)
            engine.onVehicleEvent(VehicleEvent.Stopped)
            assertEquals(AppMode.STOPPED, engine.mode.value)

            testScheduler.advanceTimeBy(6_000)
            assertNull(audio.playing)
            engine.onVehicleEvent(VehicleEvent.Driving)
            assertEquals(AppMode.DRIVING, engine.mode.value)
            assertFalse(engine.canInteract.value)
            assertEquals("reply_0", audio.playing)

            engine.onAudioEvent(AudioEvent.SceneFinished("reply_0"))
            assertEquals("m1" to 1, audio.history.last())
            assertEquals(AppMode.DRIVING, engine.mode.value)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `위험이 짧은 시간에 반복되면 정차할 때까지 재개를 보류한다`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = hazardEngine(backgroundScope) { testScheduler.currentTime }
            engine.onVehicleEvent(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
            testScheduler.advanceTimeBy(1_000)
            engine.onVehicleEvent(VehicleEvent.Hazard(HazardReason.ADAS_WARNING))

            testScheduler.advanceTimeBy(30_000)
            assertNull("반복 위험이면 시간이 지나도 재개하지 않는다", audio.playing)
            engine.onVehicleEvent(VehicleEvent.Driving)
            assertNull(audio.playing)
            assertEquals(AppMode.PAUSED, engine.mode.value)

            engine.onVehicleEvent(VehicleEvent.Stopped)
            assertEquals(AppMode.STOPPED, engine.mode.value)
            assertEquals("정차하면 안내 후 재개한다", "reply_0", audio.playing)
            engine.onAudioEvent(AudioEvent.SceneFinished("reply_0"))
            assertEquals("m1" to 1, audio.history.last())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `위험 사이가 충분히 길면 반복으로 보지 않고 각각 자동 재개한다`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = hazardEngine(backgroundScope) { testScheduler.currentTime }
            engine.onVehicleEvent(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
            testScheduler.advanceTimeBy(3_100)
            engine.onAudioEvent(AudioEvent.SceneFinished("reply_0"))

            testScheduler.advanceTimeBy(70_000)
            audio.pauseIndex = 1
            engine.onVehicleEvent(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
            assertNull(audio.playing)
            testScheduler.advanceTimeBy(3_100)
            assertEquals("reply_1", audio.playing)
        }

    @Test
    fun `저장한 진행 상황에서 이어하면 멈춘 장면의 그 문장부터 재생한다`() {
        val saved =
            Progress(
                scenarioId = "test",
                chapterId = "ch1",
                lastSceneId = "m2",
                lastSentenceIndex = 2,
                playedSceneIds = listOf("m1", "opt1"),
                cluesFound = listOf("c1"),
            )
        engine.start("test", resume = saved)
        assertNull("이어하기에서는 브리핑을 다시 틀지 않는다", audio.playing)
        assertEquals(listOf("c1"), engine.clues.value.map { it.id })

        send(VehicleEvent.Driving)
        assertEquals("m2" to 2, audio.history.last())
        assertEquals("m2", engine.snapshot.value?.lastSceneId)
    }

    @Test
    fun `터널 이탈이면 대기 중인 장면은 취소되고 다음 본편으로 진행한다`() {
        startDriving()
        send(VehicleEvent.Place("tunnel"), VehicleEvent.PlaceExited("tunnel"))
        finish("m1")
        assertEquals("opt1", audio.playing)
    }

    @Test
    fun `정차 중 장소 입력과 예약된 시스템 태그는 연출을 삽입하지 않는다`() {
        startDriving()
        send(VehicleEvent.Stopped, VehicleEvent.Place("tunnel"), VehicleEvent.Driving)
        send(VehicleEvent.Place("ending_correct"), VehicleEvent.Place("briefing"))
        finish("m1")
        assertEquals("opt1", audio.playing)
    }

    @Test
    fun `터널 낭독 중 위험은 연출도 중단하고 문장 재개 때 연출을 반복하지 않는다`() {
        val effects = mutableListOf<Effect>()
        var stops = 0
        val fake = FakeDirector()
        val director = object : com.roadmystery.app.director.Director {
            override val lastEffect = fake.lastEffect
            override fun perform(effect: Effect) { effects += effect; fake.perform(effect) }
            override fun stop() { stops++ }
        }
        val scenario = testScenario.copy(chapters = testScenario.chapters.map { chapter ->
            chapter.copy(scenes = chapter.scenes.map { if (it.trigger == "tunnel") it.copy(effect = "tunnel") else it })
        })
        val engine = DefaultStoryEngine(ScenarioRepository { scenario }, audio, director = director, clock = { now })
        engine.start("test")
        engine.onVehicleEvent(VehicleEvent.Driving)
        engine.onAudioEvent(AudioEvent.SceneFinished("briefing"))
        engine.onVehicleEvent(VehicleEvent.Place("tunnel"))
        engine.onAudioEvent(AudioEvent.SceneFinished("m1"))
        assertEquals("tunnel", audio.playing)
        assertEquals(1, effects.count { it == Effect.TUNNEL })
        val before = stops
        engine.onVehicleEvent(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
        assertTrue(stops > before)
        now += 5_000
        engine.onVehicleEvent(VehicleEvent.Driving)
        engine.onAudioEvent(AudioEvent.SceneFinished("reply_0"))
        assertEquals("tunnel", audio.playing)
        assertEquals(1, effects.count { it == Effect.TUNNEL })
    }

    @Test
    fun `재개 후에도 반복 위험 기록을 유지한다`() {
        startDriving()
        send(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
        now = 4_000
        send(VehicleEvent.Driving)
        finish("reply_0")
        send(VehicleEvent.Hazard(HazardReason.ADAS_WARNING))
        now = 10_000
        send(VehicleEvent.Driving)
        assertNull(audio.playing)
        assertEquals(AppMode.PAUSED, engine.mode.value)
        send(VehicleEvent.Stopped)
        assertEquals("reply_1", audio.playing)
    }

    @Test
    fun `지속 경고는 시간 경과나 정차로 해제되지 않는다`() {
        startDriving()
        send(VehicleEvent.HazardState(HazardReason.ADAS_WARNING, true))
        now = 20_000
        send(VehicleEvent.Driving, VehicleEvent.Stopped)
        assertNull(audio.playing)
        assertFalse(engine.canInteract.value)
        engine.onAudioEvent(AudioEvent.SentenceStarted("m1", 2))
        finish("m1")
        assertEquals("m1", engine.nowPlaying.value?.sceneId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `모든 경고 해제 뒤 대기하고 같은 문장으로 돌아간다`() = runTest {
        val player = RecordingAudioPlayer()
        val target = DefaultStoryEngine(ScenarioRepository { testScenario }, player,
            scope = backgroundScope, clock = { testScheduler.currentTime })
        target.start("test")
        target.onVehicleEvent(VehicleEvent.Driving)
        target.onAudioEvent(AudioEvent.SceneFinished("briefing"))
        player.pauseIndex = 1
        target.onVehicleEvent(VehicleEvent.HazardState(HazardReason.ADAS_WARNING, true))
        target.onVehicleEvent(VehicleEvent.HazardState(HazardReason.HARD_BRAKE, true))
        target.onVehicleEvent(VehicleEvent.HazardState(HazardReason.ADAS_WARNING, false))
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()
        assertNull(player.playing)
        target.onVehicleEvent(VehicleEvent.HazardState(HazardReason.HARD_BRAKE, false))
        target.onVehicleEvent(VehicleEvent.Stopped)
        testScheduler.advanceTimeBy(2_999)
        testScheduler.runCurrent()
        assertNull(player.playing)
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals("reply_0", player.playing)
        target.onAudioEvent(AudioEvent.SceneFinished("reply_0"))
        assertEquals("m1" to 1, player.history.last())
    }

    @Test
    fun `같은 지속 경고의 반복 수신은 새 위험으로 세지 않는다`() {
        startDriving()
        repeat(4) { send(VehicleEvent.HazardState(HazardReason.ADAS_WARNING, true)) }
        send(VehicleEvent.HazardState(HazardReason.ADAS_WARNING, false))
        now = 4_000
        send(VehicleEvent.Driving)
        assertEquals("reply_0", audio.playing)
    }

    private fun startDriving() {
        engine.start("test")
        finish("briefing")
        send(VehicleEvent.Driving)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.questionWindowEngine(): DefaultStoryEngine {
        val engine = DefaultStoryEngine(ScenarioRepository { testScenario }, audio, scope = backgroundScope)
        engine.start("test")
        engine.onAudioEvent(AudioEvent.SceneFinished("briefing"))
        engine.onVehicleEvent(VehicleEvent.Driving)
        return engine
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `주행 중 질문 구간에서 질문하면 대답을 읽고 다음 장면으로 간다`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = questionWindowEngine()
            engine.onAudioEvent(AudioEvent.SentenceStarted("m1", 2))

            engine.askBetweenScenes("a", "커피는 왜 샀나요?")

            val reply = audio.playing!!
            assertTrue(reply.startsWith("reply_"))
            assertEquals(AppMode.DRIVING, engine.mode.value)
            assertEquals("대표님이 드실 커피였어요.", engine.interrogation.value?.answer)
            assertEquals(listOf("c1", "c2"), engine.clues.value.map { it.id })
            assertEquals(1, engine.testimonies.value.size)

            // 질문 구간 시간이 다 되어 오는 원래 장면의 종료는 대답을 끊지 못한다.
            engine.onAudioEvent(AudioEvent.SceneFinished("m1"))
            assertEquals(reply, audio.playing)

            audio.playing = null
            engine.onAudioEvent(AudioEvent.SceneFinished(reply))
            assertEquals("opt1" to 0, audio.history.last())
            assertEquals(AppMode.DRIVING, engine.mode.value)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `장면을 아직 읽는 중이거나 정차 중이면 질문 구간 질문을 받지 않는다`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = questionWindowEngine()
            engine.onAudioEvent(AudioEvent.SentenceStarted("m1", 1))
            engine.askBetweenScenes("a", "커피")
            assertEquals("m1", audio.playing)
            assertNull(engine.interrogation.value)

            engine.onAudioEvent(AudioEvent.SentenceStarted("m1", 2))
            engine.onVehicleEvent(VehicleEvent.Stopped)
            engine.askBetweenScenes("a", "커피")
            assertEquals("m1", audio.playing)
            assertTrue(engine.testimonies.value.isEmpty())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `질문 구간 대답 중 위험이 오면 심문을 끊고, 조용해지면 안내 후 다음 장면으로 간다`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine =
                DefaultStoryEngine(
                    ScenarioRepository { testScenario },
                    audio,
                    scope = backgroundScope,
                    clock = { testScheduler.currentTime },
                )
            engine.start("test")
            engine.onAudioEvent(AudioEvent.SceneFinished("briefing"))
            engine.onVehicleEvent(VehicleEvent.Driving)
            engine.onAudioEvent(AudioEvent.SentenceStarted("m1", 2))
            engine.askBetweenScenes("a", "커피")
            val reply = audio.playing!!

            engine.onVehicleEvent(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
            assertEquals(AppMode.PAUSED, engine.mode.value)
            assertNull(engine.interrogation.value)

            // 위험 직후의 출발로는 재개하지 않는다. 조용한 시간이 지나면 안내 후 다음 장면으로 간다.
            engine.onVehicleEvent(VehicleEvent.Driving)
            assertEquals(AppMode.PAUSED, engine.mode.value)
            testScheduler.advanceTimeBy(3_100)
            val notice = audio.playing!!
            assertTrue(notice.startsWith("reply_") && notice != reply)
            assertEquals(AppMode.DRIVING, engine.mode.value)
            engine.onAudioEvent(AudioEvent.SceneFinished(notice))
            assertEquals("opt1" to 0, audio.history.last())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `질문 구간 대답 중 이미 주행 중이라는 알림이 와도 대답을 끊지 않는다`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = questionWindowEngine()
            engine.onAudioEvent(AudioEvent.SentenceStarted("m1", 2))
            engine.askBetweenScenes("a", "커피")
            val reply = audio.playing!!

            engine.onVehicleEvent(VehicleEvent.Driving)

            assertEquals(reply, audio.playing)
            assertNotNull(engine.interrogation.value?.answer)
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
    effect: String? = null,
) = Scene(
    id = id,
    title = id,
    required = required,
    durationSec = durationSec,
    script = listOf("$id 첫 문장", "$id 둘째 문장", "$id 셋째 문장"),
    unlocks = unlocks,
    trigger = trigger,
    effect = effect,
)

private val testScenario =
    Scenario(
        id = "test",
        title = "테스트 사건",
        culprit = "a",
        victim = Victim("피해자", "대표"),
        suspects =
            listOf(
                Suspect(
                    id = "a",
                    name = "가",
                    role = "비서",
                    relation = "비서",
                    voice = "v1",
                    persona = Persona("", "", ""),
                    revealable = listOf("c2"),
                    interrogation =
                        listOf(
                            Answer("q1", "커피는 왜 샀나요?", listOf("커피"), "대표님이 드실 커피였어요.", reveals = listOf("c2")),
                        ),
                    fallback = listOf("모르겠습니다."),
                ),
                Suspect("b", "나", "기사", "기사", "v2", Persona("", "", ""), fallback = listOf("몰라요.")),
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
                            scene("m1", unlocks = listOf("c1"), effect = "tension"),
                            scene("opt1", required = false, durationSec = 20),
                            scene("m2", unlocks = listOf("c2")),
                            scene("tunnel", required = false, trigger = "tunnel"),
                            scene("ending_correct", trigger = SceneTrigger.ENDING_CORRECT),
                            scene("ending_wrong", trigger = SceneTrigger.ENDING_WRONG),
                        ),
                ),
            ),
    )
