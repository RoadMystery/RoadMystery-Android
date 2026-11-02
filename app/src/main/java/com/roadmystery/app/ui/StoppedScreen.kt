package com.roadmystery.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roadmystery.app.story.Clue

/** 정차 중: 지금까지 모은 단서 카드를 가로로 보여주고, 이번에 열린 카드를 강조한다. */
@Composable
fun StoppedScreen(
    clues: List<Clue>,
    newClueIds: Set<String>,
    totalClues: Int,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(newClueIds) {
        val index = clues.indexOfFirst { it.id in newClueIds }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            "단서 수첩 (${clues.size}/$totalClues)",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        if (clues.isEmpty()) {
            Text("아직 찾은 단서가 없습니다.", style = MaterialTheme.typography.titleMedium)
        } else {
            LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(clues, key = { it.id }) { clue ->
                    ClueCard(clue = clue, isNew = clue.id in newClueIds)
                }
            }
        }
    }
}

@Composable
private fun ClueCard(
    clue: Clue,
    isNew: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.width(300.dp).height(240.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = if (isNew) colors.primaryContainer else colors.surfaceVariant,
            ),
        border = if (isNew) BorderStroke(3.dp, colors.primary) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = if (isNew) 8.dp else 1.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (isNew) {
                Text(
                    "새 단서",
                    color = colors.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(clue.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(clue.description, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
