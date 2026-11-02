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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.roadmystery.app.story.GameState
import com.roadmystery.app.ui.GameScreen
import com.roadmystery.app.ui.SimulationPanel
import com.roadmystery.app.ui.theme.RoadMysteryTheme
import com.roadmystery.app.vehicle.FakeVehicleState

class MainActivity : ComponentActivity() {
    private val vehicle = FakeVehicleState()
    private val game = GameState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        game.bind(vehicle, lifecycleScope)

        enableEdgeToEdge()
        setContent {
            RoadMysteryTheme {
                val mode by game.mode.collectAsState()
                val clues by game.unlockedClues.collectAsState()
                val newClueId by game.newClueId.collectAsState()
                val place by game.place.collectAsState()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        SimulationPanel(
                            mode = mode,
                            onEvent = vehicle::send,
                            modifier = Modifier.width(280.dp).fillMaxHeight(),
                        )
                        VerticalDivider()
                        GameScreen(
                            mode = mode,
                            sceneTitle = game.sceneTitle,
                            unlockedClues = clues,
                            newClueId = newClueId,
                            totalClues = game.totalClues,
                            place = place,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
