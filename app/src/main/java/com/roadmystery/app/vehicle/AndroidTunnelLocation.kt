package com.roadmystery.app.vehicle

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.roadmystery.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn

/** 앱이 보이는 동안만 GPS 수신. 위치 원문을 서버에 전송하거나 저장하지 않는다. */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidTunnelLocation(private val context: Context, private val lifecycle: Lifecycle) {
    private val revision = MutableStateFlow(0)
    private val message = MutableStateFlow("GPS 대기")
    val status = message.asStateFlow()
    private val latest = MutableStateFlow<PositionFix?>(null)
    val lastFix = latest.asStateFlow()

    fun refreshPermission() {
        revision.value += 1
    }

    @SuppressLint("MissingPermission")
    val events = revision.flatMapLatest {
        callbackFlow {
            val map = runCatching { context.assets.open(TunnelMap.ASSET).bufferedReader().use { TunnelMap.parse(it.readText()) } }
                .getOrElse {
                    message.value = "터널 지도 로드 실패"
                    close()
                    return@callbackFlow
                }
            val detector = MappedPlaceDetector(
                map.regions,
                PlaceEventDetector {
                    message.value = "${map.name}: ${if (it is VehicleEvent.Place) "진입" else "이탈"}"
                    trySend(it)
                }
            )
            val manager = context.getSystemService(LocationManager::class.java)
            var registered = false
            var lastTimestamp = -1L
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    // 캐시된 동일 fix를 두 번 받았다고 진입을 확정하지 않는다.
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (location.elapsedRealtimeNanos <= lastTimestamp || location.elapsedRealtimeNanos > now || location.elapsedRealtimeNanos <= 0) return
                    if (lastTimestamp > 0 && location.elapsedRealtimeNanos - lastTimestamp > 5_000_000_000L) detector.update(null)
                    lastTimestamp = location.elapsedRealtimeNanos
                    val fix = PositionFix(
                        GeoPoint(location.latitude, location.longitude),
                        if (location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY,
                        (now - location.elapsedRealtimeNanos) / 1_000_000
                    )
                    latest.value = fix
                    detector.update(fix)
                }
                override fun onProviderDisabled(provider: String) {
                    detector.update(null)
                    latest.value = null
                    message.value = "GPS 꺼짐 · 위치 판정 보류"
                }
                override fun onProviderEnabled(provider: String) {
                    message.value = "GPS 수신 대기"
                }
            }
            fun stop() {
                if (BuildConfig.DEBUG) Log.d("TunnelLocation", "unregister lifecycle=${lifecycle.currentState}")
                if (registered) manager.removeUpdates(listener)
                registered = false
                lastTimestamp = -1L
                latest.value = null
                detector.update(null)
            }
            fun start() {
                if (registered) return
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    message.value = "정확한 위치 권한 필요 · 터널 자동 감지 비활성"
                    return
                }
                runCatching {
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, listener, Looper.getMainLooper())
                    registered = true
                    if (BuildConfig.DEBUG) Log.d("TunnelLocation", "registered lifecycle=${lifecycle.currentState}")
                    message.value = if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) "${map.name} 지도 준비 · GPS 수신 대기" else "GPS 꺼짐 · 위치 판정 보류"
                }.onFailure { message.value = "위치 수신 불가 · 터널 자동 감지 비활성" }
            }
            val observer = LifecycleEventObserver { _, event ->
                if (BuildConfig.DEBUG) Log.d("TunnelLocation", "lifecycle=$event")
                when (event) {
                    Lifecycle.Event.ON_START -> start()
                    Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> stop()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
            awaitClose {
                stop()
                lifecycle.removeObserver(observer)
            }
        }.flowOn(Dispatchers.Main.immediate)
    }
}
