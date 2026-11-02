package com.roadmystery.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roadmystery.app.story.AppMode
import com.roadmystery.app.story.NowPlaying
import com.roadmystery.app.vehicle.HazardReason
import com.roadmystery.app.vehicle.VehicleEvent

/**
 * 시연·개발용 패널. 차량 이벤트를 보내고, 소리가 없는 동안 낭독 중인 문장을 자막으로 보여준다.
 * 운전자용 화면이 아니므로 실제 차량 빌드에서는 빼야 한다.
 */
@Composable
fun SimulationPanel(
    mode: AppMode,
    nowPlaying: NowPlaying?,
    fastAudio: Boolean,
    onStart: () -> Unit,
    onEvent: (VehicleEvent) -> Unit,
    onToggleSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("시뮬레이션 패널", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("현재 모드: ${mode.name}", style = MaterialTheme.typography.bodyMedium)

            PanelButton("사건 시작", onClick = onStart)
            HorizontalDivider()
            PanelButton("출발") { onEvent(VehicleEvent.Driving) }
            PanelButton("정차") { onEvent(VehicleEvent.Stopped) }
            PanelButton("터널") { onEvent(VehicleEvent.Place("tunnel")) }
            PanelButton("도착 1분 전") { onEvent(VehicleEvent.EtaChanged(remainingSec = 60)) }
            PanelButton("도착") { onEvent(VehicleEvent.Arrived) }
            PanelButton("급제동", danger = true) { onEvent(VehicleEvent.Hazard(HazardReason.HARD_BRAKE)) }
            PanelButton("\"멈춰\"", danger = true) { onEvent(VehicleEvent.Hazard(HazardReason.USER_STOP)) }
            HorizontalDivider()
            OutlinedButton(onClick = onToggleSpeed, modifier = Modifier.fillMaxWidth()) {
                Text(if (fastAudio) "낭독 속도 ×10" else "낭독 속도 ×1")
            }

            Text("자막", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            if (nowPlaying == null) {
                Text("(낭독 없음)", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "${nowPlaying.title} (${nowPlaying.sentenceIndex + 1}/${nowPlaying.sentenceCount})",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(nowPlaying.sentence.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }
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
