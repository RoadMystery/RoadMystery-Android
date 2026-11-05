package com.roadmystery.app.vehicle

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.roadmystery.app.ui.theme.RoadMysteryTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** Debug only. No StoryEngine, narration or hardware control is started here. */
@OptIn(ExperimentalCoroutinesApi::class)
class MotionTestActivity : ComponentActivity() {
    private val fake = FakeMotionSignals(SystemClock::elapsedRealtime)
    private val real = MutableStateFlow(false)
    private var useReal by mutableStateOf(false)
    private var reading by mutableStateOf(MotionReading())
    private var history by mutableStateOf(emptyList<String>())
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        select(true)
    }

    private fun select(actual: Boolean) {
        useReal = actual
        reading = MotionReading()
        history = emptyList()
        real.value = actual
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        select(intent.getBooleanExtra("real", false))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                real.flatMapLatest { actual ->
                    (if (actual) AaosMotionSignals(this@MotionTestActivity).samples else fake.samples)
                        .classifyMotion(SystemClock::elapsedRealtime)
                }.collect { next ->
                    if (reading.physicalEvent != next.physicalEvent || reading.event != next.event || reading.reason != next.reason || history.isEmpty()) {
                        val line = "${SystemClock.elapsedRealtime()} physical=${next.physicalEvent} app=${next.event}: ${next.reason}"
                        history = (listOf(line) + history).take(8)
                        Log.i("F01Motion", line)
                    }
                    reading = next
                }
            }
        }
        setContent {
            RoadMysteryTheme {
                Scaffold { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("F-01 · 주행 / 정차 판정", style = MaterialTheme.typography.headlineMedium)
                        Text(if (useReal) "입력: 에뮬레이터 차량 신호 (AAOS)" else "입력: 가짜 속도·기어 신호")
                        Text(
                            when (reading.event) {
                                VehicleEvent.Parked -> "정차 모드 · 주차(P)"
                                VehicleEvent.Stopped -> "정차 모드 · 신호대기"
                                else -> "주행 모드 · 화면 최소 표시"
                            },
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            "차량 판정: " + when (reading.physicalEvent) {
                                VehicleEvent.Parked -> "주차"
                                VehicleEvent.Stopped -> "정차"
                                else -> "주행 또는 정차 확인 중"
                            }
                        )
                        Text(reading.reason)
                        val value = reading.sample
                        Text("속도 ${value.speedMps?.let { "%.2f".format(it * 3.6f) } ?: "미수신"} km/h · P=${value.parked} · UX 제한=${value.restricted}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { select(false) }) { Text("가짜 신호") }
                            Button(onClick = {
                                // Re-selection must re-register after a previously denied permission.
                                select(false)
                                permissions.launch(arrayOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN"))
                            }) { Text("실제 신호 연결") }
                        }
                        if (!useReal) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { fake.set(30f / 3.6f) }) { Text("출발 30 km/h") }
                                Button(onClick = { fake.set(0f) }) { Text("정지 0 km/h · D") }
                                Button(onClick = { fake.set(0f, true) }) { Text("주차 0 km/h · P") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { fake.set(null) }) { Text("속도 미수신") }
                                Button(onClick = { fake.freezeSpeed() }) { Text("속도 갱신 중단") }
                                Button(onClick = { fake.set(0f, restricted = true) }) { Text("화면 제한") }
                            }
                            Text("정차 진입은 2초 유지 후, 출발·미수신·화면 제한은 즉시 주행 처리. 갱신 중단은 3초 초과 후 주행 처리.")
                        }
                        Text("최근 판정 (개발용)", style = MaterialTheme.typography.titleMedium)
                        history.forEach { Text(it) }
                    }
                }
            }
        }
    }
}
