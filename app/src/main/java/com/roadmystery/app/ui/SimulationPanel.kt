package com.roadmystery.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roadmystery.app.story.AppMode
import com.roadmystery.app.vehicle.HazardReason
import com.roadmystery.app.vehicle.VehicleEvent

/** 시연·개발용 패널. 버튼은 차량 이벤트를 보낼 뿐 게임 상태를 직접 바꾸지 않는다. */
@Composable
fun SimulationPanel(
    mode: AppMode,
    onEvent: (VehicleEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("시뮬레이션 패널", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("현재 모드: ${mode.name}", style = MaterialTheme.typography.bodyMedium)

            PanelButton("출발") { onEvent(VehicleEvent.Driving) }
            PanelButton("정차") { onEvent(VehicleEvent.Stopped) }
            PanelButton("터널") { onEvent(VehicleEvent.Place("tunnel")) }
            PanelButton("급제동", danger = true) { onEvent(VehicleEvent.Hazard(HazardReason.HARD_BRAKE)) }
            PanelButton("도착") { onEvent(VehicleEvent.Arrived) }
        }
    }
}

@Composable
private fun PanelButton(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors =
            if (danger) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
