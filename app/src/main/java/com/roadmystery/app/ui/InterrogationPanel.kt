package com.roadmystery.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roadmystery.app.story.Interrogation
import com.roadmystery.app.story.Scenario
import com.roadmystery.app.story.Testimony

/** 정차 중 심문: 용의자를 고르고 준비된 질문을 누르거나 음성으로 묻는다. */
@Composable
fun InterrogationPanel(
    scenario: Scenario,
    testimonies: List<Testimony>,
    interrogation: Interrogation?,
    listening: Boolean,
    onAsk: (suspectId: String, question: String) -> Unit,
    onListen: (suspectId: String) -> Unit,
    onSummary: () -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf(scenario.suspects.first().id) }
    val suspect = scenario.suspect(selectedId) ?: scenario.suspects.first()
    val busy = listening || (interrogation != null && interrogation.answer == null)

    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                scenario.suspects.forEach { s ->
                    if (s.id == selectedId) {
                        Button(onClick = {}) { Text(s.name) }
                    } else {
                        OutlinedButton(onClick = { selectedId = s.id }) { Text(s.name) }
                    }
                }
            }
            Text("${suspect.name} · ${suspect.role}", style = MaterialTheme.typography.titleMedium)
            suspect.interrogation.forEach { answer ->
                OutlinedButton(
                    onClick = { onAsk(suspect.id, answer.question) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(answer.question) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onListen(suspect.id) }, enabled = !busy) {
                    Text(if (listening) "듣는 중…" else "음성으로 묻기")
                }
                OutlinedButton(onClick = onSummary, enabled = !busy) { Text("수첩 요약 듣기") }
            }
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (interrogation != null) {
                val speaker = interrogation.suspectId?.let { scenario.suspect(it)?.name } ?: "수첩"
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Q. ${interrogation.question}", style = MaterialTheme.typography.labelLarge)
                        Text(
                            interrogation.answer?.let { "$speaker: $it" } ?: "$speaker 생각 중…",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            val history = testimonies.filter { it.suspectId == suspect.id }
            if (history.isNotEmpty()) {
                Text("${suspect.name}의 증언", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                history.asReversed().forEach {
                    Text("· ${it.answer}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
