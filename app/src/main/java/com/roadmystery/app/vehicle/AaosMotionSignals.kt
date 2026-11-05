package com.roadmystery.app.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn

/** AAOS API 34. Subscribe to continuous speed so stale timestamps cannot unlock the UI. */
class AaosMotionSignals(private val context: Context) {
    val samples = callbackFlow {
        var sample = MotionSample()
        var properties: CarPropertyManager? = null
        var ux: CarUxRestrictionsManager? = null
        var closed = false
        var accepting = false
        fun publish(next: MotionSample) {
            sample = next
            trySend(sample)
        }
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                if (!accepting || closed) return
                val available = value.status == CarPropertyValue.STATUS_AVAILABLE
                when (value.propertyId) {
                    VehiclePropertyIds.PERF_VEHICLE_SPEED -> publish(
                        sample.copy(
                            speedMps = if (available) value.value as? Float else null,
                            sampledAtMs = if (available) value.timestamp / 1_000_000 else null
                        )
                    )
                    VehiclePropertyIds.GEAR_SELECTION -> {
                        val gear = if (available) value.value as? Int else null
                        val known = gear in setOf(
                            VehicleGear.GEAR_PARK, VehicleGear.GEAR_NEUTRAL, VehicleGear.GEAR_REVERSE,
                            VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_FIRST, VehicleGear.GEAR_SECOND,
                            VehicleGear.GEAR_THIRD, VehicleGear.GEAR_FOURTH, VehicleGear.GEAR_FIFTH,
                            VehicleGear.GEAR_SIXTH, VehicleGear.GEAR_SEVENTH, VehicleGear.GEAR_EIGHTH,
                            VehicleGear.GEAR_NINTH
                        )
                        publish(sample.copy(parked = if (known) gear == VehicleGear.GEAR_PARK else null))
                    }
                }
            }
            override fun onErrorEvent(propertyId: Int, areaId: Int) {
                if (!accepting || closed) return
                when (propertyId) {
                    VehiclePropertyIds.PERF_VEHICLE_SPEED -> publish(sample.copy(speedMps = null, sampledAtMs = null))
                    VehiclePropertyIds.GEAR_SELECTION -> publish(sample.copy(parked = null))
                }
            }
        }
        fun unregister() {
            accepting = false
            runCatching { properties?.unregisterCallback(callback) }
            runCatching { ux?.unregisterListener() }
            properties = null
            ux = null
        }
        publish(MotionSample())
        val car = Car.createCar(context.applicationContext, Handler(Looper.getMainLooper()), 0) { connected, ready ->
            if (!closed) {
                unregister()
                publish(MotionSample())
                if (ready) {
                    runCatching {
                        accepting = true
                        properties = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                        check(properties!!.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, 5f))
                        check(properties!!.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, 0f))
                        ux = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                        ux!!.registerListener { restrictions ->
                            if (accepting && !closed) publish(sample.copy(restricted = restrictions.isRequiresDistractionOptimization))
                        }
                        publish(sample.copy(restricted = ux!!.currentCarUxRestrictions.isRequiresDistractionOptimization))
                    }.onFailure {
                        unregister()
                        publish(MotionSample())
                        Log.w("F01Motion", "차량 신호 연결 실패 (권한·지원 여부 확인)", it)
                    }
                }
            }
        }
        awaitClose {
            closed = true
            unregister()
            car?.disconnect()
        }
    }.catch { error ->
        Log.w("F01Motion", "차량 서비스 사용 불가", error)
        emit(MotionSample())
    }.flowOn(Dispatchers.Main.immediate)
}
