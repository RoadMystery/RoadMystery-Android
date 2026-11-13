package com.roadmystery.app.vehicle

import com.roadmystery.app.audio.FakeAudioPlayer
import com.roadmystery.app.story.AppMode
import com.roadmystery.app.story.DefaultStoryEngine
import com.roadmystery.app.story.ScenarioParser
import com.roadmystery.app.story.ScenarioRepository
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MotionEngineIntegrationTest {
    @Test fun `raw signals control the existing engine screen permission`() = runTest {
        val scenario = ScenarioParser.parse(File("src/main/assets/scenarios/midnight_rest_area.json").readText())
        val fake = FakeMotionSignals { testScheduler.currentTime }
        val vehicle = object : VehicleState {
            override val events = fake.samples.classifyMotion { testScheduler.currentTime }.motionEvents()
        }
        val engine = DefaultStoryEngine(ScenarioRepository { scenario }, FakeAudioPlayer(backgroundScope))
        engine.start(scenario.id)
        engine.bind(vehicle, backgroundScope)
        runCurrent()
        advanceTimeBy(2100)
        runCurrent()
        assertEquals(AppMode.STOPPED, engine.mode.value)
        assertTrue(engine.canInteract.value)
        fake.set(8f)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(AppMode.DRIVING, engine.mode.value)
        assertFalse(engine.canInteract.value)
        fake.set(0f, restricted = true)
        advanceTimeBy(2500)
        runCurrent()
        assertFalse(engine.canInteract.value)
        fake.set(0f, parked = true)
        advanceTimeBy(100)
        runCurrent()
        assertTrue(engine.canInteract.value)
        fake.set(null)
        advanceTimeBy(100)
        runCurrent()
        assertFalse(engine.canInteract.value)
    }

    @Test fun `demo autoplay ends at accusation after parking`() = runTest {
        val scenario = ScenarioParser.parse(File("src/main/assets/scenarios/midnight_rest_area.json").readText())
        val simulated = FakeVehicleState()
        val selected = SelectedVehicleState(emptyFlow(), simulated.events, VehicleSource.SIMULATION)
        val engine = DefaultStoryEngine(
            ScenarioRepository { scenario },
            FakeAudioPlayer(backgroundScope, speed = 10f)
        )
        engine.bind(selected, backgroundScope)
        runCurrent()

        DemoAutoplay(simulated, engine).run(scenario.id)
        runCurrent()

        assertEquals(AppMode.ENDING, engine.mode.value)
        assertTrue(engine.canInteract.value)
    }

    @Test fun `a hazard still held at arrival does not block the accusation`() = runTest {
        val scenario = ScenarioParser.parse(File("src/main/assets/scenarios/midnight_rest_area.json").readText())
        val simulated = FakeVehicleState()
        val selected = SelectedVehicleState(emptyFlow(), simulated.events, VehicleSource.SIMULATION)
        val engine = DefaultStoryEngine(
            ScenarioRepository { scenario },
            FakeAudioPlayer(backgroundScope),
            scope = backgroundScope,
            clock = { testScheduler.currentTime }
        )
        engine.bind(selected, backgroundScope)
        runCurrent()
        engine.start(scenario.id)
        simulated.send(VehicleEvent.Driving)
        advanceTimeBy(5_000)

        // 급제동 뒤 3초 안에 서면 낭독은 다음 출발까지 멈춘 채로 남는다. 그대로 도착해 주차한다.
        simulated.send(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
        advanceTimeBy(1_500)
        simulated.send(VehicleEvent.Stopped)
        advanceTimeBy(10_000)
        simulated.send(VehicleEvent.Arrived)
        simulated.send(VehicleEvent.Parked)
        runCurrent()

        assertEquals(AppMode.ENDING, engine.mode.value)
        assertTrue(engine.canInteract.value)
    }
}
