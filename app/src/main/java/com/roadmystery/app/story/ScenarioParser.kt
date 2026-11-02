package com.roadmystery.app.story

import kotlinx.serialization.json.Json

object ScenarioParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Scenario = json.decodeFromString(Scenario.serializer(), text)

    /** 시나리오 데이터의 연결 오류를 찾는다. 비어 있으면 정상. */
    fun validate(scenario: Scenario): List<String> {
        val errors = mutableListOf<String>()
        val clueIds = scenario.clues.map { it.id }
        val suspectIds = scenario.suspects.map { it.id }

        duplicates(clueIds).forEach { errors += "단서 id 중복: $it" }
        duplicates(suspectIds).forEach { errors += "용의자 id 중복: $it" }
        if (scenario.culprit !in suspectIds) errors += "범인 ${scenario.culprit}이(가) 용의자 목록에 없음"
        if (scenario.chapters.isEmpty()) errors += "챕터가 없음"

        scenario.suspects.forEach { suspect ->
            suspect.revealable.filterNot { it in clueIds }.forEach {
                errors += "용의자 ${suspect.id}: 없는 단서 $it"
            }
        }

        scenario.chapters.forEach { chapter ->
            duplicates(chapter.scenes.map { it.id }).forEach { errors += "${chapter.id}: 장면 id 중복 $it" }
            SceneTrigger.endings.filter { chapter.sceneFor(it) == null }.forEach {
                errors += "${chapter.id}: $it 장면이 없음"
            }
            chapter.scenes.forEach { scene ->
                if (scene.script.isEmpty() || scene.script.any { it.isBlank() }) errors += "${scene.id}: 빈 대본"
                if (scene.durationSec <= 0) errors += "${scene.id}: durationSec는 0보다 커야 함"
                scene.unlocks.filterNot { it in clueIds }.forEach { errors += "${scene.id}: 없는 단서 $it" }
            }
        }

        // 도착 시 필수 장면의 단서를 일괄 지급하므로, 모든 단서는 필수 본편 장면에서 열려야 한다.
        val guaranteed =
            scenario.chapters
                .flatMap { it.mainScenes }
                .filter { it.required }
                .flatMap { it.unlocks }
                .toSet()
        clueIds.filterNot { it in guaranteed }.forEach { errors += "단서 $it 이(가) 필수 본편 장면에서 열리지 않음" }

        return errors
    }

    private fun duplicates(ids: List<String>): Set<String> = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
}
