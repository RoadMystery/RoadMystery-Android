package dev.voiceagent.content

import dev.voiceagent.RepoFiles
import dev.voiceagent.objects
import dev.voiceagent.prompt.SuspectPrompt
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
    fun `narrator hints are read when a case has them and null when it does not`() {
        val jade = RepoFiles.scenario("cases/jade-crown").case

        assertTrue(jade.clues.all { !it.hint.isNullOrEmpty() })
        assertTrue(!jade.spoilerHint.isNullOrEmpty())
        assertTrue(scenario.case.clues.all { it.hint == null })
        assertNull(scenario.case.spoilerHint)
    }

    @Test
    fun `mentions, key reactions and pressure lines are read for jade-crown and absent from the demo case`() {
        val jade = RepoFiles.scenario("cases/jade-crown")
        val keyReactions = jade.suspects.values.flatMap { suspect -> suspect.unlockable.filter { it.key }.map { it.id } }

        assertEquals(listOf("뒷문", "뒤문", "부엌문"), jade.case.clue("clue-back-door").mentions)
        assertEquals(listOf("창문", "서재 창", "창밖", "창 아래", "창가", "창을 열", "창 열"), jade.case.clue("clue-window").mentions)
        assertEquals(listOf("골목"), jade.case.clue("clue-barefoot").mentions)
        // Not "구두" alone: the window's "구두 자국" would bring the shoes up too.
        assertEquals(listOf("고정태 구두", "고정태의 구두"), jade.case.clue("clue-shoes").mentions)
        assertEquals(setOf("k-son-shoes", "k-niece-window", "k-maid-back-door"), keyReactions.toSet())
        for (suspect in jade.suspects.values) {
            assertEquals(suspect.id, setOf(InvestigationStage.PRESSING, InvestigationStage.READY), suspect.pressure.keys)
            assertTrue(suspect.id, suspect.pressure.values.all { it.isNotBlank() })
        }
        assertTrue(scenario.case.clues.all { it.mentions.isEmpty() })
        assertTrue(scenario.suspects.values.all { suspect -> suspect.pressure.isEmpty() && suspect.unlockable.none { it.key } })
    }

    @Test
    fun `a pressure line for an unknown stage is rejected`() {
        val json = suspectJson("butler") { it.put("pressure", JSONObject().put("finale", "이제 다 끝났습니다.")) }
        assertMessage("unknown pressure stage 'finale'") { ScenarioParser.parseSuspect(json, scenario.case) }
    }

    @Test
    fun `an empty string is no value, as Python reads it`() {
        val caseJson = JSONObject(RepoFiles.asset("${RepoFiles.CASE_DIR}/case.json"))
        caseJson.getJSONArray("clues").getJSONObject(0).put("hint", "")
        caseJson.getJSONObject("spoilerGuard").put("hint", "")
        val case = ScenarioParser.parseCase(caseJson.toString())
        val pressingLine = "회장님께는 정말 면목이 없습니다."
        val json = suspectJson("butler") {
            val knowledge = it.getJSONObject("knowledge")
            knowledge.getJSONArray("public").getJSONObject(0).put("line", "")
            knowledge.getJSONArray("unlockable").getJSONObject(0).put("line", "").put("demeanor", "")
            it.put("pressure", JSONObject().put("pressing", pressingLine).put("ready", ""))
        }
        val butler = ScenarioParser.parseSuspect(json, scenario.case)

        assertNull(case.clues[0].hint)
        assertNull(case.spoilerHint)
        // Python's `line or text`: an empty line shows the answer key, not an empty bullet.
        assertEquals(butler.public[0].text, butler.public[0].promptText)
        assertEquals(butler.unlockable[0].text, butler.unlockable[0].promptText)
        assertNull(butler.unlockable[0].demeanor)
        // An empty READY line falls back to the PRESSING one, as Python's `or` does.
        assertEquals(mapOf(InvestigationStage.PRESSING to pressingLine), butler.pressure)
        assertEquals(pressingLine, SuspectPrompt.pressureLine(butler, InvestigationStage.READY))
    }

    @Test
    fun `a key reaction no clue unlocks is rejected`() {
        val json = suspectJson("butler") {
            it.getJSONObject("knowledge").getJSONArray("unlockable").getJSONObject(0)
                .put("key", true)
                .put("unlockedBy", JSONArray())
        }
        assertMessage("key reaction") { ScenarioParser.parseSuspect(json, scenario.case) }
    }

    @Test
    fun `a reaction replaces only lines already shown that no key reaction needs`() {
        fun reaction(knowledge: JSONObject, id: String) =
            knowledge.getJSONArray("unlockable").objects().first { it.getString("id") == id }
        fun rejects(message: String, mutate: (JSONObject) -> Unit) = message to mutate
        val rejected = listOf(
            rejects("unknown fact 'k-nope'") { reaction(it, "k-fusebox").put("replaces", JSONArray(listOf("k-nope"))) },
            rejects("replaces itself") { reaction(it, "k-fusebox").put("replaces", JSONArray(listOf("k-fusebox"))) },
            rejects("needs clues") { reaction(it, "k-fusebox").put("replaces", JSONArray(listOf("k-gloves"))) },
            rejects("replaces key reaction 'k-fusebox'") {
                reaction(it, "k-fusebox").put("key", true)
                addReactionToBothClues(it, "k-fusebox")
            },
        )
        for ((message, mutate) in rejected) {
            val json = suspectJson("butler") { mutate(it.getJSONObject("knowledge")) }
            assertMessage(message) { ScenarioParser.parseSuspect(json, scenario.case) }
        }
    }

    @Test
    fun `a reaction may replace public facts and reactions shown before it`() {
        val json = suspectJson("butler") { addReactionToBothClues(it.getJSONObject("knowledge"), "k-alibi-claim", "k-fusebox") }
        val butler = ScenarioParser.parseSuspect(json, scenario.case)

        assertEquals(listOf("k-alibi-claim", "k-fusebox"), butler.unlockable.first { it.id == "k-both" }.replaces)
        assertTrue(butler.unlockable.filter { it.id != "k-both" }.all { it.replaces.isEmpty() })
    }

    private fun addReactionToBothClues(knowledge: JSONObject, vararg replaces: String) {
        knowledge.getJSONArray("unlockable").put(
            JSONObject()
                .put("id", "k-both")
                .put("unlockedBy", JSONArray(listOf("clue-fusebox", "clue-wet-gloves")))
                .put("text", "두 단서를 다 보고 털어놓는 말")
                .put("replaces", JSONArray(replaces.toList())),
        )
    }

    @Test
    fun `a key reaction id two suspects share is rejected`() {
        val caseDir = "cases/jade-crown"
        val niecePath = "$caseDir/suspects/niece.json"
        val niece = JSONObject(RepoFiles.asset(niecePath))
        niece.getJSONObject("knowledge").getJSONArray("unlockable").objects().first { it.optBoolean("key") }.put("id", "k-maid-back-door")

        assertMessage("duplicate key reaction id 'k-maid-back-door'") {
            Scenario.load({ path -> if (path == niecePath) niece.toString() else RepoFiles.asset(path) }, caseDir)
        }
    }

    @Test
    fun `a missing demeanor is null, not the text null`() {
        val pawn = scenario.suspect("butler").unlockable.first { it.id == "k-pawn" }
        assertNull(pawn.demeanor)
    }

    @Test
    fun `a fact's line is what the prompt shows, and its text stays the answer key`() {
        val json = suspectJson("butler") {
            val knowledge = it.getJSONObject("knowledge")
            knowledge.getJSONArray("public").getJSONObject(0).put("line", "회장님 지시로 생신용 와인을 가지러 내려갔습니다.")
            knowledge.getJSONArray("unlockable").getJSONObject(0).put("line", "두꺼비집이 저장고 바로 옆에 있는 건 맞습니다.")
        }
        val butler = ScenarioParser.parseSuspect(json, scenario.case)

        assertEquals("회장님 지시로 생신용 와인을 가지러 내려갔습니다.", butler.public[0].promptText)
        assertTrue(butler.public[0].text.endsWith("말한다."))
        assertNull(butler.public[1].line)
        assertEquals(butler.public[1].text, butler.public[1].promptText)
        assertEquals("두꺼비집이 저장고 바로 옆에 있는 건 맞습니다.", butler.unlockable[0].promptText)
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
