package com.roadmystery.app

import android.os.Bundle
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
import com.roadmystery.app.audio.FakeAudioPlayer
import com.roadmystery.app.story.AssetScenarioRepository
import com.roadmystery.app.story.DefaultStoryEngine
import com.roadmystery.app.story.StoryEngine
import com.roadmystery.app.ui.GameScreen
import com.roadmystery.app.ui.SimulationPanel
import com.roadmystery.app.ui.theme.RoadMysteryTheme
import com.roadmystery.app.vehicle.FakeVehicleState

private const val DEMO_SCENARIO_ID = "midnight_rest_area"

class MainActivity : ComponentActivity() {
    private val vehicle = FakeVehicleState()
    private lateinit var audio: FakeAudioPlayer
    private lateinit var engine: DefaultStoryEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = FakeAudioPlayer(lifecycleScope)
        engine = DefaultStoryEngine(AssetScenarioRepository(assets), audio)
        engine.bind(vehicle, lifecycleScope)

        enableEdgeToEdge()
        setContent {
            RoadMysteryTheme {
                val mode by engine.mode.collectAsState()
                val scenario by engine.scenario.collectAsState()
                val nowPlaying by engine.nowPlaying.collectAsState()
                val progress by engine.progress.collectAsState()
                val clues by engine.clues.collectAsState()
                val pendingClues by engine.pendingClues.collectAsState()
                val outcome by engine.outcome.collectAsState()
                var fastAudio by remember { mutableStateOf(false) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        SimulationPanel(
                            mode = mode,
                            nowPlaying = nowPlaying,
                            fastAudio = fastAudio,
                            onStart = { engine.start(DEMO_SCENARIO_ID) },
                            onEvent = vehicle::send,
                            onToggleSpeed = {
                                fastAudio = !fastAudio
                                audio.speed = if (fastAudio) 10f else 1f
                            },
                            modifier = Modifier.width(280.dp).fillMaxHeight(),
                        )
                        VerticalDivider()
                        GameScreen(
                            mode = mode,
                            scenario = scenario,
                            nowPlaying = nowPlaying,
                            progress = progress,
                            clues = clues,
                            pendingClues = pendingClues,
                            outcome = outcome,
                            onAccuse = { engine.choose(StoryEngine.DECISION_ACCUSE, it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
