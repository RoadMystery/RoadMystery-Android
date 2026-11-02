package com.roadmystery.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.roadmystery.app.story.Scenario
import com.roadmystery.app.story.Testimony

private val CardWidth = 240.dp

/** 피해자를 가운데 두고 용의자를 아래에 펼친 관계도. 선 위에 피해자와의 관계를 적는다. */
@Composable
fun RelationMap(
    scenario: Scenario,
    testimonies: List<Testimony>,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.outline
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = maxWidth
        val suspects = scenario.suspects
        val victimTop = 0.dp
        val suspectTop = maxHeight * 0.55f
        val slots = suspects.indices.map { i -> width * ((i + 0.5f) / suspects.size) }
        val density = LocalDensity.current

        Canvas(modifier = Modifier.fillMaxSize()) {
            val from = Offset(size.width / 2, with(density) { (victimTop + 70.dp).toPx() })
            slots.forEach { x ->
                drawLine(
                    color = lineColor,
                    start = from,
                    end = Offset(with(density) { x.toPx() }, with(density) { suspectTop.toPx() }),
                    strokeWidth = 4f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f)),
                )
            }
        }

        PersonCard(
            name = scenario.victim.name,
            role = scenario.victim.role,
            detail = "피해자",
            highlight = true,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = victimTop),
        )
        suspects.forEachIndexed { i, suspect ->
            val count = testimonies.count { it.suspectId == suspect.id }
            PersonCard(
                name = suspect.name,
                role = suspect.role,
                detail = suspect.relation + if (count > 0) "\n증언 ${count}개" else "",
                highlight = false,
                modifier = Modifier.offset(x = slots[i] - CardWidth / 2, y = suspectTop),
            )
        }
    }
}

@Composable
private fun PersonCard(
    name: String,
    role: String,
    detail: String,
    highlight: Boolean,
    modifier: Modifier = Modifier,
    width: Dp = CardWidth,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier.width(width),
        colors =
            CardDefaults.cardColors(
                containerColor = if (highlight) colors.tertiaryContainer else colors.secondaryContainer,
            ),
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(width),
            ) {
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(role, style = MaterialTheme.typography.labelLarge)
                Text(detail, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
    }
}
