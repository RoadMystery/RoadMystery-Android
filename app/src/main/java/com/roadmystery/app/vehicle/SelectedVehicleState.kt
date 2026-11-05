package com.roadmystery.app.vehicle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart

enum class VehicleSource { REAL, SIMULATION }

/** Subscribe to one source only. Switching starts with a conservative motion state. */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectedVehicleState(
    private val real: Flow<VehicleEvent>,
    private val simulated: Flow<VehicleEvent>,
    initial: VehicleSource = VehicleSource.REAL
) : VehicleState {
    private val selected = MutableStateFlow(initial)
    val source = selected.asStateFlow()
    override val events = selected.flatMapLatest { source ->
        (if (source == VehicleSource.REAL) real else simulated)
            .onStart { emit(VehicleEvent.Driving) }.arriveOnlyWhenParked()
    }

    fun select(source: VehicleSource) {
        selected.value = source
    }
}

/** The current engine treats Arrived as stationary; hold arrival until actual parking. */
internal fun Flow<VehicleEvent>.arriveOnlyWhenParked(): Flow<VehicleEvent> = flow {
    var parked = false
    var pendingArrival = false
    collect { event ->
        when (event) {
            VehicleEvent.Arrived -> {
                pendingArrival = !parked
                if (parked) emit(event)
            }
            VehicleEvent.Parked -> {
                parked = true
                emit(event)
                if (pendingArrival) {
                    pendingArrival = false
                    emit(VehicleEvent.Arrived)
                }
            }
            VehicleEvent.Driving, VehicleEvent.Stopped -> {
                parked = false
                emit(event)
            }
            else -> emit(event)
        }
    }
}
