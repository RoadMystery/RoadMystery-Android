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
import com.roadmystery.app.story.NowPlaying
import com.roadmystery.app.story.Outcome
import com.roadmystery.app.story.Scenario

/** 모드에 따라 화면을 고른다. 상태는 전달받아 그리기만 한다. */
@Composable
fun GameScreen(
    mode: AppMode,
    scenario: Scenario?,
    nowPlaying: NowPlaying?,
    progress: Float,
    clues: List<Clue>,
    pendingClues: List<Clue>,
    outcome: Outcome?,
    onAccuse: (suspectId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chapterTitle = scenario?.chapters?.firstOrNull()?.title.orEmpty()
    Box(modifier = modifier.fillMaxSize().padding(32.dp)) {
        when (mode) {
            AppMode.IDLE ->
                CenterMessage(title = "RoadMystery", body = "시뮬레이션 패널에서 사건을 시작하세요.")
            AppMode.BRIEFING, AppMode.DRIVING ->
                DrivingScreen(title = nowPlaying?.title ?: chapterTitle, progress = progress)
            AppMode.PAUSED ->
                CenterMessage(title = "일시정지", body = "안전을 위해 낭독을 멈췄습니다.")
            AppMode.STOPPED ->
                StoppedScreen(
                    clues = clues,
                    newClueIds = pendingClues.map { it.id }.toSet(),
                    totalClues = scenario?.clues?.size ?: 0,
                )
            AppMode.ENDING ->
                EndingScreen(
                    suspects = scenario?.suspects.orEmpty(),
                    outcome = outcome,
                    onAccuse = onAccuse,
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
