package com.roadmystery.app.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionClassifierTest {
    private val classifier = MotionClassifier()
    private fun at(time: Long, speed: Float? = 0f, parked: Boolean? = false, restricted: Boolean? = false) = classifier.classify(MotionSample(speed, time, parked, restricted), time).event

    @Test fun `starts conservative and requires full two seconds`() {
        assertEquals(VehicleEvent.Driving, at(0))
        assertEquals(VehicleEvent.Driving, at(1999))
        assertEquals(VehicleEvent.Stopped, at(2000))
    }

    @Test fun `park also requires stationary confirmation`() {
        assertEquals(VehicleEvent.Driving, at(0, parked = true))
        assertEquals(VehicleEvent.Parked, at(2000, parked = true))
    }

    @Test fun `departure is immediate even with park gear`() {
        at(0, parked = true)
        assertEquals(VehicleEvent.Parked, at(2000, parked = true))
        assertEquals(VehicleEvent.Driving, at(2001, 0.001f, parked = true))
    }

    @Test fun `reverse speed is moving`() {
        at(0)
        at(2000)
        assertEquals(VehicleEvent.Driving, at(2001, -2f))
    }

    @Test fun `short stops reset the confirmation timer`() {
        at(0)
        at(1500, 1f)
        assertEquals(VehicleEvent.Driving, at(1600))
        assertEquals(VehicleEvent.Driving, at(3500))
        assertEquals(VehicleEvent.Stopped, at(3600))
    }

    @Test fun `loss and invalid numbers close immediately and recovery waits again`() {
        for (invalid in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val c = MotionClassifier()
            c.classify(MotionSample(0f, 0, false, false), 0)
            assertEquals(VehicleEvent.Stopped, c.classify(MotionSample(0f, 2000, false, false), 2000).event)
            assertEquals(VehicleEvent.Driving, c.classify(MotionSample(invalid, 2001, false, false), 2001).event)
            assertEquals(VehicleEvent.Driving, c.classify(MotionSample(0f, 2002, false, false), 2002).event)
        }
    }

    @Test fun `stale null and future timestamps are rejected`() {
        for (time in listOf(null, 0L, 4000L)) {
            assertEquals(VehicleEvent.Driving, classifier.classify(MotionSample(0f, time, true, false), 3001).event)
        }
    }

    @Test fun `unknown gear and ux never open stationary screen`() {
        assertEquals(VehicleEvent.Driving, at(0, parked = null))
        assertEquals(VehicleEvent.Driving, at(5000, parked = null))
        assertEquals(VehicleEvent.Driving, at(6000, restricted = null))
        assertEquals(VehicleEvent.Driving, at(9000, restricted = true))
    }

    @Test fun `restriction while stopped immediately closes screen`() {
        at(0)
        assertEquals(VehicleEvent.Stopped, at(2000))
        assertEquals(VehicleEvent.Driving, at(2001, restricted = true))
        assertEquals(VehicleEvent.Stopped, at(2002))
        assertEquals(VehicleEvent.Stopped, at(4002))
    }

    @Test fun `gear changes while confirmed stationary preserve confirmation`() {
        at(0)
        assertEquals(VehicleEvent.Stopped, at(2000))
        assertEquals(VehicleEvent.Parked, at(2001, parked = true))
        assertEquals(VehicleEvent.Stopped, at(2002))
    }

    @Test fun `stationary classification is distinct from platform screen restriction`() {
        classifier.classify(MotionSample(0f, 0, false, true), 0)
        val result = classifier.classify(MotionSample(0f, 2000, false, true), 2000)
        assertEquals(VehicleEvent.Stopped, result.physicalEvent)
        assertEquals(VehicleEvent.Driving, result.event)
    }
}
