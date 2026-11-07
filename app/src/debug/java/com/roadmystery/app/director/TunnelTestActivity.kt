package com.roadmystery.app.director

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
import com.roadmystery.app.audio.PlaybackPhase
import com.roadmystery.app.audio.PleosTtsAudioPlayer
import com.roadmystery.app.story.AppMode
import com.roadmystery.app.story.AssetScenarioRepository
import com.roadmystery.app.story.DefaultStoryEngine
import com.roadmystery.app.story.Progress
import com.roadmystery.app.ui.theme.RoadMysteryTheme
import com.roadmystery.app.vehicle.FakeVehicleState
import com.roadmystery.app.vehicle.HazardReason
import com.roadmystery.app.vehicle.PlaceEventDetector
import com.roadmystery.app.vehicle.PlaceObservation
import com.roadmystery.app.vehicle.VehicleEvent
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** 가상 진입 + 가상 차량 제어 + 실제 TTS/효과음. 실제 차량 제어는 메인 앱에서 수행한다. */
class TunnelTestActivity : ComponentActivity() {
    private val cabin = FakeCabinPort()
    private lateinit var audio: PleosTtsAudioPlayer
    private lateinit var director: Director
    private lateinit var effectStatus: StateFlow<String>
    private var useSdk = false
    private lateinit var engine: DefaultStoryEngine
    private lateinit var detector: PlaceEventDetector
    private var inside by mutableStateOf(false)
    private var feedback by mutableStateOf("시연 시작 후 터널 진입을 눌러 주세요.")
    private var supported by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = PleosTtsAudioPlayer(this, lifecycleScope, BuildConfig.PLEOS_CLIENT_ID, BuildConfig.PLEOS_CLIENT_SECRET)
        useSdk = intent.getBooleanExtra("realCabin", false)
        supported = !intent.getBooleanExtra("unsupported", false)
        cabin.available = supported
        if (useSdk) {
            val real = PleosDirector(this)
            director = real
            effectStatus = real.status
        } else {
            val fake = TunnelDirector(cabin, ToneDirector())
            director = fake
            effectStatus = fake.status
        }
        engine = DefaultStoryEngine(AssetScenarioRepository(assets), audio, director = director, scope = lifecycleScope)
        engine.bind(FakeVehicleState(), lifecycleScope)
        detector = PlaceEventDetector {
            Log.i("F09Test", "event=$it")
            engine.onVehicleEvent(it)
        }
        lifecycleScope.launch { effectStatus.collect { Log.i("F09Test", "effect=$it sdk=$useSdk fakeBrightness=${cabin.brightness} fakeFan=${cabin.fan}") } }
        lifecycleScope.launch { audio.status.collect { Log.i("F09Test", "audio=$it") } }
        lifecycleScope.launch { engine.nowPlaying.collect { Log.i("F09Test", "scene=${it?.sceneId}") } }
        if (intent.getBooleanExtra("smoke", false)) {
            lifecycleScope.launch {
                try {
                    startDemo()
                    enter()
                    enter()
                    withTimeout(60_000) { engine.nowPlaying.first { it?.sceneId == "tunnel_flashback" } }
                    delay(7_000)
                    Log.i("F09Test", "SMOKE status=${effectStatus.value} sdk=$useSdk fakeBrightness=${cabin.brightness} fakeFan=${cabin.fan}")
                    withTimeout(60_000) { engine.nowPlaying.first { it != null && it.sceneId != "tunnel_flashback" } }
                    engine.stop()
                    Log.i("F09Test", "SMOKE complete")
                } catch (_: TimeoutCancellationException) {
                    Log.e("F09Test", "SMOKE timed out: ${audio.status.value}")
                    engine.stop()
                    feedback = "자동 점검 시간 초과 · 음성 서비스 상태를 확인한 뒤 시연을 다시 시작해 주세요."
                }
            }
        }
        setContent {
            val mode by engine.mode.collectAsState()
            val playing by engine.nowPlaying.collectAsState()
            val speech by audio.status.collectAsState()
            val effect by effectStatus.collectAsState()
            RoadMysteryTheme {
                Column(
                    Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("F-09 터널 연출 테스트", style = MaterialTheme.typography.headlineSmall)
                    Text("입력: 가상 터널 / 조명·공조: ${if (useSdk) "Pleos SDK" else "가상 차량"} / 음성·효과음: 실제 재생")
                    Text("터널: ${if (inside) "안" else "밖"} · 사건: $mode · ${playing?.title ?: "대기"}")
                    Text(speech.message)
                    Text(effect, style = MaterialTheme.typography.titleMedium)
                    if (!useSdk) Text("가상 밝기 ${cabin.brightness} / 송풍 ${cabin.fan}단계 (기본 70 / 2)")
                    Text(feedback, color = MaterialTheme.colorScheme.primary)
                    if (speech.phase == PlaybackPhase.ERROR) {
                        Button(onClick = { audio.retry() }) { Text("실패한 문장 재시도") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = ::startDemo) { Text("시연 시작 / 처음부터") }
                        Button(enabled = mode != AppMode.IDLE, onClick = {
                            engine.stop()
                            feedback = "시연 중단 · 진행 중인 연출을 복원합니다."
                        }) { Text("시연 중단·복원") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = mode != AppMode.IDLE, onClick = ::enter) { Text("터널 진입 (중복 시험 가능)") }
                        Button(enabled = inside, onClick = {
                            inside = false
                            detector.accept(PlaceObservation("demo-tunnel", "tunnel", false))
                            feedback = "터널 이탈 · 대기 중인 터널 장면 취소 / 실행 중인 연출 복원"
                        }) { Text("터널 이탈") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = mode != AppMode.IDLE, onClick = {
                            engine.onVehicleEvent(VehicleEvent.Hazard(HazardReason.HARD_BRAKE))
                            feedback = "위험 이벤트 · 낭독과 연출 중단. 단일 위험은 3초 뒤 낭독 재개."
                        }) { Text("위험 중단") }
                        Button(enabled = mode != AppMode.IDLE, onClick = {
                            engine.onVehicleEvent(VehicleEvent.Stopped)
                            feedback = "정차 전달 · 반복 위험으로 보류된 낭독 재개"
                        }) { Text("정차") }
                    }
                    Button(enabled = !useSdk && mode == AppMode.IDLE, onClick = {
                        supported = !supported
                        cabin.available = supported
                    }) { Text(if (supported) "가상 차량: 제어 지원 → 미지원으로" else "가상 차량: 제어 미지원 → 지원으로") }
                    Text("현재 본편 마지막 문장을 들은 뒤 터널 속 회상 3문장을 재생하고 본편으로 돌아갑니다.")
                    Text("지원: 밝기 낮춤·송풍 1단계 증가 후 4초 뒤 복원 / 미지원: 효과음만 재생")
                    Text("같은 터널 장면은 사건 1회당 한 번만 재생합니다. 다시 시험하려면 시연 시작을 누르세요.")
                    Text("GPS·지도 서비스는 아직 연결하지 않았습니다. 실제 위치 감지로 확인한 화면이 아닙니다.")
                }
            }
        }
    }

    private fun startDemo() {
        detector.reset()
        inside = false
        val scenario = AssetScenarioRepository(assets).load("midnight_rest_area")
        val chapter = scenario.chapters.first()
        val first = chapter.mainScenes.first()
        engine.start(
            scenario.id,
            Progress(
                scenarioId = scenario.id,
                chapterId = chapter.id,
                lastSceneId = first.id,
                lastSentenceIndex = first.script.lastIndex
            )
        )
        engine.onVehicleEvent(VehicleEvent.Driving)
        feedback = "본편 마지막 문장부터 시연합니다. 지금 터널 진입을 눌러 주세요."
    }

    private fun enter() {
        val repeated = inside
        inside = true
        detector.accept(PlaceObservation("demo-tunnel", "tunnel", true))
        feedback = if (repeated) "중복 진입 무시 · 장면을 추가하지 않습니다." else "터널 진입 전달 · 현재 장면이 끝나면 터널 장면 재생"
    }

    override fun onDestroy() {
        engine.stop()
        director.release()
        audio.release()
        super.onDestroy()
    }
}
