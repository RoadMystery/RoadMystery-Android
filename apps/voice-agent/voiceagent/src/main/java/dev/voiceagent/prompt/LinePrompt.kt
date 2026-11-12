package dev.voiceagent.prompt

import dev.voiceagent.content.Case
import dev.voiceagent.content.InvestigationStage
import dev.voiceagent.content.Scenario
import dev.voiceagent.content.Suspect
import dev.voiceagent.text.normalizeForMatch

/**
 * One line a suspect may say this turn. [text] is a string of its persona file, word for word, and [id] is how
 * the model names it (voice_eval.prompt.Line). The car reads [text] and nothing else.
 */
data class SpeakableLine(val id: String, val label: String, val text: String)

/**
 * Answers made of case lines only (AgentConfig.answers, docs/voice-agent/line-answers.md): the model only picks
 * lines by id and the app reads the lines as written, so an answer holds nothing the case file does not, and a
 * reaction to a clue not found yet is not on the list to pick. Port of voice_eval.prompt's speakable_lines,
 * render_line_prompt, chosen_lines and heard_line_ids; GoldenParityTest replays them.
 */
object LinePrompt {
    const val CONFESSION_ID = "confession"
    const val PRESSURE_ID = "pressure"
    const val NOT_UNDERSTOOD_ID = "not-understood"
    private const val DEFLECTION_PREFIX = "deflect-"

    /** Two lines answer a question that asks two things; more would run long in a car. */
    const val MAX_LINES = 2

    private const val PUBLIC_LABEL = "처음부터 할 수 있는 말"
    private const val PRESSURE_LABEL = "수사가 깊어진 지금, 어울릴 때만"
    private const val CONFESSION_LABEL = "범인으로 몰릴 때"
    private const val DEFLECTION_LABEL = "맞는 대사가 없을 때"
    private const val NOT_UNDERSTOOD_LABEL = "무슨 말인지 전혀 알 수 없을 때"
    private const val NO_CLUES = "- 아직 없음"

    /** An id the model writes; ids are matched whole, so "k-son" never matches inside "k-son-shoes". */
    private const val ID_CHARS = "A-Za-z0-9_-"

    /** What a fact id may be made of, so a whole-id match cannot stop at a "." or a Hangul letter inside one. */
    private val ID = Regex("[$ID_CHARS]+")

    /**
     * What [suspect] may say this turn, in the order the prompt lists them: its public lines and the reactions
     * the found clues unlock, less the lines those reactions take back ([SuspectPrompt.shownFacts]); the pressure
     * line for [stage], unless [confession] takes its place; the confession; its deflections; its line for a
     * question it did not catch. [stage] and [confession] as for [SuspectPrompt.render].
     */
    fun speakableLines(
        case: Case,
        suspect: Suspect,
        unlocked: Set<String>,
        stage: InvestigationStage = InvestigationStage.OPENING,
        confession: Boolean = false,
    ): List<SpeakableLine> {
        val confessionLine = suspect.confession
        require(!confession || (suspect.id == case.solution.culprit && confessionLine != null)) {
            "'${suspect.id}' cannot confess"
        }
        PromptText.requireKnownClues(case, unlocked)
        val (public, reactions) = SuspectPrompt.shownFacts(suspect, unlocked)
        val order = case.clues.map { it.id }
        val pressure = if (confession) null else SuspectPrompt.pressureLine(suspect, stage)
        return buildList {
            public.forEach { add(SpeakableLine(it.id, PUBLIC_LABEL, lineOf(suspect, it.id, it.line))) }
            // A reaction to several clues goes under the last of them in case order, as in the free prompt.
            for (clue in case.clues.filter { it.id in unlocked }) {
                for (fact in reactions.filter { it.unlockedBy.maxBy(order::indexOf) == clue.id }) {
                    val titles = fact.unlockedBy.sortedBy(order::indexOf).joinToString("·") { case.clue(it).title }
                    add(SpeakableLine(fact.id, "$titles 이야기에", lineOf(suspect, fact.id, fact.line)))
                }
            }
            pressure?.let { add(SpeakableLine(PRESSURE_ID, PRESSURE_LABEL, it)) }
            if (confession && confessionLine != null) add(SpeakableLine(CONFESSION_ID, CONFESSION_LABEL, confessionLine))
            suspect.deflections.forEachIndexed { index, text ->
                add(SpeakableLine("$DEFLECTION_PREFIX${index + 1}", DEFLECTION_LABEL, text))
            }
            add(SpeakableLine(NOT_UNDERSTOOD_ID, NOT_UNDERSTOOD_LABEL, suspect.fallbackNotUnderstood))
        }
    }

    /** The system prompt asking the model to pick [suspect]'s lines by id, from [speakableLines]. */
    fun render(
        case: Case,
        suspect: Suspect,
        unlocked: Set<String>,
        template: String,
        stage: InvestigationStage = InvestigationStage.OPENING,
        confession: Boolean = false,
    ): String {
        val lines = speakableLines(case, suspect, unlocked, stage, confession)
        val values = mapOf(
            "case_title" to case.title,
            "name" to suspect.name,
            "name_ga" to KoreanParticles.withSubject(suspect.name),
            "role" to suspect.role,
            "confession_rule" to if (confession) confessionRule(suspect) else "",
            "public_facts" to PromptText.caseOverview(case),
            "clues" to clueSection(case, unlocked),
            "lines" to lines.joinToString("\n") { "- ${it.id} (${it.label}): ${it.text}" },
        )
        return PromptText.fillTemplate(template, values)
    }

    /**
     * The lines [reply] names, from [lines], to be spoken in this order. Ids count in the order the reply names
     * them, each once; anything else in the reply is ignored, so it is never spoken. The confession goes alone;
     * otherwise up to [MAX_LINES] case lines, and only when there is none, the first deflection or not-caught
     * line named. Empty when the reply names none.
     */
    fun chosen(reply: String, lines: List<SpeakableLine>): List<SpeakableLine> {
        if (lines.isEmpty()) return emptyList()
        val byId = lines.associateBy { it.id.lowercase() }
        val alternatives = lines.map { it.id }.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val pattern = Regex("(?<![$ID_CHARS])(?:$alternatives)(?![$ID_CHARS])", RegexOption.IGNORE_CASE)
        val named = pattern.findAll(reply).mapNotNull { byId[it.value.lowercase()] }.distinct().toList()
        named.firstOrNull { it.id == CONFESSION_ID }?.let { return listOf(it) }
        val content = named.filterNot { isStock(it.id) }
        return if (content.isNotEmpty()) content.take(MAX_LINES) else named.take(1)
    }

    /**
     * Ids of [suspect]'s lines that [answer], something it was heard to say, holds word for word (normalized
     * like the spoiler guard), in the order it says them: for the conversation history the model sees and
     * for the notebook read back. Longer lines claim their stretch first, so a deflection inside a longer
     * line is not counted twice.
     */
    fun heardLineIds(suspect: Suspect, answer: String): List<String> {
        val text = normalizeForMatch(answer)
        val claimed = mutableListOf<IntRange>()
        val found = mutableListOf<Pair<Int, String>>()
        for ((id, line) in everyLine(suspect).sortedByDescending { it.second.length }) {
            var from = 0
            while (true) {
                val start = text.indexOf(line, from)
                if (start < 0) break
                val span = start until start + line.length
                from = start + 1
                if (claimed.any { it.first <= span.last && span.first <= it.last }) continue
                claimed += span
                found += start to id
                break
            }
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    /**
     * The case lines (public lines, reactions, the confession) of [heardLineIds], as written: what the notebook
     * reads back as the suspect's words, without deflections and the like.
     */
    fun heardCaseLines(suspect: Suspect, answer: String): List<String> {
        val texts = caseLineTexts(suspect)
        return heardLineIds(suspect, answer).mapNotNull { texts[it] }
    }

    /**
     * What keeps [scenario] from being answered in lines: "suspect/fact" for each public line or reaction
     * without a line, for each whose id has characters other than A-Z, a-z, 0-9, "_" and "-", and for each
     * that takes an id the lines' own ids use. Empty when it can be.
     */
    fun problems(scenario: Scenario): List<String> = scenario.suspects.values.flatMap { suspect ->
        val facts = suspect.public.map { it.id to it.line } + suspect.unlockable.map { it.id to it.line }
        facts.mapNotNull { (id, line) ->
            when {
                line == null -> "${suspect.id}/$id has no line"
                !ID.matches(id) -> "${suspect.id}/$id has characters an id may not have"
                isReserved(id) -> "${suspect.id}/$id uses a reserved id"
                else -> null
            }
        }
    }

    /** A deflection or the line for a question not caught: what the suspect says when no case line fits. */
    fun isStock(id: String): Boolean = id == NOT_UNDERSTOOD_ID || isDeflection(id)

    private fun isDeflection(id: String): Boolean =
        id.startsWith(DEFLECTION_PREFIX) && id.length > DEFLECTION_PREFIX.length &&
            id.drop(DEFLECTION_PREFIX.length).all { it in '0'..'9' }

    private fun isReserved(id: String): Boolean = id == CONFESSION_ID || id == PRESSURE_ID || isStock(id)

    private fun lineOf(suspect: Suspect, id: String, line: String?): String =
        line ?: throw IllegalArgumentException("${suspect.id}/$id has no line")

    private fun confessionRule(suspect: Suspect): String =
        "\n- 플레이어가 찾은 증거를 들이밀며 ${KoreanParticles.withSubject(suspect.name)} 범인이라고 몰아붙이면 " +
            "'$CONFESSION_LABEL' 대사를 골라라."

    private fun clueSection(case: Case, unlocked: Set<String>): String {
        val clues = case.clues.filter { it.id in unlocked }
        return if (clues.isEmpty()) NO_CLUES else PromptText.bullets(clues.map { "${it.title}: ${it.summary}" })
    }

    /** id to text of the lines [suspect] says about the case, whatever has been found. */
    private fun caseLineTexts(suspect: Suspect): Map<String, String> = buildMap {
        suspect.public.forEach { fact -> fact.line?.let { put(fact.id, it) } }
        suspect.unlockable.forEach { fact -> fact.line?.let { put(fact.id, it) } }
        suspect.confession?.let { put(CONFESSION_ID, it) }
    }

    /** (id, normalized text) of every line [suspect] could have said in any turn. */
    private fun everyLine(suspect: Suspect): List<Pair<String, String>> = buildList {
        caseLineTexts(suspect).forEach { (id, text) -> add(id to text) }
        suspect.pressure.values.forEach { add(PRESSURE_ID to it) }
        suspect.deflections.forEachIndexed { index, text -> add("$DEFLECTION_PREFIX${index + 1}" to text) }
        add(NOT_UNDERSTOOD_ID to suspect.fallbackNotUnderstood)
    }.map { (id, text) -> id to normalizeForMatch(text) }.filter { it.second.isNotEmpty() }
}
