package com.roadmystery.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roadmystery.app.story.Outcome
import com.roadmystery.app.story.Suspect

/** 도착 후: 범인 지목, 지목한 뒤에는 결과. */
@Composable
fun EndingScreen(
    suspects: List<Suspect>,
    outcome: Outcome?,
    onAccuse: (suspectId: String) -> Unit,
) {
    if (outcome != null) {
        val culprit = suspects.firstOrNull { it.id == outcome.culpritId }?.name.orEmpty()
        CenterMessage(
            title = if (outcome.correct) "정답입니다" else "범인이 아닙니다",
            body = "범인은 ${culprit}입니다.",
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("목적지에 도착했습니다", style = MaterialTheme.typography.headlineMedium)
        Text("범인은 누구입니까?", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            suspects.forEach { suspect ->
                ElevatedButton(
                    onClick = { onAccuse(suspect.id) },
                    modifier = Modifier.width(200.dp).height(120.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(suspect.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(suspect.role, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
