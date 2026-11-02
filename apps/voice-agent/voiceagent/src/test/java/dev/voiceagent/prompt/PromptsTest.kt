package dev.voiceagent.prompt

import dev.voiceagent.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {
    private val scenario = RepoFiles.scenario()

    @Test
    fun `particles follow the final consonant`() {
        assertEquals("한도윤이", KoreanParticles.withSubject("한도윤"))
        assertEquals("집사가", KoreanParticles.withSubject("집사"))
        assertEquals("한도윤으로", KoreanParticles.withInstrumental("한도윤"))
        assertEquals("집사로", KoreanParticles.withInstrumental("집사"))
        assertEquals("길로", KoreanParticles.withInstrumental("길"))
        assertEquals("AI가", KoreanParticles.withSubject("AI"))
        assertEquals("AI로", KoreanParticles.withInstrumental("AI"))
        assertEquals("젖은 흰 장갑이에요", KoreanParticles.withCopula("젖은 흰 장갑"))
        assertEquals("지하실 열쇠예요", KoreanParticles.withCopula("지하실 열쇠"))
    }

    @Test
    fun `cover prompt keeps the secret out and truth prompt carries it`() {
        val butler = scenario.suspect("butler")
        val cover = SuspectPrompt.render(scenario.case, butler, emptySet(), PromptVariant.COVER, scenario.suspectTemplate)
        val truth = SuspectPrompt.render(scenario.case, butler, emptySet(), PromptVariant.TRUTH, scenario.suspectTemplate)
        val secret = butler.secret.first().text
        assertFalse(cover.contains(secret))
        assertTrue(truth.contains(secret))
        assertFalse(cover.contains("{{"))
        assertTrue(cover.endsWith("\n") && !cover.endsWith("\n\n"))
    }

    @Test
    fun `a reaction appears only once all of its clues are found`() {
        val cook = scenario.suspect("cook")
        val wetFloor = cook.unlockable.first { it.unlockedBy.size > 1 }
        val oneClue = setOf(wetFloor.unlockedBy.first())
        assertFalse(SuspectPrompt.unlockedFacts(cook, oneClue).contains(wetFloor))
        assertTrue(SuspectPrompt.unlockedFacts(cook, wetFloor.unlockedBy.toSet()).contains(wetFloor))
    }

    @Test
    fun `unknown clues and placeholders are errors`() {
        val niece = scenario.suspect("niece")
        assertThrows(IllegalArgumentException::class.java) {
            SuspectPrompt.render(scenario.case, niece, setOf("clue-nope"), PromptVariant.COVER, scenario.suspectTemplate)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SummaryPrompt.render(scenario.case, scenario.summaryTemplate, setOf("clue-nope"), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) { PromptText.fillTemplate("{{이름}}", emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { PromptVariant.fromId("lie") }
    }

    @Test
    fun `summary prompt keeps only the most recent testimonies`() {
        val testimonies = (1..SummaryPrompt.MAX_TESTIMONIES + 3).map { Testimony("cook", "질문 $it", "대답 $it") }
        val prompt = SummaryPrompt.render(scenario.case, scenario.summaryTemplate, emptySet(), testimonies)
        assertFalse(prompt.contains("질문 3 /"))
        assertTrue(prompt.contains("질문 4 /"))
        assertTrue(prompt.contains("질문 ${SummaryPrompt.MAX_TESTIMONIES + 3} /"))
    }
}
