package com.roadmystery.app

import ai.pleos.playground.vehicle.Vehicle
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.roadmystery.app.audio.AudioPlayer
import com.roadmystery.app.audio.FakeAudioPlayer
import com.roadmystery.app.audio.PlaybackStatus
import com.roadmystery.app.audio.PleosTtsAudioPlayer
import com.roadmystery.app.audio.SentenceAudioPlayer
import com.roadmystery.app.audio.SpeechOutput
import com.roadmystery.app.director.Director
import com.roadmystery.app.director.PleosDirector
import com.roadmystery.app.director.ToneDirector
import com.roadmystery.app.story.AppMode
import com.roadmystery.app.story.AssetScenarioRepository
import com.roadmystery.app.story.DataStoreProgressStore
import com.roadmystery.app.story.DefaultStoryEngine
import com.roadmystery.app.story.Progress
import com.roadmystery.app.story.Scene
import com.roadmystery.app.story.StoryEngine
import com.roadmystery.app.ui.GameActions
import com.roadmystery.app.ui.GameScreen
import com.roadmystery.app.ui.GameUiState
import com.roadmystery.app.ui.PanelActions
import com.roadmystery.app.ui.PanelState
import com.roadmystery.app.ui.SimulationPanel
import com.roadmystery.app.ui.theme.RoadMysteryTheme
import com.roadmystery.app.vehicle.AndroidTunnelLocation
import com.roadmystery.app.vehicle.DemoAutoplay
import com.roadmystery.app.vehicle.FakeVehicleState
import com.roadmystery.app.vehicle.PleosVehicleState
import com.roadmystery.app.vehicle.SelectedVehicleState
import com.roadmystery.app.vehicle.VehicleSource
import com.roadmystery.app.voice.PleosSpeechInput
import com.roadmystery.app.voice.ScriptedVoiceAgent
import com.roadmystery.app.voice.isStopCommand
import com.roadmystery.app.voice.SpeechInput
import com.roadmystery.app.voice.listenForDrivingQuestion
import com.roadmystery.app.voice.listenForQuestion
import com.roadmystery.app.voice.resolveDrivingQuestionSuspect
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

private const val DEMO_SCENARIO_ID = "midnight_rest_area"
private const val TAG = "MainActivity"

/**
 * 모듈을 조립한다. 차량 SDK가 없는 환경(일반 에뮬레이터·단위 테스트)에서도 Fake로 동작하도록
 * Pleos 구현은 모두 실패 시 대체 구현으로 넘어간다.
 */
class MainActivity : ComponentActivity() {
    private val simulated = FakeVehicleState()
    private lateinit var selectedVehicle: SelectedVehicleState
    private lateinit var tunnelLocation: AndroidTunnelLocation
    private val motionPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Re-subscribe after the permission result; missing permission stays conservatively Driving.
        tunnelLocation.refreshPermission()
        selectedVehicle.select(VehicleSource.REAL)
    }
    private var vehicleSdk: Vehicle? = null
    private lateinit var audio: AudioPlayer
    private var speech: SpeechInput? = null

    /** 정차 중 "음성으로 묻기"로 듣는 작업. 정차 화면을 벗어나면 끝낸다. */
    private var listenJob: Job? = null
    private lateinit var director: Director
    private lateinit var engine: DefaultStoryEngine
    private lateinit var store: DataStoreProgressStore
    private lateinit var autoplay: DemoAutoplay
    private val savedProgress = MutableStateFlow<Progress?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clientId = BuildConfig.PLEOS_CLIENT_ID
        val clientSecret = BuildConfig.PLEOS_CLIENT_SECRET

        vehicleSdk = attempt("Vehicle SDK") { Vehicle(this).apply { initialize() } }
        tunnelLocation = AndroidTunnelLocation(this, lifecycle)
        selectedVehicle = SelectedVehicleState(
            real = merge(PleosVehicleState(this).events, tunnelLocation.events),
            simulated = simulated.events,
            initial = if (BuildConfig.DEBUG) VehicleSource.SIMULATION else VehicleSource.REAL
        )

        audio = attempt("TTS") { PleosTtsAudioPlayer(this, lifecycleScope, clientId, clientSecret) }
            ?: SentenceAudioPlayer(
                lifecycleScope,
                object : SpeechOutput {
                    override suspend fun speak(text: String, speaker: String) {
                        error("TTS를 시작하지 못했습니다. 앱을 다시 실행해 주세요.")
                    }
                    override fun stop() = Unit
                    override fun release() = Unit
                }
            )
        speech = attempt("STT") { PleosSpeechInput(this, clientId, clientSecret) }
        director = attempt("Director") { PleosDirector(this) } ?: ToneDirector()
        store = DataStoreProgressStore(this)
        engine =
            DefaultStoryEngine(
                repository = AssetScenarioRepository(assets),
                audio = audio,
                voice = ScriptedVoiceAgent(),
                director = director,
                scope = lifecycleScope
            )
        val questionWindow: suspend (Scene) -> Boolean = { handleDrivingQuestion(it.id) }
        when (val player = audio) {
            is PleosTtsAudioPlayer -> player.onQuestionWindow = questionWindow
            is SentenceAudioPlayer -> player.onQuestionWindow = questionWindow
        }
        engine.bind(selectedVehicle, lifecycleScope)
        autoplay = DemoAutoplay(simulated, engine)

        lifecycleScope.launch { savedProgress.value = store.load(DEMO_SCENARIO_ID) }
        lifecycleScope.launch {
            // 문장이 바뀔 때마다 바로 저장한다. 저장이 느리면 StateFlow가 중간 값을 건너뛰고 최신 값만 저장한다.
            engine.snapshot.filterNotNull().collect { progress ->
                runCatching {
                    if (engine.outcome.value != null) {
                        store.clear(progress.scenarioId)
                        savedProgress.value = null
                    } else {
                        store.save(progress)
                        savedProgress.value = progress
                    }
                }.onFailure { Log.w(TAG, "진행 상태 저장 실패: ${it.message}") }
            }
        }

        enableEdgeToEdge()
        setContent { RoadMysteryTheme { AppContent() } }
    }

    @androidx.compose.runtime.Composable
    private fun AppContent() {
        val source by selectedVehicle.source.collectAsState()
        val gpsStatus by tunnelLocation.status.collectAsState()
        val mode by engine.mode.collectAsState()
        val scenario by engine.scenario.collectAsState()
        val nowPlaying by engine.nowPlaying.collectAsState()
        val progress by engine.progress.collectAsState()
        val clues by engine.clues.collectAsState()
        val pendingClues by engine.pendingClues.collectAsState()
        val outcome by engine.outcome.collectAsState()
        val canInteract by engine.canInteract.collectAsState()
        val testimonies by engine.testimonies.collectAsState()
        val interrogation by engine.interrogation.collectAsState()
        val saved by savedProgress.collectAsState()
        val autoplayStep by autoplay.step.collectAsState()
        val lastEffect by director.lastEffect.collectAsState()
        val effectStatus by ((director as? PleosDirector)?.status ?: MutableStateFlow("효과음 전용")).collectAsState()
        val audioStatus by (
            when (val player = audio) {
                is PleosTtsAudioPlayer -> player.status
                is SentenceAudioPlayer -> player.status
                else -> MutableStateFlow(PlaybackStatus(message = "무음 시뮬레이션"))
            }
            ).collectAsState()
        var fastAudio by remember { mutableStateOf(false) }
        var listening by remember { mutableStateOf(false) }
        LaunchedEffect(mode) { if (mode != AppMode.STOPPED) listenJob?.cancel() }

        val gameState =
            GameUiState(
                mode = mode,
                scenario = scenario,
                nowPlaying = nowPlaying,
                progress = progress,
                clues = clues,
                pendingClues = pendingClues,
                outcome = outcome,
                canInteract = canInteract,
                testimonies = testimonies,
                interrogation = interrogation,
                listening = listening
            )
        val gameActions =
            GameActions(
                onAccuse = { engine.choose(StoryEngine.DECISION_ACCUSE, it) },
                onAsk = { suspectId, question -> handleUtterance(suspectId, question) },
                onListen = { suspectId ->
                    listenJob?.cancel()
                    listenJob =
                        lifecycleScope.launch {
                            listening = true
                            // 마이크가 낭독을 듣지 않게 듣는 동안 낭독을 멈춘다. 말이 없으면 정해 둔 시간 뒤에 끝난다.
                            engine.beginListening()
                            try {
                                val text = speech?.listenForQuestion()
                                if (text != null) handleUtterance(suspectId, text)
                            } finally {
                                listening = false
                                engine.endListening()
                            }
                        }
                },
                onSummary = { engine.requestSummary() }
            )
        val panelState =
            PanelState(
                mode = mode,
                nowPlaying = nowPlaying,
                fastAudio = fastAudio,
                canResume = saved != null,
                autoplayStep = autoplayStep,
                audioLabel =
                audioStatus.message,
                lastEffect = lastEffect?.let { "${it.label} · $effectStatus" }
            )
        val panelActions =
            PanelActions(
                onStart = { engine.start(DEMO_SCENARIO_ID) },
                onResume = { engine.start(DEMO_SCENARIO_ID, resume = savedProgress.value) },
                onAutoplay = { lifecycleScope.launch { autoplay.run(DEMO_SCENARIO_ID) } },
                onEvent = simulated::send,
                onToggleSpeed = {
                    fastAudio = !fastAudio
                    val speed = if (fastAudio) 10f else 1f
                    when (val player = audio) {
                        is PleosTtsAudioPlayer -> player.retry()
                        is SentenceAudioPlayer -> player.retry()
                        is FakeAudioPlayer -> player.speed = speed
                    }
                }
            )

        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (BuildConfig.DEBUG && (source == VehicleSource.SIMULATION || canInteract)) {
                    Column(Modifier.width(280.dp).fillMaxHeight()) {
                        Button(onClick = {
                            if (source == VehicleSource.REAL) {
                                selectedVehicle.select(VehicleSource.SIMULATION)
                            } else {
                                motionPermissions.launch(
                                    arrayOf(
                                        "android.car.permission.CAR_SPEED",
                                        "android.car.permission.CAR_POWERTRAIN",
                                        "android.permission.ACCESS_COARSE_LOCATION",
                                        "android.permission.ACCESS_FINE_LOCATION"
                                    )
                                )
                            }
                        }) { Text(if (source == VehicleSource.REAL) "실제 입력 → 시뮬레이션" else "시뮬레이션 → 실제 입력") }
                        Button(onClick = {
                            startActivity(Intent().setClassName(packageName, "$packageName.vehicle.MotionTestActivity"))
                        }) { Text("F-01 단독 판정 테스트") }
                        Button(onClick = {
                            engine.stop()
                            (audio as? PleosTtsAudioPlayer)?.disconnect()
                            startActivity(Intent().setClassName(packageName, "$packageName.audio.NarrationTestActivity"))
                        }) { Text("F-03 낭독 테스트") }
                        Button(onClick = {
                            engine.stop()
                            (audio as? PleosTtsAudioPlayer)?.disconnect()
                            startActivity(Intent().setClassName(packageName, "$packageName.director.TunnelTestActivity"))
                        }) { Text("F-09 터널 연출 테스트") }
                        Button(onClick = {
                            engine.stop()
                            (audio as? PleosTtsAudioPlayer)?.disconnect()
                            startActivity(Intent().setClassName(packageName, "$packageName.director.GpsTunnelTestActivity"))
                        }) { Text("F-09 GPS 터널 자동 감지 테스트") }
                        Text(gpsStatus)
                        Text("터널 지도: © OpenStreetMap contributors · ODbL 1.0")
                        if (source == VehicleSource.SIMULATION) {
                            SimulationPanel(state = panelState, actions = panelActions, modifier = Modifier.weight(1f))
                        }
                    }
                }
                VerticalDivider()
                GameScreen(state = gameState, actions = gameActions, modifier = Modifier.weight(1f))
            }
        }
    }

    private suspend fun handleDrivingQuestion(sceneId: String): Boolean {
        if (!canListenForDrivingQuestion(sceneId)) return false
        val question = speech?.listenForDrivingQuestion()?.trim().orEmpty()
        if (question.isBlank() || !canListenForDrivingQuestion(sceneId)) return true
        val suspect = resolveDrivingQuestionSuspect(
            question,
            engine.scenario.value?.suspects.orEmpty(),
            engine.testimonies.value.lastOrNull()?.suspectId
        ) ?: return true
        engine.askBetweenScenes(suspect, question)
        return true
    }

    private fun canListenForDrivingQuestion(sceneId: String): Boolean {
        val foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        return foreground && engine.mode.value == AppMode.DRIVING && engine.nowPlaying.value?.sceneId == sceneId
    }

    override fun onPause() {
        listenJob?.cancel()
        speech?.cancel()
        super.onPause()
    }

    private fun handleUtterance(suspectId: String, text: String) {
        if (isStopCommand(text)) {
            engine.onVehicleEvent(com.roadmystery.app.vehicle.VehicleEvent.Hazard(com.roadmystery.app.vehicle.HazardReason.USER_STOP))
        } else {
            engine.ask(suspectId, text)
        }
    }

    override fun onDestroy() {
        engine.stop()
        director.release()
        when (val player = audio) {
            is PleosTtsAudioPlayer -> player.release()
            is SentenceAudioPlayer -> player.release()
            else -> player.stop()
        }
        (speech as? PleosSpeechInput)?.release()
        runCatching { vehicleSdk?.release() }
        super.onDestroy()
    }

    private fun <T> attempt(
        name: String,
        block: () -> T
    ): T? = runCatching(block)
        .onFailure { Log.w(TAG, "$name 사용 불가, 대체 구현으로 진행: ${it.message}") }
        .getOrNull()
}
