package com.roadmystery.app.vehicle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 내려받은 한 개 터널의 지도 스냅샷. 전국 터널 검색이나 내비 경로 매칭은 아니다. */
@Serializable
data class TunnelMap(
    val name: String,
    val source: String,
    val attribution: String,
    val license: String,
    val regions: List<PlaceRegion>
) {
    companion object {
        const val ASSET = "maps/namsan3_tunnel.json"
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): TunnelMap = json.decodeFromString<TunnelMap>(text).also {
            require(it.regions.isNotEmpty())
            require(it.regions.all { region -> region.tag == "tunnel" })
            // 공통 판정기의 형상 검증도 통과해야 위치 수신을 시작한다.
            MappedPlaceDetector(it.regions, PlaceEventDetector {})
        }
    }
}
