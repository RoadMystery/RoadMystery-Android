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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roadmystery.app.story.Clue
import com.roadmystery.app.story.Interrogation
import com.roadmystery.app.story.Scenario
import com.roadmystery.app.story.Testimony

/** 정차 중: 단서 수첩 · 인물 관계도 · 심문. 출발하면 GameScreen이 이 화면을 바로 내린다. */
@Composable
fun StoppedScreen(
    scenario: Scenario?,
    clues: List<Clue>,
    newClueIds: Set<String>,
    testimonies: List<Testimony>,
    interrogation: Interrogation?,
    listening: Boolean,
    onAsk: (suspectId: String, question: String) -> Unit,
    onListen: (suspectId: String) -> Unit,
    onSummary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("단서 (${clues.size}/${scenario?.clues?.size ?: 0})", "인물 관계도", "심문")

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, label ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(label, style = MaterialTheme.typography.titleMedium) },
                )
            }
        }
        when (tab) {
            0 -> ClueList(clues = clues, newClueIds = newClueIds)
            1 -> if (scenario != null) RelationMap(scenario = scenario, testimonies = testimonies)
            2 ->
                if (scenario != null) {
                    InterrogationPanel(
                        scenario = scenario,
                        testimonies = testimonies,
                        interrogation = interrogation,
                        listening = listening,
                        onAsk = onAsk,
                        onListen = onListen,
                        onSummary = onSummary,
                    )
                }
        }
    }
}

@Composable
private fun ClueList(
    clues: List<Clue>,
    newClueIds: Set<String>,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(newClueIds) {
        val index = clues.indexOfFirst { it.id in newClueIds }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    if (clues.isEmpty()) {
        Text("아직 찾은 단서가 없습니다.", style = MaterialTheme.typography.titleMedium)
        return
    }
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        items(clues, key = { it.id }) { clue ->
            ClueCard(clue = clue, isNew = clue.id in newClueIds)
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
