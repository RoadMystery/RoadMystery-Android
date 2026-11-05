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
    /**
     * The narrator's hint while this clue is not found: what to watch for in the scenes to come. For a
     * cut leak or an answer-seeking question with no key reaction within reach left to suggest,
     * NarratorLines.hint gives the first such hint in case order.
     */
    val hint: String? = null,
    /** Words besides [keywords] and [title] by which a question brings this clue up (confrontations). */
    val mentions: List<String> = emptyList(),
)

/**
 * How far the player's investigation has come (수사 진척도), never shown to the player. [id] is the
 * key of a persona's pressure lines and of voice-eval's probe stage.
 */
enum class InvestigationStage(val id: String) {
    /** No clue of the case is found yet. */
    OPENING("opening"),

    /** Some clues are found, not all. */
    GATHERING("gathering"),

    /** Every clue is found; some key reaction has not been drawn out yet. */
    PRESSING("pressing"),

    /** Every clue is found and every key reaction drawn out: time to accuse. */
    READY("ready"),
}

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
    /**
     * The narrator's hint once every clue is found and no key reaction is pending, each one drawn out
     * (NarratorLines.hint).
     */
    val spoilerHint: String? = null,
) {
    /** Suspects in cast order, which is also the order that breaks ties when identifying them. */
    val suspects: List<CastMember> get() = cast.filter { it.isSuspect }

    fun member(id: String): CastMember =
        cast.firstOrNull { it.id == id } ?: throw NoSuchElementException("no cast member '$id'")

    fun clue(id: String): Clue = clues.firstOrNull { it.id == id } ?: throw NoSuchElementException("no clue '$id'")
}

/**
 * [text] is the answer key the evaluation checks against; [line], when the persona has one, is the
 * same claim as the suspect says it, and the prompt shows that instead.
 */
data class Fact(val id: String, val text: String, val line: String? = null) {
    val promptText: String get() = line ?: text
}

/** A reaction to found clues; [text] and [line] as in [Fact]. */
data class UnlockableFact(
    val id: String,
    val unlockedBy: List<String>,
    val text: String,
    val demeanor: String?,
    val line: String? = null,
    /** Needed to solve the case: drawing out every key reaction makes the stage [InvestigationStage.READY]. */
    val key: Boolean = false,
    /**
     * Ids of the suspect's public facts and earlier reactions this reaction supersedes: once it is
     * unlocked the prompt stops showing them, so a cover story and the confession that ends it do
     * not sit side by side (voice_eval.content.UnlockableFact.replaces).
     */
    val replaces: List<String> = emptyList(),
) {
    val promptText: String get() = line ?: text
}

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
    /** First-person lines the prompt adds from [InvestigationStage.PRESSING] on. */
    val pressure: Map<InvestigationStage, String> = emptyMap(),
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
            ScenarioParser.checkCast(suspects.values)
            return Scenario(case, suspects, text(SUSPECT_TEMPLATE), text(SUMMARY_TEMPLATE))
        }

        /** Python reads text with universal newlines; a Windows checkout can hand the app CRLF files. */
        private fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
    }
}

/**
 * Parses the formats in content/schema. Full schema validation runs in tools/voice-eval's tests;
 * this checks the cross-references the app relies on, as voice_eval/content.py does, and what
 * VoiceAgent's investigation progress needs of the key reactions.
 */
object ScenarioParser {
    fun parseCase(json: String): Case = parsing("case.json") {
        val root = JSONObject(json)
        val solution = root.getJSONObject("solution")
        val guard = root.getJSONObject("spoilerGuard")
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
            forbiddenPhrases = guard.getJSONArray("forbiddenPhrases").strings(),
            spoilerHint = guard.optionalString("hint"),
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
            pressure = root.optJSONObject("pressure")?.let { pressure(it, source) }.orEmpty(),
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
            // No question could bring it up, so the investigation would never reach READY; any other
            // reaction would have no clue to sit under in the prompt (the schema's minItems: 1).
            if (fact.unlockedBy.isEmpty()) {
                val what = if (fact.key) "key reaction" else "knowledge"
                throw ContentException("$source: $what '${fact.id}' has no clue in unlockedBy")
            }
        }
        val castIds = case.cast.map { it.id }.toSet()
        suspect.relationships.firstOrNull { it.target !in castIds }?.let {
            throw ContentException("$source: relationship target '${it.target}' is not in the case cast")
        }
        val knowledgeIds = suspect.public.map { it.id } + suspect.unlockable.map { it.id } + suspect.secret.map { it.id }
        requireUnique(knowledgeIds, "knowledge", source)
        checkReplaces(suspect, source)
    }

    /**
     * voice_eval.content._check_replaces: a reaction hides only lines the prompt already shows when
     * it unlocks, the suspect's public facts and its reactions to a subset of the same clues. A key
     * reaction is never hidden, because the investigation counts it as something to draw out.
     */
    private fun checkReplaces(suspect: Suspect, source: String) {
        val publicIds = suspect.public.map { it.id }.toSet()
        val reactions = suspect.unlockable.associateBy { it.id }
        for (fact in suspect.unlockable) {
            for (target in fact.replaces) {
                if (target == fact.id) throw ContentException("$source: knowledge '${fact.id}' replaces itself")
                if (target !in publicIds && target !in reactions) {
                    throw ContentException("$source: knowledge '${fact.id}' replaces unknown fact '$target'")
                }
                val replaced = reactions[target] ?: continue
                if (replaced.key) throw ContentException("$source: knowledge '${fact.id}' replaces key reaction '$target'")
                if (!fact.unlockedBy.containsAll(replaced.unlockedBy)) {
                    throw ContentException("$source: knowledge '${fact.id}' replaces '$target', which needs clues '${fact.id}' does not")
                }
            }
        }
    }

    /**
     * What only the whole cast shows, once every persona is parsed: VoiceAgent counts confrontations
     * by key reaction id alone, so an id two suspects share would be drawn out of both at once.
     */
    internal fun checkCast(suspects: Collection<Suspect>) {
        val owners = suspects
            .flatMap { suspect -> suspect.unlockable.filter { it.key }.map { it.id to suspect.id } }
            .groupBy({ it.first }, { it.second })
        owners.entries.firstOrNull { it.value.size > 1 }?.let { (id, cast) ->
            val files = cast.joinToString(" and ") { "suspects/$it.json" }
            throw ContentException("duplicate key reaction id '$id' in $files")
        }
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
        hint = item.optionalString("hint"),
        mentions = item.optJSONArray("mentions")?.strings().orEmpty(),
    )

    /** The schema allows lines for these stages only; earlier stages never get one. */
    private val PRESSURE_STAGES = listOf(InvestigationStage.PRESSING, InvestigationStage.READY)

    /** An empty line is no line, so READY falls back to PRESSING as Python's `or` does. */
    private fun pressure(item: JSONObject, source: String): Map<InvestigationStage, String> =
        item.keys().asSequence().mapNotNull { id ->
            val stage = PRESSURE_STAGES.firstOrNull { it.id == id }
                ?: throw ContentException("$source: unknown pressure stage '$id' (pressing or ready only)")
            item.optionalString(id)?.let { stage to it }
        }.toMap()

    private fun fact(item: JSONObject) = Fact(item.getString("id"), item.getString("text"), item.optionalString("line"))

    private fun unlockableFact(item: JSONObject) = UnlockableFact(
        id = item.getString("id"),
        unlockedBy = item.getJSONArray("unlockedBy").strings(),
        text = item.getString("text"),
        demeanor = item.optionalString("demeanor"),
        line = item.optionalString("line"),
        key = item.optBoolean("key", false),
        replaces = item.optJSONArray("replaces")?.strings().orEmpty(),
    )

    // isNull covers a missing key and JSON null; Android's optString would turn null into "null".
    // An empty string is absent too: Python reads "" as no value (`line or text`, `if demeanor`).
    private fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else getString(key).ifEmpty { null }

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
