package com.roadmystery.app.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceEventDetectorTest {
    private val events = mutableListOf<VehicleEvent>()
    private val detector = PlaceEventDetector(events::add)

    @Test fun `duplicate entry and missing gps do not repeat or exit`() {
        detector.accept(PlaceObservation("a", "tunnel", true))
        repeat(10) { detector.accept(PlaceObservation("a", "tunnel", true)) }
        detector.accept(PlaceObservation("a", "tunnel", null))
        assertEquals(listOf(VehicleEvent.Place("tunnel")), events)
        detector.accept(PlaceObservation("a", "tunnel", false))
        detector.accept(PlaceObservation("a", "tunnel", true))
        assertEquals(listOf(VehicleEvent.Place("tunnel"), VehicleEvent.PlaceExited("tunnel"), VehicleEvent.Place("tunnel")), events)
    }

    @Test fun `overlapping map regions only exit after all regions exited`() {
        detector.accept(PlaceObservation("a", "tunnel", true))
        detector.accept(PlaceObservation("b", "tunnel", true))
        detector.accept(PlaceObservation("a", "tunnel", false))
        assertEquals(1, events.size)
        detector.accept(PlaceObservation("b", "tunnel", false))
        assertEquals(VehicleEvent.PlaceExited("tunnel"), events.last())
    }

    private fun mapped() = MappedPlaceDetector(
        listOf(
            PlaceRegion(
                "demo",
                "tunnel",
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 0.01),
                    GeoPoint(0.01, 0.01),
                    GeoPoint(0.01, 0.0)
                )
            )
        ),
        detector
    )

    @Test fun `mapped entry and exit need two clear observations`() {
        val mapped = mapped()
        val inside = PositionFix(GeoPoint(0.005, 0.005), 10.0, 0)
        mapped.update(inside)
        assertTrue(events.isEmpty())
        mapped.update(inside)
        assertEquals(VehicleEvent.Place("tunnel"), events.single())
        val outside = PositionFix(GeoPoint(0.02, 0.02), 10.0, 0)
        mapped.update(outside)
        mapped.update(outside)
        assertEquals(VehicleEvent.PlaceExited("tunnel"), events.last())
    }

    @Test fun `poor stale boundary and missing fixes cannot trigger entry`() {
        val mapped = mapped()
        val inside = PositionFix(GeoPoint(0.005, 0.005), 10.0, 0)
        repeat(3) {
            mapped.update(inside.copy(accuracyMeters = 200.0))
            mapped.update(inside.copy(ageMs = 20_000))
            mapped.update(inside.copy(point = GeoPoint(0.0, 0.005)))
            mapped.update(null)
        }
        assertTrue(events.isEmpty())
        mapped.update(inside)
        mapped.update(null)
        mapped.update(inside)
        assertTrue(events.isEmpty())
    }
}
