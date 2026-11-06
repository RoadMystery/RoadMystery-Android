package com.roadmystery.app.audio

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.roadmystery.app.BuildConfig
import com.roadmystery.app.story.AppMode
import com.roadmystery.app.story.AssetScenarioRepository
import com.roadmystery.app.story.DefaultStoryEngine
import com.roadmystery.app.story.Progress
import com.roadmystery.app.story.Scene
import com.roadmystery.app.ui.theme.RoadMysteryTheme
import com.roadmystery.app.vehicle.FakeVehicleState
import com.roadmystery.app.vehicle.HazardReason
import com.roadmystery.app.vehicle.VehicleEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 정차 상태에서 사용하는 개발 화면. 실제 위험 신호 감지나 F-05 음성 심문을 대신하지 않는다. */
class NarrationTestActivity : ComponentActivity() {
    private lateinit var audio: PleosTtsAudioPlayer
    private lateinit var engine: DefaultStoryEngine
    private var sampleIndex = 0
    private val sample = Scene(
        id = "f03_voice_sample",
        title = "진행자와 용의자 음성 확인",
        durationSec = 20,
        script = listOf(
            "탐정님, 지금부터 네 사람의 목소리와 문장 중단 기능을 확인하겠습니다.",
            "저는 박서진입니다. 커피를 건넨 뒤에는 편의점과 화장실에 다녀왔습니다.",
            "저는 오민혁입니다. 담배 피우는 모습이 찍힐까 봐 전원을 뽑았습니다.",
            "저는 윤재희입니다. 그 시간에는 주차장에서 전화하고 있었습니다."
        ),
        sentenceVoices = listOf("narrator", "female_calm", "male_rough", "female_young")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = PleosTtsAudioPlayer(this, lifecycleScope, BuildConfig.PLEOS_CLIENT_ID, BuildConfig.PLEOS_CLIENT_SECRET)
        engine = DefaultStoryEngine(AssetScenarioRepository(assets), audio, scope = lifecycleScope)
        engine.bind(FakeVehicleState(), lifecycleScope)
        lifecycleScope.launch { audio.status.collect { Log.i("F03Test", "status=$it") } }
        lifecycleScope.launch { audio.events.collect { Log.i("F03Test", "event=$it") } }
        lifecycleScope.launch { engine.mode.collect { Log.i("F03Test", "engine mode=$it") } }
        if (intent.getBooleanExtra("engineSmoke", false)) {
            lifecycleScope.launch {
                engine.start(
                    "midnight_rest_area",
                    Progress(
                        scenarioId = "midnight_rest_area",
                        chapterId = "ch1",
                        lastSceneId = "s01_scene",
                        lastSentenceIndex = 1
                    )
                )
                engine.onVehicleEvent(VehicleEvent.Driving)
                delay(2_000)
                engine.onVehicleEvent(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
                Log.i("F03Test", "ENGINE_SMOKE first hazard")
                delay(12_000)
                engine.onVehicleEvent(VehicleEvent.Hazard(HazardReason.ADAS_WARNING))
                Log.i("F03Test", "ENGINE_SMOKE repeated hazard")
                delay(6_000)
                Log.i("F03Test", "ENGINE_SMOKE before stopped mode=${engine.mode.value}")
                engine.onVehicleEvent(VehicleEvent.Stopped)
                delay(15_000)
                audio.pause()
                Log.i("F03Test", "ENGINE_SMOKE finished mode=${engine.mode.value}")
            }
        }
        if (intent.getBooleanExtra("smoke", false)) {
            lifecycleScope.launch {
                audio.play(sample)
                delay(2_000)
                sampleIndex = audio.pause()
                Log.i("F03Test", "SMOKE paused sentence=$sampleIndex")
                delay(3_000)
                audio.play(sample, sampleIndex)
                Log.i("F03Test", "SMOKE resumed sentence=$sampleIndex")
            }
        }
        setContent {
            val status by audio.status.collectAsState()
            val mode by engine.mode.collectAsState()
            val playing by engine.nowPlaying.collectAsState()
            val progress by engine.progress.collectAsState()
            RoadMysteryTheme {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("F-03 낭독 테스트 · 정차 중 개발용")
                    Text("심야 휴게소의 마지막 손님 · $mode")
                    Text(playing?.title ?: "재생 대기")
                    LinearProgressIndicator(progress = { progress })
                    Text(status.message)
                    Button(onClick = {
                        engine.start("midnight_rest_area")
                        engine.onVehicleEvent(VehicleEvent.Driving)
                    }) { Text("기존 사건 낭독 시작") }
                    Button(onClick = { engine.onVehicleEvent(VehicleEvent.Hazard(HazardReason.HARD_BRAKE)) }) {
                        Text("위험 이벤트 → 중단·3초 후 재개")
                    }
                    Button(onClick = { engine.onVehicleEvent(VehicleEvent.Stopped) }) { Text("정차 이벤트") }
                    Button(onClick = { engine.onVehicleEvent(VehicleEvent.Driving) }) { Text("출발 이벤트") }
                    Button(onClick = {
                        engine.stop()
                        sampleIndex = 0
                        audio.play(sample)
                    }) { Text("진행자 + 용의자 3명 음성 샘플") }
                    Button(enabled = mode == AppMode.IDLE, onClick = { sampleIndex = audio.pause() }) { Text("샘플 일시정지") }
                    Button(enabled = mode == AppMode.IDLE, onClick = { audio.play(sample, sampleIndex) }) { Text("샘플 멈춘 문장부터 재개") }
                    Button(onClick = { audio.retry() }) { Text("음성 오류 재시도") }
                    Text("샘플은 화자 구분 확인용입니다. 기존 사건 원고는 변경하지 않았습니다.")
                }
            }
        }
    }

    override fun onDestroy() {
        engine.stop()
        audio.release()
        super.onDestroy()
    }
}
