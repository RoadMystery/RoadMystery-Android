package com.roadmystery.app.vehicle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MotionFlowTest {
    @Test fun `watchdog invalidates a silent source without another callback`() = runTest {
        val signals = MutableStateFlow(MotionSample(0f, 0, false, false))
        val seen = mutableListOf<VehicleEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            signals.classifyMotion { testScheduler.currentTime }.motionEvents().collect { seen.add(it) }
        }
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(listOf(VehicleEvent.Driving, VehicleEvent.Stopped), seen)
        advanceTimeBy(1100)
        runCurrent()
        assertEquals(listOf(VehicleEvent.Driving, VehicleEvent.Stopped, VehicleEvent.Driving), seen)
    }

    @Test fun `repeated stationary samples do not repeat mode events`() = runTest {
        val fake = FakeMotionSignals { testScheduler.currentTime }
        val seen = mutableListOf<VehicleEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fake.samples.classifyMotion { testScheduler.currentTime }.motionEvents().collect { seen.add(it) }
        }
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(listOf(VehicleEvent.Driving, VehicleEvent.Stopped), seen)
        fake.set(1f)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(VehicleEvent.Driving, seen.last())
    }

    @Test fun `disconnect and reconnect require a new stationary interval`() = runTest {
        val signals = MutableStateFlow(MotionSample(0f, 0, false, false))
        val seen = mutableListOf<VehicleEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            signals.classifyMotion { testScheduler.currentTime }.motionEvents().collect { seen.add(it) }
        }
        advanceTimeBy(2000)
        runCurrent()
        signals.value = MotionSample()
        runCurrent()
        assertEquals(VehicleEvent.Driving, seen.last())
        signals.value = MotionSample(0f, 2000, false, false)
        advanceTimeBy(1999)
        runCurrent()
        assertEquals(VehicleEvent.Driving, seen.last())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(VehicleEvent.Stopped, seen.last())
    }
}
