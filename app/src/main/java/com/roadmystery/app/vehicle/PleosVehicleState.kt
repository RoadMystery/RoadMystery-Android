package com.roadmystery.app.vehicle

import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.merge

/** F-01 motion comes from confirmed AAOS speed, gear and UX restrictions. Navi events are unchanged. */
class PleosVehicleState(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") vehicle: Vehicle? = null
) : VehicleState {
    override val events: Flow<VehicleEvent> = merge(motionEvents(), naviEvents())

    private fun motionEvents(): Flow<VehicleEvent> = AaosMotionSignals(context).samples.classifyMotion(android.os.SystemClock::elapsedRealtime).motionEvents()

    private fun naviEvents(): Flow<VehicleEvent> = callbackFlow {
        val navi = NaviHelper(context)
        val listener =
            object : NaviHelperEventListener {
                override fun onDrivingInfo(info: DrivingInfo) {
                    trySend(VehicleEvent.EtaChanged(info.destination.duration))
                }

                override fun onDestinationArrived(info: DestinationArrivedInfo) {
                    trySend(VehicleEvent.Arrived)
                }
            }
        runCatching {
            navi.initialize({ navi.addListener(listener) }, { Log.w(TAG, "NaviHelper 초기화 실패: ${it.message}") })
        }.onFailure { Log.w(TAG, "NaviHelper 사용 불가: ${it.message}") }
        awaitClose {
            runCatching {
                navi.removeListener(listener)
                navi.release()
            }
        }
    }

    private companion object {
        const val TAG = "PleosVehicleState"
    }
}
