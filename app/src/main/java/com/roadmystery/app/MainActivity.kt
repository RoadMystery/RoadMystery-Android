package com.roadmystery.app

import ai.pleos.playground.vehicle.Vehicle
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.roadmystery.app.audio.AudioPlayer
import com.roadmystery.app.audio.FakeAudioPlayer
import com.roadmystery.app.audio.PleosTtsAudioPlayer
import com.roadmystery.app.director.Director
import com.roadmystery.app.director.PleosDirector
import com.roadmystery.app.director.ToneDirector
import com.roadmystery.app.story.AssetScenarioRepository
import com.roadmystery.app.story.DataStoreProgressStore
import com.roadmystery.app.story.DefaultStoryEngine
import com.roadmystery.app.story.Progress
import com.roadmystery.app.story.StoryEngine
import com.roadmystery.app.ui.GameActions
import com.roadmystery.app.ui.GameScreen
import com.roadmystery.app.ui.GameUiState
import com.roadmystery.app.ui.PanelActions
import com.roadmystery.app.ui.PanelState
import com.roadmystery.app.ui.SimulationPanel
import com.roadmystery.app.ui.theme.RoadMysteryTheme
import com.roadmystery.app.vehicle.DemoAutoplay
import com.roadmystery.app.vehicle.FakeVehicleState
import com.roadmystery.app.vehicle.PleosVehicleState
import com.roadmystery.app.vehicle.VehicleEvent
import com.roadmystery.app.vehicle.VehicleState
import com.roadmystery.app.voice.PleosSpeechInput
import com.roadmystery.app.voice.ScriptedVoiceAgent
import com.roadmystery.app.voice.isStopCommand
import com.roadmystery.app.voice.SpeechInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private var vehicleSdk: Vehicle? = null
    private lateinit var audio: AudioPlayer
    private var speech: SpeechInput? = null
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
        val realVehicle: Flow<VehicleEvent> =
            vehicleSdk?.let { sdk -> attempt("PleosVehicleState") { PleosVehicleState(this, sdk).events } } ?: emptyFlow()
        val vehicle =
            object : VehicleState {
                // 실제 차량 신호와 시뮬레이션 패널 입력을 함께 받는다. 시연 중에는 패널이 마지막 입력을 덮어쓴다.
                override val events = merge(simulated.events, realVehicle)
            }

        audio = attempt("TTS") { PleosTtsAudioPlayer(this, lifecycleScope, clientId, clientSecret) } ?: FakeAudioPlayer(lifecycleScope)
        speech = attempt("STT") { PleosSpeechInput(this, clientId, clientSecret) }
        director = vehicleSdk?.let { sdk -> attempt("Director") { PleosDirector(sdk) } } ?: ToneDirector()
        store = DataStoreProgressStore(this)
        engine =
            DefaultStoryEngine(
                repository = AssetScenarioRepository(assets),
                audio = audio,
                voice = ScriptedVoiceAgent(),
                director = director,
                scope = lifecycleScope,
            )
        engine.bind(vehicle, lifecycleScope)
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
        val ttsReady by ((audio as? PleosTtsAudioPlayer)?.ttsReady ?: MutableStateFlow(false)).collectAsState()
        var fastAudio by remember { mutableStateOf(false) }
        var listening by remember { mutableStateOf(false) }

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
                listening = listening,
            )
        val gameActions =
            GameActions(
                onAccuse = { engine.choose(StoryEngine.DECISION_ACCUSE, it) },
                onAsk = { suspectId, question -> handleUtterance(suspectId, question) },
                onListen = { suspectId ->
                    lifecycleScope.launch {
                        listening = true
                        val text = speech?.listen()
                        listening = false
                        if (text != null) handleUtterance(suspectId, text)
                    }
                },
                onSummary = { engine.requestSummary() },
            )
        val panelState =
            PanelState(
                mode = mode,
                nowPlaying = nowPlaying,
                fastAudio = fastAudio,
                canResume = saved != null,
                autoplayStep = autoplayStep,
                audioLabel =
                    when (ttsReady) {
                        true -> "Pleos TTS"
                        null -> "TTS 준비 중"
                        false -> "무음 + 자막"
                    },
                lastEffect = lastEffect?.label,
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
                        is PleosTtsAudioPlayer -> player.fallbackSpeed = speed
                        is FakeAudioPlayer -> player.speed = speed
                    }
                },
            )

        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                SimulationPanel(
                    state = panelState,
                    actions = panelActions,
                    modifier = Modifier.width(280.dp).fillMaxHeight(),
                )
                VerticalDivider()
                GameScreen(state = gameState, actions = gameActions, modifier = Modifier.weight(1f))
            }
        }
    }

    private fun handleUtterance(suspectId: String, text: String) {
        if (isStopCommand(text)) {
            engine.onVehicleEvent(com.roadmystery.app.vehicle.VehicleEvent.Hazard(com.roadmystery.app.vehicle.HazardReason.USER_STOP))
        } else {
            engine.ask(suspectId, text)
        }
    }

    override fun onDestroy() {
        (audio as? PleosTtsAudioPlayer)?.release() ?: audio.stop()
        (speech as? PleosSpeechInput)?.release()
        runCatching { vehicleSdk?.release() }
        super.onDestroy()
    }

    private fun <T> attempt(
        name: String,
        block: () -> T,
    ): T? =
        runCatching(block)
            .onFailure { Log.w(TAG, "$name 사용 불가, 대체 구현으로 진행: ${it.message}") }
            .getOrNull()
}
