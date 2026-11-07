package com.roadmystery.app.director

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.roadmystery.app.BuildConfig
import com.roadmystery.app.audio.PlaybackPhase
import com.roadmystery.app.audio.PleosTtsAudioPlayer
import com.roadmystery.app.story.AssetScenarioRepository
import com.roadmystery.app.story.DefaultStoryEngine
import com.roadmystery.app.story.Progress
import com.roadmystery.app.ui.theme.RoadMysteryTheme
import com.roadmystery.app.vehicle.AndroidTunnelLocation
import com.roadmystery.app.vehicle.FakeVehicleState
import com.roadmystery.app.vehicle.VehicleEvent
import kotlinx.coroutines.launch

/** GPS는 OS 수신 경로, 주행 상태만 시연용 Driving. 터널 이벤트를 직접 넣는 버튼은 없다. */
class GpsTunnelTestActivity : ComponentActivity() {
    private lateinit var location: AndroidTunnelLocation
    private lateinit var audio: PleosTtsAudioPlayer
    private lateinit var director: PleosDirector
    private lateinit var engine: DefaultStoryEngine
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        location.refreshPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = AndroidTunnelLocation(this, lifecycle)
        audio = PleosTtsAudioPlayer(this, lifecycleScope, BuildConfig.PLEOS_CLIENT_ID, BuildConfig.PLEOS_CLIENT_SECRET)
        director = PleosDirector(this)
        engine = DefaultStoryEngine(AssetScenarioRepository(assets), audio, director = director, scope = lifecycleScope)
        engine.bind(FakeVehicleState(), lifecycleScope)
        lifecycleScope.launch {
            location.events.collect {
                Log.i("F09GpsTest", "LOCATION_EVENT $it")
                engine.onVehicleEvent(it)
            }
        }
        lifecycleScope.launch { location.lastFix.collect { Log.i("F09GpsTest", "GPS_FIX $it") } }
        lifecycleScope.launch { location.status.collect { Log.i("F09GpsTest", "GPS_STATUS $it") } }
        lifecycleScope.launch { engine.nowPlaying.collect { Log.i("F09GpsTest", "SCENE ${it?.sceneId}") } }
        lifecycleScope.launch { director.status.collect { Log.i("F09GpsTest", "EFFECT $it") } }
        lifecycleScope.launch { audio.status.collect { Log.i("F09GpsTest", "AUDIO $it") } }
        setContent {
            val gps by location.status.collectAsState()
            val fix by location.lastFix.collectAsState()
            val scene by engine.nowPlaying.collectAsState()
            val effect by director.status.collectAsState()
            val speech by audio.status.collectAsState()
            RoadMysteryTheme {
                Column(
                    Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("F-09 GPS 터널 자동 감지 · 남산3호터널 지도 구간")
                    Text("위치: Android GPS 수신 / 주행 상태: 시연용 Driving / 연출: Pleos SDK")
                    Text(gps)
                    Text("최근 위치: ${fix?.point} · 오차 ${fix?.accuracyMeters}m")
                    Text("장면: ${scene?.title ?: "대기"}")
                    Text(speech.message)
                    Text(effect)
                    Button(onClick = { permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) }) {
                        Text("정확한 위치 권한 허용")
                    }
                    Button(onClick = ::startStory) { Text("사건 다시 시작") }
                    if (speech.phase == PlaybackPhase.ERROR) Button(onClick = { audio.retry() }) { Text("음성 재시도") }
                    Button(onClick = { engine.stop() }) { Text("중단·연출 복원") }
                    Text("터널 진입 버튼 없음: 에뮬레이터 GPS 좌표를 밖 → 안 → 밖으로 이동하세요.")
                    Text("지도: © OpenStreetMap contributors · ODbL 1.0\nhttps://www.openstreetmap.org/copyright")
                }
            }
        }
        startStory()
    }

    private fun startStory() {
        val scenario = AssetScenarioRepository(assets).load("midnight_rest_area")
        val chapter = scenario.chapters.first()
        val scene = chapter.mainScenes.first()
        engine.start(scenario.id, Progress(scenarioId = scenario.id, chapterId = chapter.id, lastSceneId = scene.id, lastSentenceIndex = scene.script.lastIndex))
        engine.onVehicleEvent(VehicleEvent.Driving)
    }

    override fun onDestroy() {
        engine.stop()
        director.release()
        audio.release()
        super.onDestroy()
    }
}
