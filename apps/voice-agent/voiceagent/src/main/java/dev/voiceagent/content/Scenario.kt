package dev.voiceagent.content

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Scenario content is malformed or references something that does not exist. */
class ContentException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

data class Clue(
    val id: String,
    val title: String,
    val summary: String,
    val chapter: Int,
    val keywords: List<String>,
)

data class CastMember(
    val id: String,
    val name: String,
    val role: String,
    val isSuspect: Boolean,
    val aliases: List<String>,
    val publicDescription: String,
)

data class Solution(val culprit: String, val method: String, val motive: String, val hidingPlace: String)

data class Case(
    val id: String,
    val title: String,
    val theme: String,
    val setting: String,
    val publicFacts: List<String>,
    val cast: List<CastMember>,
    val clues: List<Clue>,
    val solution: Solution,
    val forbiddenPhrases: List<String>,
) {
    /** Suspects in cast order, which is also the order that breaks ties when identifying them. */
    val suspects: List<CastMember> get() = cast.filter { it.isSuspect }

    fun member(id: String): CastMember =
        cast.firstOrNull { it.id == id } ?: throw NoSuchElementException("no cast member '$id'")

    fun clue(id: String): Clue = clues.firstOrNull { it.id == id } ?: throw NoSuchElementException("no clue '$id'")
}

data class Fact(val id: String, val text: String)

data class UnlockableFact(val id: String, val unlockedBy: List<String>, val text: String, val demeanor: String?)

data class Relationship(val target: String, val attitude: String)

data class Suspect(
    val id: String,
    val name: String,
    val role: String,
    /** Pleos TextToSpeech.setSpeakerType value. */
    val speaker: String,
    val age: Int,
    val background: String,
    val personality: List<String>,
    val register: String,
    val speechRules: List<String>,
    val speechExamples: List<String>,
    val public: List<Fact>,
    val unlockable: List<UnlockableFact>,
    val secret: List<Fact>,
    val relationships: List<Relationship>,
    val deflections: List<String>,
    val fallbackOffline: String,
    val fallbackNotUnderstood: String,
    val filler: String,
)

/** One case as VoiceAgent uses it: content plus the prompt templates evaluated in tools/voice-eval. */
data class Scenario(
    val case: Case,
    /** Keyed by suspect id, in cast order. */
    val suspects: Map<String, Suspect>,
    val suspectTemplate: String,
    val summaryTemplate: String,
) {
    fun suspect(id: String): Suspect = suspects[id] ?: throw NoSuchElementException("no suspect '$id'")

    companion object {
        const val SUSPECT_TEMPLATE = "suspect_system.ko.md"
        const val SUMMARY_TEMPLATE = "summary_system.ko.md"

        /**
         * Loads `<caseDir>/case.json`, `<caseDir>/suspects/<id>.json` for every suspect, and both
         * templates. [read] returns a file's text by asset path: the app's merged assets, or the
         * repository directories in JVM tests.
         */
        fun load(read: (String) -> String, caseDir: String): Scenario {
            val text = { path: String -> normalizeNewlines(read(path)) }
            val case = ScenarioParser.parseCase(text("$caseDir/case.json"))
            val suspects = case.suspects.associate { member ->
                member.id to ScenarioParser.parseSuspect(text("$caseDir/suspects/${member.id}.json"), case)
            }
            return Scenario(case, suspects, text(SUSPECT_TEMPLATE), text(SUMMARY_TEMPLATE))
        }

        /** Python reads text with universal newlines; a Windows checkout can hand the app CRLF files. */
        private fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
    }
}

/**
 * Parses the formats in content/schema. Full schema validation runs in tools/voice-eval's tests;
 * this checks the cross-references the app relies on, as voice_eval/content.py does.
 */
object ScenarioParser {
    fun parseCase(json: String): Case = parsing("case.json") {
        val root = JSONObject(json)
        val solution = root.getJSONObject("solution")
        val case = Case(
            id = root.getString("id"),
            title = root.getString("title"),
            theme = root.getString("theme"),
            setting = root.getString("setting"),
            publicFacts = root.getJSONArray("publicFacts").strings(),
            cast = root.getJSONArray("cast").objects(::castMember),
            clues = root.getJSONArray("clues").objects(::clue),
            solution = Solution(
                culprit = solution.getString("culprit"),
                method = solution.getString("method"),
                motive = solution.getString("motive"),
                hidingPlace = solution.getString("hidingPlace"),
            ),
            forbiddenPhrases = root.getJSONObject("spoilerGuard").getJSONArray("forbiddenPhrases").strings(),
        )
        requireUnique(case.cast.map { it.id }, "cast", "case.json")
        requireUnique(case.clues.map { it.id }, "clue", "case.json")
        if (case.cast.none { it.id == case.solution.culprit }) {
            throw ContentException("case.json: solution.culprit '${case.solution.culprit}' is not in cast")
        }
        case
    }

    fun parseSuspect(json: String, case: Case): Suspect = parsing("suspect") {
        val root = JSONObject(json)
        val id = root.getString("id")
        val source = "suspects/$id.json"
        val member = case.cast.firstOrNull { it.id == id }
            ?: throw ContentException("$source: id '$id' is not in the case cast")
        if (!member.isSuspect) throw ContentException("$source: cast member '$id' is not a suspect")
        val profile = root.getJSONObject("profile")
        val style = root.getJSONObject("speechStyle")
        val knowledge = root.getJSONObject("knowledge")
        val fallback = root.getJSONObject("fallbackLines")
        val suspect = Suspect(
            id = id,
            name = member.name,
            role = member.role,
            speaker = root.getJSONObject("voice").getString("pleosSpeaker"),
            age = profile.getInt("age"),
            background = profile.getString("background"),
            personality = profile.getJSONArray("personality").strings(),
            register = style.getString("register"),
            speechRules = style.getJSONArray("rules").strings(),
            speechExamples = style.getJSONArray("examples").strings(),
            public = knowledge.getJSONArray("public").objects(::fact),
            unlockable = knowledge.getJSONArray("unlockable").objects(::unlockableFact),
            secret = knowledge.getJSONArray("secret").objects(::fact),
            relationships = root.getJSONArray("relationships").objects { item ->
                Relationship(item.getString("target"), item.getString("attitude"))
            },
            deflections = root.getJSONArray("deflections").strings(),
            fallbackOffline = fallback.getString("offline"),
            fallbackNotUnderstood = fallback.getString("notUnderstood"),
            filler = fallback.getString("filler"),
        )
        checkReferences(suspect, case, source)
        suspect
    }

    private fun checkReferences(suspect: Suspect, case: Case, source: String) {
        val clueIds = case.clues.map { it.id }.toSet()
        for (fact in suspect.unlockable) {
            fact.unlockedBy.firstOrNull { it !in clueIds }?.let {
                throw ContentException("$source: knowledge '${fact.id}' is unlocked by unknown clue '$it'")
            }
        }
        val castIds = case.cast.map { it.id }.toSet()
        suspect.relationships.firstOrNull { it.target !in castIds }?.let {
            throw ContentException("$source: relationship target '${it.target}' is not in the case cast")
        }
        val knowledgeIds = suspect.public.map { it.id } + suspect.unlockable.map { it.id } + suspect.secret.map { it.id }
        requireUnique(knowledgeIds, "knowledge", source)
    }

    private fun castMember(item: JSONObject) = CastMember(
        id = item.getString("id"),
        name = item.getString("name"),
        role = item.getString("role"),
        isSuspect = item.getBoolean("isSuspect"),
        aliases = item.getJSONArray("aliases").strings(),
        publicDescription = item.getString("publicDescription"),
    )

    private fun clue(item: JSONObject) = Clue(
        id = item.getString("id"),
        title = item.getString("title"),
        summary = item.getString("summary"),
        chapter = item.getInt("chapter"),
        keywords = item.getJSONArray("keywords").strings(),
    )

    private fun fact(item: JSONObject) = Fact(item.getString("id"), item.getString("text"))

    private fun unlockableFact(item: JSONObject) = UnlockableFact(
        id = item.getString("id"),
        unlockedBy = item.getJSONArray("unlockedBy").strings(),
        text = item.getString("text"),
        // isNull covers a missing key and JSON null; Android's optString would turn null into "null".
        demeanor = if (item.isNull("demeanor")) null else item.getString("demeanor"),
    )

    private fun requireUnique(ids: List<String>, what: String, source: String) {
        val seen = HashSet<String>()
        ids.firstOrNull { !seen.add(it) }?.let { throw ContentException("$source: duplicate $what id '$it'") }
    }

    private fun <T> parsing(source: String, block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw ContentException("$source: ${e.message}", e)
    }

    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

    private fun <T> JSONArray.objects(map: (JSONObject) -> T): List<T> = List(length()) { map(getJSONObject(it)) }
}
