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

data class PanelState(
    val mode: AppMode,
    val nowPlaying: NowPlaying?,
    val fastAudio: Boolean,
    val canResume: Boolean,
    val autoplayStep: String?,
    /** 예: "Pleos TTS", "무음(TTS 초기화 실패)" */
    val audioLabel: String,
    val lastEffect: String?,
)

class PanelActions(
    val onStart: () -> Unit,
    val onResume: () -> Unit,
    val onAutoplay: () -> Unit,
    val onEvent: (VehicleEvent) -> Unit,
    val onToggleSpeed: () -> Unit,
)

/**
 * 시연·개발용 패널. 차량 이벤트를 보내고, 낭독 중인 문장을 자막으로 보여준다.
 * 운전자용 화면이 아니므로 실제 차량 빌드에서는 빼야 한다.
 */
@Composable
fun SimulationPanel(
    state: PanelState,
    actions: PanelActions,
    modifier: Modifier = Modifier,
) {
    val onEvent = actions.onEvent
    val autoplaying = state.autoplayStep != null
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("시뮬레이션 패널", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("모드: ${state.mode.name} · 음성: ${state.audioLabel}", style = MaterialTheme.typography.bodySmall)

            PanelButton("사건 시작", enabled = !autoplaying, onClick = actions.onStart)
            if (state.canResume) PanelButton("이어하기", enabled = !autoplaying, onClick = actions.onResume)
            PanelButton(if (autoplaying) "자동 재생 중…" else "시연 자동 재생", enabled = !autoplaying, onClick = actions.onAutoplay)
            state.autoplayStep?.let { Text("▶ $it", style = MaterialTheme.typography.labelMedium) }
            HorizontalDivider()
            PanelButton("출발") { onEvent(VehicleEvent.Driving) }
            PanelButton("정차") { onEvent(VehicleEvent.Stopped) }
            PanelButton("주차") { onEvent(VehicleEvent.Parked) }
            PanelButton("터널") { onEvent(VehicleEvent.Place("tunnel")) }
            PanelButton("도착 1분 전") { onEvent(VehicleEvent.EtaChanged(remainingSec = 60)) }
            PanelButton("도착") { onEvent(VehicleEvent.Arrived) }
            PanelButton("급제동", danger = true) { onEvent(VehicleEvent.Hazard(HazardReason.HARD_BRAKE)) }
            PanelButton("ADAS 경고", danger = true) { onEvent(VehicleEvent.Hazard(HazardReason.ADAS_WARNING)) }
            PanelButton("ADAS 지속 경고", danger = true) { onEvent(VehicleEvent.HazardState(HazardReason.ADAS_WARNING, true)) }
            PanelButton("ADAS 경고 해제") { onEvent(VehicleEvent.HazardState(HazardReason.ADAS_WARNING, false)) }
            PanelButton("\"멈춰\"", danger = true) { onEvent(VehicleEvent.Hazard(HazardReason.USER_STOP)) }
            HorizontalDivider()
            OutlinedButton(onClick = actions.onToggleSpeed, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.fastAudio) "무음 낭독 속도 ×10" else "무음 낭독 속도 ×1")
            }
            state.lastEffect?.let { Text("연출: $it", style = MaterialTheme.typography.labelMedium) }

            Text("자막", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            val playing = state.nowPlaying
            if (playing == null) {
                Text("(낭독 없음)", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "${playing.title} (${playing.sentenceIndex + 1}/${playing.sentenceCount})",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(playing.sentence.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PanelButton(
    label: String,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors =
            if (danger) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}
