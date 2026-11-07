package com.roadmystery.app.vehicle

enum class HazardReason { HARD_BRAKE, ADAS_WARNING, USER_STOP }

/** 차량 신호를 게임이 이해하는 단위로 바꾼 이벤트. StoryEngine은 이것만 받는다. */
sealed interface VehicleEvent {
    data object Driving : VehicleEvent

    data object Stopped : VehicleEvent

    data object Parked : VehicleEvent

    data object Arrived : VehicleEvent

    data class EtaChanged(val remainingSec: Int) : VehicleEvent

    data class Hazard(val reason: HazardReason) : VehicleEvent

    /** "tunnel", 지역명 등 */
    data class Place(val tag: String) : VehicleEvent

    /** 확인된 구역 이탈. 위치 신호 유실은 이탈로 취급하지 않는다. */
    data class PlaceExited(val tag: String) : VehicleEvent
}
