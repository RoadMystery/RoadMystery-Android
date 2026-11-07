package com.roadmystery.app.vehicle

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelMapTest {
    private fun map(): TunnelMap {
        val file = listOf(File("src/main/assets/${TunnelMap.ASSET}"), File("app/src/main/assets/${TunnelMap.ASSET}")).first { it.exists() }
        return TunnelMap.parse(file.readText())
    }

    @Test fun `downloaded OSM tunnel outside inside outside emits exactly one pair`() {
        val map = map()
        assertEquals("https://www.openstreetmap.org/way/37930783", map.source)
        val events = mutableListOf<VehicleEvent>()
        val detector = MappedPlaceDetector(map.regions, PlaceEventDetector(events::add))
        fun move(lat: Double, lon: Double) = repeat(4) { detector.update(PositionFix(GeoPoint(lat, lon), 5.0, 0)) }
        move(37.55688396, 126.98260931)
        assertTrue(events.isEmpty())
        move(37.5501163, 126.98546526)
        assertEquals(listOf(VehicleEvent.Place("tunnel")), events)
        move(37.54344575, 126.98869924)
        assertEquals(listOf(VehicleEvent.Place("tunnel"), VehicleEvent.PlaceExited("tunnel")), events)
    }

    @Test fun `nearby off corridor road does not enter tunnel`() {
        val events = mutableListOf<VehicleEvent>()
        val detector = MappedPlaceDetector(map().regions, PlaceEventDetector(events::add))
        repeat(4) { detector.update(PositionFix(GeoPoint(37.5501163, 126.9865), 5.0, 0)) }
        assertTrue(events.isEmpty())
    }

    @Test fun `coarse inaccurate position cannot enter mapped tunnel`() {
        val events = mutableListOf<VehicleEvent>()
        val detector = MappedPlaceDetector(map().regions, PlaceEventDetector(events::add))
        repeat(4) { detector.update(PositionFix(GeoPoint(37.5501163, 126.98546526), 200.0, 0)) }
        assertTrue(events.isEmpty())
    }
}
