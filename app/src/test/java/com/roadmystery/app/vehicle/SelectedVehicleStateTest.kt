package com.roadmystery.app.vehicle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectedVehicleStateTest {
    @Test fun `real events never leak into simulation`() = runTest {
        val real = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 8)
        val fake = FakeVehicleState()
        val selected = SelectedVehicleState(real, fake.events, VehicleSource.SIMULATION)
        val seen = mutableListOf<VehicleEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { selected.events.toList(seen) }
        runCurrent()
        real.emit(VehicleEvent.Place("real"))
        fake.send(VehicleEvent.Stopped)
        runCurrent()
        assertFalse(seen.contains(VehicleEvent.Place("real")))
        assertEquals(VehicleEvent.Stopped, seen.last())
        selected.select(VehicleSource.REAL)
        runCurrent()
        assertEquals(VehicleEvent.Driving, seen.last())
        fake.send(VehicleEvent.Place("fake"))
        real.emit(VehicleEvent.Place("real"))
        runCurrent()
        assertFalse(seen.contains(VehicleEvent.Place("fake")))
        assertTrue(seen.contains(VehicleEvent.Place("real")))
    }

    @Test fun `arrival while driving waits until parking not a traffic light`() = runTest {
        val seen = flowOf(VehicleEvent.Driving, VehicleEvent.Arrived, VehicleEvent.Stopped, VehicleEvent.Parked)
            .arriveOnlyWhenParked().toList()
        assertEquals(listOf(VehicleEvent.Driving, VehicleEvent.Stopped, VehicleEvent.Parked, VehicleEvent.Arrived), seen)
    }

    @Test fun `switching away from stopped resets the engine to driving`() = runTest {
        val real = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 8)
        val fake = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 8)
        val selected = SelectedVehicleState(real, fake, VehicleSource.REAL)
        val seen = mutableListOf<VehicleEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { selected.events.toList(seen) }
        runCurrent()
        real.emit(VehicleEvent.Stopped)
        runCurrent()
        assertEquals(VehicleEvent.Stopped, seen.last())
        selected.select(VehicleSource.SIMULATION)
        runCurrent()
        assertEquals(VehicleEvent.Driving, seen.last())
    }
}
