package dev.voiceagent.content

import dev.voiceagent.RepoFiles
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioTest {
    private val scenario = RepoFiles.scenario()

    @Test
    fun `demo case loads every suspect in cast order, each with its own voice`() {
        assertEquals(listOf("butler", "niece", "cook"), scenario.suspects.keys.toList())
        assertEquals(3, scenario.suspects.values.map { it.speaker }.toSet().size)
        assertEquals("한도윤", scenario.suspect("butler").name)
        assertEquals("집사", scenario.suspect("butler").role)
    }

    @Test
    fun `a missing demeanor is null, not the text null`() {
        val pawn = scenario.suspect("butler").unlockable.first { it.id == "k-pawn" }
        assertNull(pawn.demeanor)
    }

    @Test
    fun `CRLF files load exactly like LF files`() {
        // A Windows checkout (core.autocrlf) can already be CRLF, so derive both endings from LF text.
        val lf = { path: String -> RepoFiles.asset(path).replace("\r\n", "\n") }
        val crlf = Scenario.load({ lf(it).replace("\n", "\r\n") }, RepoFiles.CASE_DIR)
        assertEquals(Scenario.load(lf, RepoFiles.CASE_DIR), crlf)
    }

    @Test
    fun `unknown clue in unlockedBy is rejected`() {
        val json = suspectJson("butler") {
            it.getJSONObject("knowledge").getJSONArray("unlockable").getJSONObject(0)
                .put("unlockedBy", JSONArray(listOf("clue-nope")))
        }
        assertMessage("clue-nope") { ScenarioParser.parseSuspect(json, scenario.case) }
    }

    @Test
    fun `a persona for someone outside the cast or not a suspect is rejected`() {
        assertMessage("not in the case cast") {
            ScenarioParser.parseSuspect(suspectJson("butler") { it.put("id", "gardener") }, scenario.case)
        }
        assertMessage("not a suspect") {
            ScenarioParser.parseSuspect(suspectJson("butler") { it.put("id", "chairman") }, scenario.case)
        }
    }

    @Test
    fun `unknown relationship target and duplicate knowledge ids are rejected`() {
        val stranger = suspectJson("cook") {
            it.getJSONArray("relationships").getJSONObject(0).put("target", "gardener")
        }
        assertMessage("gardener") { ScenarioParser.parseSuspect(stranger, scenario.case) }

        val duplicate = suspectJson("niece") {
            val knowledge = it.getJSONObject("knowledge")
            val firstPublicId = knowledge.getJSONArray("public").getJSONObject(0).getString("id")
            knowledge.getJSONArray("secret").getJSONObject(0).put("id", firstPublicId)
        }
        assertMessage("duplicate knowledge id") { ScenarioParser.parseSuspect(duplicate, scenario.case) }
    }

    @Test
    fun `a culprit outside the cast and malformed JSON are content errors`() {
        val case = JSONObject(RepoFiles.asset("${RepoFiles.CASE_DIR}/case.json"))
        case.getJSONObject("solution").put("culprit", "gardener")
        assertMessage("solution.culprit") { ScenarioParser.parseCase(case.toString()) }
        assertThrows(ContentException::class.java) { ScenarioParser.parseCase("{\"id\": ") }
        assertThrows(ContentException::class.java) { ScenarioParser.parseCase("{\"id\": \"x\"}") }
    }

    private fun suspectJson(id: String, edit: (JSONObject) -> Unit): String =
        JSONObject(RepoFiles.asset("${RepoFiles.CASE_DIR}/suspects/$id.json")).also(edit).toString()

    private fun assertMessage(fragment: String, block: () -> Unit) {
        val error = assertThrows(ContentException::class.java) { block() }
        assertTrue("'${error.message}' should mention '$fragment'", error.message.orEmpty().contains(fragment))
    }
}
