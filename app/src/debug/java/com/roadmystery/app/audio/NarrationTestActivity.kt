package com.roadmystery.app.audio

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    private var actionCount = 0
    private var feedback by mutableStateOf("사건 낭독 또는 음성 샘플을 시작해 주세요.")
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
            val canInteract by engine.canInteract.collectAsState()
            val storyActive = mode != AppMode.IDLE
            val sampleActive = !storyActive && status.sceneId == sample.id
            val samplePlaying = sampleActive &&
                status.phase in setOf(
                    PlaybackPhase.SPEAKING,
                    PlaybackPhase.QUESTION,
                    PlaybackPhase.WAITING
                )
            val modeLabel = when (mode) {
                AppMode.IDLE -> "사건 시작 전"
                AppMode.BRIEFING -> "브리핑"
                AppMode.DRIVING -> "주행 모드"
                AppMode.PAUSED -> "위험으로 일시정지"
                AppMode.STOPPED -> "정차 모드"
                AppMode.ENDING -> "결말"
            }
            RoadMysteryTheme {
                Column(
                    Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("F-03 낭독 테스트 · 정차 중 개발용", style = MaterialTheme.typography.titleLarge)
                    Text("사건 상태: $modeLabel", style = MaterialTheme.typography.titleMedium)
                    if (storyActive) {
                        Text("모의 차량 상태: ${if (canInteract) "정차" else "주행"} · ${playing?.title ?: "다음 장면 대기"}")
                        LinearProgressIndicator(progress = { progress })
                    } else if (sampleActive) {
                        Text("음성 샘플 · 차량 이벤트 테스트와 별개로 재생합니다.")
                    }
                    Text(status.message)
                    Text(feedback, color = MaterialTheme.colorScheme.primary)
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("사건 진행 · 심야 휴게소의 마지막 손님", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = {
                            engine.start("midnight_rest_area")
                            engine.onVehicleEvent(VehicleEvent.Driving)
                            report("사건을 처음부터 시작하고 출발 이벤트를 전달했습니다.")
                        }) { Text("기존 사건 낭독 시작") }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(enabled = storyActive, onClick = {
                                engine.onVehicleEvent(VehicleEvent.Stopped)
                                report("정차 이벤트 전달 완료 · 일반 정차에서는 낭독을 계속합니다.")
                            }) { Text("정차 이벤트") }
                            Button(enabled = storyActive, onClick = {
                                engine.onVehicleEvent(VehicleEvent.Driving)
                                report("출발 이벤트 전달 완료 · 위의 사건 상태와 모의 차량 상태를 확인하세요.")
                            }) { Text("출발 이벤트") }
                        }
                        Button(enabled = storyActive, onClick = {
                            engine.onVehicleEvent(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
                            report("위험 이벤트 전달 완료 · 낭독을 중단합니다.")
                        }) { Text("위험 이벤트 · 낭독 중단") }
                        Text("정차·출발은 사건 시작 후 사용하는 모의 차량 이벤트입니다. 음성 재생·정지 버튼이 아닙니다.")
                        Text("위험 1회: 주행 중 3초 후 재개 / 60초 내 반복 위험: 정차까지 보류")
                        Text("화자별 음성 샘플", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = {
                            engine.stop()
                            sampleIndex = 0
                            audio.play(sample)
                            report("음성 샘플을 시작했습니다. 사건 이벤트 버튼은 사용하지 않습니다.")
                        }) { Text("진행자 + 용의자 3명 음성 샘플") }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(enabled = samplePlaying, onClick = {
                                sampleIndex = audio.pause()
                                report("샘플을 일시정지했습니다. 재개하면 중단한 문장부터 다시 읽습니다.")
                            }) { Text("샘플 일시정지") }
                            Button(enabled = sampleActive && status.phase == PlaybackPhase.PAUSED, onClick = {
                                audio.play(sample, status.sentenceIndex)
                                report("샘플을 중단한 문장부터 재개합니다.")
                            }) { Text("샘플 멈춘 문장부터 재개") }
                        }
                        Button(enabled = status.phase == PlaybackPhase.ERROR, onClick = {
                            audio.retry()
                            report("실패한 문장을 다시 요청했습니다. 위의 음성 상태를 확인하세요.")
                        }) { Text("실패한 문장 재시도") }
                        Text("재시도는 음성 오류가 발생했을 때만 켜집니다. 처음부터 듣기는 위의 시작 버튼을 사용하세요.")
                        if (status.phase == PlaybackPhase.ERROR) {
                            Text("초기화 오류 또는 중단 응답 오류가 계속되면 앱을 다시 실행해 주세요.")
                        }
                    }
                }
            }
        }
    }

    private fun report(message: String) {
        actionCount += 1
        feedback = "[$actionCount] $message"
        Log.i("F03Test", "action=$feedback mode=${engine.mode.value}")
    }

    override fun onDestroy() {
        engine.stop()
        audio.release()
        super.onDestroy()
    }
}
