package dev.voiceagent

import dev.voiceagent.identify.SequenceMatcher
import dev.voiceagent.identify.SuspectIdentifier
import dev.voiceagent.identify.decomposeJamo
import dev.voiceagent.prompt.PromptVariant
import dev.voiceagent.prompt.SummaryPrompt
import dev.voiceagent.prompt.SuspectPrompt
import dev.voiceagent.prompt.Testimony
import dev.voiceagent.text.normalizeForMatch
import dev.voiceagent.text.phraseHits
import dev.voiceagent.text.splitSentences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays tools/voice-eval/golden/voice-agent-golden.json. Evaluation results only carry over to
 * the car if the app renders the same prompts and routes questions the same way as Python.
 * After a content or template change, run `python -m voice_eval golden` and commit the file;
 * tools/voice-eval/tests/test_golden.py fails while it is stale.
 */
class GoldenParityTest {
    private val golden = JSONObject(RepoFiles.read("tools/voice-eval/golden/voice-agent-golden.json"))
    private val scenario = RepoFiles.scenario()

    @Test
    fun `golden file is the version and case this port implements`() {
        assertEquals(1, golden.getInt("version"))
        assertEquals(scenario.case.id, golden.getString("case"))
    }

    @Test
    fun `suspect prompts match Python for every suspect, clue set and variant`() {
        val entries = golden.getJSONArray("prompts").objects()
        assertTrue(entries.isNotEmpty())
        for (entry in entries) {
            val suspect = scenario.suspect(entry.getString("suspect"))
            val unlocked = entry.getJSONArray("unlocked").strings().toSet()
            val variant = PromptVariant.fromId(entry.getString("variant"))
            val actual = SuspectPrompt.render(scenario.case, suspect, unlocked, variant, scenario.suspectTemplate)
            assertEquals("${suspect.id} $unlocked ${variant.id}", entry.getString("prompt"), actual)
        }
    }

    @Test
    fun `summary prompts and the empty-notebook overview match Python`() {
        assertEquals(golden.getString("overview"), SummaryPrompt.overviewFallback(scenario.case))
        val entries = golden.getJSONArray("summaries").objects()
        assertTrue(entries.isNotEmpty())
        for (entry in entries) {
            val testimonies = entry.getJSONArray("testimonies").objects().map {
                Testimony(it.getString("suspect"), it.getString("question"), it.getString("answer"))
            }
            val unlocked = entry.getJSONArray("unlocked").strings().toSet()
            val actual = SummaryPrompt.render(scenario.case, scenario.summaryTemplate, unlocked, testimonies)
            assertEquals(entry.getString("probe"), entry.getString("prompt"), actual)
        }
    }

    @Test
    fun `suspect identification matches Python`() {
        val identifier = SuspectIdentifier.forCase(scenario.case)
        for (entry in golden.getJSONArray("identify").objects()) {
            val utterance = entry.getString("utterance")
            val actual = identifier.identify(utterance, entry.stringOrNull("default"))
            assertEquals(utterance, entry.stringOrNull("expected"), actual)
        }
    }

    @Test
    fun `jamo similarity matches difflib exactly`() {
        val entries = golden.getJSONArray("similarity").objects()
        assertTrue(entries.isNotEmpty())
        for (entry in entries) {
            val a = entry.getString("a")
            val b = entry.getString("b")
            val actual = SequenceMatcher.ratio(decomposeJamo(a), decomposeJamo(b))
            assertEquals("'$a' / '$b'", entry.getDouble("ratio"), actual, 0.0)
        }
    }

    @Test
    fun `spoiler guard normalization and hits match Python`() {
        val entries = golden.getJSONArray("guard").objects()
        assertTrue(entries.isNotEmpty())
        for (entry in entries) {
            val text = entry.getString("text")
            assertEquals("'$text'", entry.getString("normalized"), normalizeForMatch(text))
            assertEquals("'$text'", entry.getJSONArray("hits").strings(), phraseHits(text, scenario.case.forbiddenPhrases))
        }
    }

    @Test
    fun `sentence splitting matches Python`() {
        for (entry in golden.getJSONArray("sentences").objects()) {
            val text = entry.getString("text")
            assertEquals("'$text'", entry.getJSONArray("sentences").strings(), splitSentences(text))
        }
    }
}
