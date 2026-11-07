package com.roadmystery.app.vehicle

import kotlinx.serialization.Serializable

/** 실제 내비/지도 어댑터와 가상 입력이 공유하는 판정 경계. null은 판정 불가다. */
data class PlaceObservation(val regionId: String, val tag: String, val inside: Boolean?)

class PlaceEventDetector(private val emit: (VehicleEvent) -> Unit) {
    private val inside = mutableMapOf<String, String>()

    fun accept(observation: PlaceObservation) {
        if (observation.inside == null) return
        require(observation.regionId.isNotBlank() && observation.tag.isNotBlank())
        if (observation.inside) {
            if (inside.containsKey(observation.regionId)) return
            val alreadyInTag = observation.tag in inside.values
            inside[observation.regionId] = observation.tag
            if (!alreadyInTag) emit(VehicleEvent.Place(observation.tag))
        } else {
            val tag = inside.remove(observation.regionId) ?: return
            if (tag !in inside.values) emit(VehicleEvent.PlaceExited(tag))
        }
    }

    fun reset() {
        inside.clear()
    }
}

/** 지도에서 얻은 구간 폴리곤을 주입한다. 이 클래스에는 실제 터널 좌표가 내장돼 있지 않다. */
@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double)

@Serializable
data class PlaceRegion(val id: String, val tag: String, val polygon: List<GeoPoint>)
data class PositionFix(val point: GeoPoint, val accuracyMeters: Double, val ageMs: Long)

/** 정확한 최신 위치 2회가 같은 결과일 때만 전환. GPS 유실·경계 오차는 상태를 유지한다. */
class MappedPlaceDetector(
    private val regions: List<PlaceRegion>,
    private val target: PlaceEventDetector
) {
    private val candidates = mutableMapOf<String, Boolean>()

    init {
        require(regions.map { it.id }.distinct().size == regions.size)
        require(regions.all { it.polygon.size >= 3 && it.polygon.all(::valid) })
    }

    fun update(fix: PositionFix?) {
        if (fix == null ||
            !valid(fix.point) ||
            !fix.accuracyMeters.isFinite() ||
            fix.accuracyMeters !in 0.0..30.0 ||
            fix.ageMs !in 0..5_000
        ) {
            candidates.clear()
            return
        }
        for (region in regions) {
            val result = contains(region.polygon, fix.point)
            // 폴리곤 경계까지의 거리가 위치 오차보다 작으면 진입/이탈을 확정하지 않는다.
            if (nearBoundary(region.polygon, fix.point, fix.accuracyMeters.coerceAtLeast(5.0))) {
                candidates.remove(region.id)
                continue
            }
            if (candidates.put(region.id, result) == result) {
                target.accept(PlaceObservation(region.id, region.tag, result))
            }
        }
    }

    private fun contains(polygon: List<GeoPoint>, point: GeoPoint): Boolean {
        var inside = false
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            if ((a.latitude > point.latitude) != (b.latitude > point.latitude) &&
                point.longitude < (b.longitude - a.longitude) * (point.latitude - a.latitude) /
                (b.latitude - a.latitude) + a.longitude
            ) {
                inside = !inside
            }
        }
        return inside
    }

    private fun nearBoundary(polygon: List<GeoPoint>, point: GeoPoint, margin: Double): Boolean {
        val xScale = 111_320.0 * kotlin.math.cos(Math.toRadians(point.latitude))
        return polygon.indices.any { i ->
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val ax = (a.longitude - point.longitude) * xScale
            val ay = (a.latitude - point.latitude) * 111_320.0
            val bx = (b.longitude - point.longitude) * xScale
            val by = (b.latitude - point.latitude) * 111_320.0
            val dx = bx - ax
            val dy = by - ay
            val length = dx * dx + dy * dy
            val t = if (length == 0.0) 0.0 else (-(ax * dx + ay * dy) / length).coerceIn(0.0, 1.0)
            kotlin.math.hypot(ax + t * dx, ay + t * dy) <= margin
        }
    }

    private fun valid(point: GeoPoint) = point.latitude.isFinite() &&
        point.longitude.isFinite() &&
        point.latitude in -90.0..90.0 &&
        point.longitude in -180.0..180.0
}
