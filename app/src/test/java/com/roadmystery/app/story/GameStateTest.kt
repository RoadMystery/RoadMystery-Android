package com.roadmystery.app.story

import com.roadmystery.app.vehicle.HazardReason
import com.roadmystery.app.vehicle.VehicleEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameStateTest {
    @Test
    fun `정차할 때마다 단서가 하나씩 열린다`() {
        val game = GameState()

        game.onVehicleEvent(VehicleEvent.Driving)
        game.onVehicleEvent(VehicleEvent.Stopped)
        assertEquals(AppMode.STOPPED, game.mode.value)
        assertEquals(listOf("clue_receipt"), game.unlockedClues.value.map { it.id })
        assertEquals("clue_receipt", game.newClueId.value)

        game.onVehicleEvent(VehicleEvent.Stopped) // 중복 정차는 무시
        assertEquals(1, game.unlockedClues.value.size)

        game.onVehicleEvent(VehicleEvent.Driving)
        assertNull(game.newClueId.value)
        game.onVehicleEvent(VehicleEvent.Stopped)
        assertEquals("clue_dashcam", game.newClueId.value)
    }

    @Test
    fun `단서를 다 열면 더 열리지 않는다`() {
        val game = GameState()
        repeat(5) {
            game.onVehicleEvent(VehicleEvent.Driving)
            game.onVehicleEvent(VehicleEvent.Stopped)
        }
        assertEquals(3, game.unlockedClues.value.size)
    }

    @Test
    fun `급제동이면 PAUSED, 도착이면 ENDING이고 이후 이벤트는 무시한다`() {
        val game = GameState()
        game.onVehicleEvent(VehicleEvent.Driving)
        game.onVehicleEvent(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
        assertEquals(AppMode.PAUSED, game.mode.value)

        game.onVehicleEvent(VehicleEvent.Driving)
        game.onVehicleEvent(VehicleEvent.Arrived)
        assertEquals(AppMode.ENDING, game.mode.value)

        game.onVehicleEvent(VehicleEvent.Driving)
        assertEquals(AppMode.ENDING, game.mode.value)
    }
}
