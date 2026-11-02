package com.roadmystery.app.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** assets에 들어 있는 실제 시나리오 파일을 검사한다. 대본을 고친 뒤 이 테스트로 연결 오류를 잡는다. */
class ScenarioDataTest {
    private val scenario =
        ScenarioParser.parse(File("src/main/assets/scenarios/midnight_rest_area.json").readText())

    @Test
    fun `데모 시나리오에 연결 오류가 없다`() {
        assertEquals(emptyList<String>(), ScenarioParser.validate(scenario))
    }

    @Test
    fun `데모 범위를 지킨다 - 용의자 3명, 챕터 1개, 브리핑 포함`() {
        assertEquals(3, scenario.suspects.size)
        assertEquals(1, scenario.chapters.size)
        assertTrue(scenario.chapters.first().sceneFor(SceneTrigger.BRIEFING) != null)
    }

    @Test
    fun `검증기가 잘못된 단서 참조를 잡는다`() {
        val broken =
            scenario.copy(
                chapters =
                    scenario.chapters.map { chapter ->
                        chapter.copy(scenes = chapter.scenes.map { it.copy(unlocks = it.unlocks + "clue_missing") })
                    },
            )
        assertTrue(ScenarioParser.validate(broken).any { "clue_missing" in it })
    }
}
