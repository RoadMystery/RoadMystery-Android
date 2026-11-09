package dev.voiceagent.content

import dev.voiceagent.RepoFiles
import dev.voiceagent.agent.Investigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every case under content/cases/, whoever wrote it, loads in the app and plays through: the investigation
 * goes from OPENING to READY, and each key reaction is drawn out by a question about its last clue once its
 * clues are found. The content rules themselves are tools/voice-eval's voice_eval/validate.py.
 */
class EveryCaseTest {
    private val caseDirs = File(RepoFiles.root, "content/cases").listFiles().orEmpty()
        .filter { File(it, "case.json").isFile }
        .map { "cases/${it.name}" }
        .sorted()

    @Test
    fun `the repository cases are all here`() {
        // midnight_rest_area is the team app's scenario, ported under its ids (docs/voice-agent/team-integration.md).
        assertTrue(
            caseDirs.containsAll(
                listOf("cases/blue-carbuncle", "cases/demo-sapphire", "cases/jade-crown", "cases/midnight_rest_area", "cases/queer-feet"),
            ),
        )
    }

    @Test
    fun `every case loads and its investigation runs from OPENING to READY`() {
        for (dir in caseDirs) {
            val scenario = RepoFiles.scenario(dir)
            val case = scenario.case
            val suspects = scenario.suspects.values
            val keyReactions = suspects.flatMap { suspect -> suspect.unlockable.filter { it.key }.map { it.id } }.toSet()
            val found = mutableSetOf<String>()

            assertEquals(dir, InvestigationStage.OPENING, Investigation.stage(case, suspects, found, emptySet()))
            for (clue in case.clues.dropLast(1)) {
                found += clue.id
                assertEquals(dir, InvestigationStage.GATHERING, Investigation.stage(case, suspects, found, emptySet()))
            }
            found += case.clues.last().id
            val withoutReactions = if (keyReactions.isEmpty()) InvestigationStage.READY else InvestigationStage.PRESSING
            assertEquals(dir, withoutReactions, Investigation.stage(case, suspects, found, emptySet()))
            assertEquals(dir, InvestigationStage.READY, Investigation.stage(case, suspects, found, keyReactions))
        }
    }

    @Test
    fun `every key reaction is drawn out by a question about its last clue once its clues are found`() {
        for (dir in caseDirs) {
            val scenario = RepoFiles.scenario(dir)
            val case = scenario.case
            for (suspect in scenario.suspects.values) {
                for (fact in suspect.unlockable.filter { it.key }) {
                    val last = case.clues.last { it.id in fact.unlockedBy }
                    val drawn = Investigation.drawnOut(case, suspect, fact.unlockedBy.toSet(), "${last.title} 얘기 좀 해 봐요")
                    assertTrue("$dir ${fact.id}", fact.id in drawn)
                }
            }
        }
    }
}
