package com.roadmystery.app.vehicle

import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge

/**
 * 실제 차량 신호를 VehicleEvent로 바꾼다.
 *
 * - 기어 P → Parked
 * - 그 외 기어에서 CarUxRestrictions가 주행 제한을 요구하면 → Driving, 아니면(신호 대기) → Stopped
 * - 기어나 주행 제한을 알 수 없으면 → Driving (판정 불가 시 주행으로 간주)
 * - NaviHelper: 남은 시간 → EtaChanged, 목적지 도착 → Arrived
 *
 * 급제동·ADAS 경고는 SDK에 해당 신호가 없어 시뮬레이션 패널로만 넣는다.
 */
class PleosVehicleState(
    private val context: Context,
    private val vehicle: Vehicle,
) : VehicleState {
    override val events: Flow<VehicleEvent> = merge(motionEvents(), naviEvents())

    private fun motionEvents(): Flow<VehicleEvent> =
        callbackFlow {
            var currentGear: VehicleGear? = null
            var moving: Boolean? = null

            fun publish() {
                val event =
                    when {
                        currentGear == VehicleGear.GEAR_PARK -> VehicleEvent.Parked
                        currentGear == null || currentGear == VehicleGear.GEAR_UNKNOWN || moving == null -> VehicleEvent.Driving
                        moving == true -> VehicleEvent.Driving
                        else -> VehicleEvent.Stopped
                    }
                trySend(event)
            }

            val gearListener =
                object : GearSelectionListener {
                    override fun onSelected(gear: VehicleGear?) {
                        currentGear = gear
                        publish()
                    }

                    override fun onFailed(exception: Exception) {
                        Log.w(TAG, "기어 신호 실패: ${exception.message}")
                        currentGear = null
                        publish()
                    }
                }
            runCatching {
                vehicle.getGear().registerGearSelection(gearListener)
                vehicle.getGear().getCurrentGearSelectionState(
                    {
                        currentGear = it
                        publish()
                    },
                    { Log.w(TAG, "기어 조회 실패: ${it.message}") },
                )
            }.onFailure { Log.w(TAG, "Gear 등록 실패: ${it.message}") }

            val car = runCatching { Car.createCar(context) }.getOrNull()
            val ux = runCatching { car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager }.getOrNull()
            fun onUx(restrictions: CarUxRestrictions?) {
                moving = restrictions?.isRequiresDistractionOptimization
                publish()
            }
            ux?.registerListener { onUx(it) }
            onUx(runCatching { ux?.currentCarUxRestrictions }.getOrNull())

            awaitClose {
                runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
                runCatching { ux?.unregisterListener() }
                runCatching { car?.disconnect() }
            }
        }.distinctUntilChanged()

    private fun naviEvents(): Flow<VehicleEvent> =
        callbackFlow {
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
