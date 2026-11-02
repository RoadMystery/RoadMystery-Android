package com.roadmystery.app.vehicle

import kotlinx.coroutines.flow.Flow

interface VehicleState {
    val events: Flow<VehicleEvent>
}
