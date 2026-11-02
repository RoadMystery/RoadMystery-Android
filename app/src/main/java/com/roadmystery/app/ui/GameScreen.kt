package com.roadmystery.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.roadmystery.app.story.AppMode
import com.roadmystery.app.story.Clue
import com.roadmystery.app.story.Interrogation
import com.roadmystery.app.story.NowPlaying
import com.roadmystery.app.story.Outcome
import com.roadmystery.app.story.Scenario
import com.roadmystery.app.story.Testimony

/** StoryEngine 상태를 화면용으로 모은 것. UI는 이걸 그리기만 한다. */
data class GameUiState(
    val mode: AppMode = AppMode.IDLE,
    val scenario: Scenario? = null,
    val nowPlaying: NowPlaying? = null,
    val progress: Float = 0f,
    val clues: List<Clue> = emptyList(),
    val pendingClues: List<Clue> = emptyList(),
    val outcome: Outcome? = null,
    val canInteract: Boolean = false,
    val testimonies: List<Testimony> = emptyList(),
    val interrogation: Interrogation? = null,
    val listening: Boolean = false,
)

class GameActions(
    val onAccuse: (suspectId: String) -> Unit,
    val onAsk: (suspectId: String, question: String) -> Unit,
    val onListen: (suspectId: String) -> Unit,
    val onSummary: () -> Unit,
)

/** 모드에 따라 화면을 고른다. 주행 중(BRIEFING·DRIVING·PAUSED)에는 제목·진행률·짧은 안내만 보인다. */
@Composable
fun GameScreen(
    state: GameUiState,
    actions: GameActions,
    modifier: Modifier = Modifier,
) {
    val chapterTitle = state.scenario?.chapters?.firstOrNull()?.title.orEmpty()
    Box(modifier = modifier.fillMaxSize().padding(32.dp)) {
        when (state.mode) {
            AppMode.IDLE ->
                CenterMessage(title = "RoadMystery", body = "시뮬레이션 패널에서 사건을 시작하세요.")
            AppMode.BRIEFING, AppMode.DRIVING ->
                DrivingScreen(title = state.nowPlaying?.title ?: chapterTitle, progress = state.progress)
            AppMode.PAUSED ->
                CenterMessage(title = "일시정지", body = "안전을 위해 낭독을 멈췄습니다.")
            AppMode.STOPPED ->
                StoppedScreen(
                    scenario = state.scenario,
                    clues = state.clues,
                    newClueIds = state.pendingClues.map { it.id }.toSet(),
                    testimonies = state.testimonies,
                    interrogation = state.interrogation,
                    listening = state.listening,
                    onAsk = actions.onAsk,
                    onListen = actions.onListen,
                    onSummary = actions.onSummary,
                )
            AppMode.ENDING ->
                EndingScreen(
                    suspects = state.scenario?.suspects.orEmpty(),
                    outcome = state.outcome,
                    canInteract = state.canInteract,
                    onAccuse = actions.onAccuse,
                )
        }
    }
}

/** 주행 중: 장면 제목과 진행률만 보여준다. */
@Composable
private fun DrivingScreen(
    title: String,
    progress: Float,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(0.6f),
        )
    }
}

@Composable
internal fun CenterMessage(
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    }
}
