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

/** 모드에 따라 화면을 고른다. 상태는 전달받아 그리기만 한다. */
@Composable
fun GameScreen(
    mode: AppMode,
    sceneTitle: String,
    unlockedClues: List<Clue>,
    newClueId: String?,
    totalClues: Int,
    place: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp)) {
        when (mode) {
            AppMode.IDLE, AppMode.BRIEFING ->
                CenterMessage(title = "RoadMystery", body = "출발하면 사건이 시작됩니다.")
            AppMode.DRIVING ->
                DrivingScreen(
                    sceneTitle = sceneTitle,
                    progress = if (totalClues == 0) 0f else unlockedClues.size.toFloat() / totalClues,
                    inTunnel = place == "tunnel",
                )
            AppMode.PAUSED ->
                CenterMessage(title = "일시정지", body = "안전을 위해 낭독을 멈췄습니다.")
            AppMode.STOPPED ->
                StoppedScreen(clues = unlockedClues, newClueId = newClueId, totalClues = totalClues)
            AppMode.ENDING ->
                CenterMessage(title = "목적지 도착", body = "이제 범인이 밝혀집니다.")
        }
    }
}

/** 주행 중: 장면 제목과 진행률만 보여준다. */
@Composable
private fun DrivingScreen(
    sceneTitle: String,
    progress: Float,
    inTunnel: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (inTunnel) "$sceneTitle · 터널" else sceneTitle,
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
private fun CenterMessage(
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.titleLarge)
    }
}
