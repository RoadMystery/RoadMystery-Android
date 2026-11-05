package dev.voiceagent.prompt

import dev.voiceagent.RepoFiles
import dev.voiceagent.content.InvestigationStage
import dev.voiceagent.content.Suspect
import dev.voiceagent.content.UnlockableFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PRESSURE_HEADING = "# 수사가 깊어진 지금 하게 되는 말 (어울릴 때만)"
private const val PRESSING_LINE = "회장님께는 정말 면목이 없습니다."
private const val READY_LINE = "드릴 말씀은 이제 다 드렸습니다."

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
    fun `each reaction sits under the clue that brings it up`() {
        val case = scenario.case
        val butler = scenario.suspect("butler")
        val fuseboxClue = case.clues.first { it.id == "clue-fusebox" }
        val keyClue = case.clues.first { it.id == "clue-cellar-key" }
        val fusebox = butler.unlockable.first { it.id == "k-fusebox" }
        val second = UnlockableFact("k-fusebox-2", listOf("clue-fusebox"), "두꺼비집 두 번째 반응", null)
        val combo = UnlockableFact("k-combo", listOf("clue-cellar-key", "clue-fusebox"), "두 단서를 모두 본 뒤의 반응", null)
        val suspect = butler.copy(unlockable = butler.unlockable + second + combo)
        val lines = SuspectPrompt.render(
            case, suspect, setOf("clue-fusebox", "clue-cellar-key"), PromptVariant.COVER, scenario.suspectTemplate,
        ).lines()
        fun at(text: String) = lines.indexOfFirst { text in it }.also { assertTrue("missing '$text'", it >= 0) }

        val label = "[${fuseboxClue.title} 이야기에 너의 반응]"
        assertTrue(at(fuseboxClue.summary) < at("$label ${fusebox.promptText}"))
        assertTrue(at("$label ${fusebox.promptText}") < at("$label ${second.text}"))
        assertTrue(at("$label ${second.text}") < at(keyClue.summary))
        assertTrue(at(keyClue.summary) < at("[${fuseboxClue.title}·${keyClue.title} 이야기에 너의 반응] ${combo.text}"))
        assertEquals(1, lines.count { combo.text in it })
    }

    @Test
    fun `a reaction replaces the lines it contradicts once unlocked`() {
        val butler = scenario.suspect("butler")
        val alibi = butler.public.first()
        val fusebox = butler.unlockable.first { it.id == "k-fusebox" }
        val gloves = butler.unlockable.first { it.id == "k-gloves" }
        val confession = UnlockableFact(
            "k-confession", listOf("clue-fusebox", "clue-wet-gloves"), "두 단서를 다 보고 털어놓는 말", null,
            replaces = listOf(alibi.id, fusebox.id),
        )
        val suspect = butler.copy(unlockable = butler.unlockable + confession)
        fun render(unlocked: Set<String>) =
            SuspectPrompt.render(scenario.case, suspect, unlocked, PromptVariant.COVER, scenario.suspectTemplate)
        val before = render(setOf("clue-fusebox"))
        val after = render(setOf("clue-fusebox", "clue-wet-gloves"))

        assertTrue(alibi.promptText in before && fusebox.promptText in before)
        assertFalse(alibi.promptText in after || fusebox.promptText in after)
        assertTrue(gloves.promptText in after && confession.text in after)
    }

    @Test
    fun `the prompt shows a fact's line in place of its answer key`() {
        val butler = scenario.suspect("butler")
        val alibi = butler.public.first().copy(line = "회장님 지시로 생신용 와인을 가지러 내려갔습니다.")
        val fusebox = butler.unlockable.first { it.id == "k-fusebox" }.copy(line = "두꺼비집이 저장고 바로 옆에 있는 건 맞습니다.")
        val spoken = butler.copy(
            public = listOf(alibi) + butler.public.drop(1),
            unlockable = butler.unlockable.map { if (it.id == fusebox.id) fusebox else it },
        )
        val prompt = SuspectPrompt.render(scenario.case, spoken, setOf("clue-fusebox"), PromptVariant.COVER, scenario.suspectTemplate)

        assertTrue(prompt.contains(alibi.promptText) && !prompt.contains(alibi.text))
        assertTrue(prompt.contains(fusebox.promptText) && !prompt.contains(fusebox.text))
        assertTrue(prompt.contains(butler.public[1].text))
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

    @Test
    fun `the pressure line follows the found clues only from PRESSING on`() {
        val butler = scenario.suspect("butler").copy(pressure = mapOf(InvestigationStage.PRESSING to PRESSING_LINE))
        val plain = everyClueFound(butler)
        val pressing = everyClueFound(butler, InvestigationStage.PRESSING)
        val section = "\n\n$PRESSURE_HEADING\n- $PRESSING_LINE"

        assertEquals(plain, everyClueFound(butler, InvestigationStage.OPENING))
        assertEquals(plain, everyClueFound(butler, InvestigationStage.GATHERING))
        assertTrue(pressing.contains("$section\n\n# 다른 인물에 대해\n"))
        // The section is all a stage adds, so before PRESSING the prompt is exactly what it was.
        assertEquals(plain, pressing.replaceFirst(section, ""))
    }

    @Test
    fun `READY shows its own pressure line or falls back to the PRESSING one`() {
        val butler = scenario.suspect("butler")
        val both = butler.copy(pressure = mapOf(InvestigationStage.PRESSING to PRESSING_LINE, InvestigationStage.READY to READY_LINE))
        val pressingOnly = butler.copy(pressure = mapOf(InvestigationStage.PRESSING to PRESSING_LINE))
        val readyOnly = butler.copy(pressure = mapOf(InvestigationStage.READY to READY_LINE))
        val ready = everyClueFound(both, InvestigationStage.READY)

        assertEquals(PRESSING_LINE, SuspectPrompt.pressureLine(both, InvestigationStage.PRESSING))
        assertEquals(READY_LINE, SuspectPrompt.pressureLine(both, InvestigationStage.READY))
        assertEquals(PRESSING_LINE, SuspectPrompt.pressureLine(pressingOnly, InvestigationStage.READY))
        // The line for the end is never said early.
        assertNull(SuspectPrompt.pressureLine(readyOnly, InvestigationStage.PRESSING))
        assertNull(SuspectPrompt.pressureLine(both, InvestigationStage.OPENING))
        assertNull(SuspectPrompt.pressureLine(both, InvestigationStage.GATHERING))
        assertTrue(ready.contains("\n\n$PRESSURE_HEADING\n- $READY_LINE\n") && !ready.contains(PRESSING_LINE))
        assertEquals(
            everyClueFound(pressingOnly, InvestigationStage.PRESSING),
            everyClueFound(pressingOnly, InvestigationStage.READY),
        )
    }

    @Test
    fun `a suspect without pressure lines gets the same prompt at every stage`() {
        val butler = scenario.suspect("butler")
        val plain = everyClueFound(butler)

        assertTrue(butler.pressure.isEmpty())
        for (stage in InvestigationStage.entries) {
            assertEquals(stage.id, plain, everyClueFound(butler, stage))
        }
    }

    /** The cover prompt with every clue found, as the app reaches the later stages; no stage passed when null. */
    private fun everyClueFound(suspect: Suspect, stage: InvestigationStage? = null): String {
        val found = scenario.case.clues.map { it.id }.toSet()
        val template = scenario.suspectTemplate
        return if (stage == null) {
            SuspectPrompt.render(scenario.case, suspect, found, PromptVariant.COVER, template)
        } else {
            SuspectPrompt.render(scenario.case, suspect, found, PromptVariant.COVER, template, stage = stage)
        }
    }
}
