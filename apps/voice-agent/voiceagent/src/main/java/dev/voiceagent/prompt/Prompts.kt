package dev.voiceagent.prompt

import dev.voiceagent.content.Case
import dev.voiceagent.content.Fact
import dev.voiceagent.content.InvestigationStage
import dev.voiceagent.content.Suspect
import dev.voiceagent.content.UnlockableFact
import dev.voiceagent.text.phraseHits
import dev.voiceagent.text.pythonStrip

// Port of tools/voice-eval/voice_eval/prompt.py and the prompt half of summary.py. The app must
// send exactly the prompts that were evaluated, so GoldenParityTest replays the Python output.

/**
 * COVER: the suspect knows only its cover story. AWARE: the culprit also knows it hides something, and its
 * confession joins the prompt only once it is cornered (agent/Confession); the app's default
 * (docs/voice-agent/game-rules.md). TRUTH: the hidden truth is included so the model can lie consistently;
 * tools/voice-eval measures which one leaks less.
 */
enum class PromptVariant(val id: String) {
    COVER("cover"),
    AWARE("aware"),
    TRUTH("truth"),
    ;

    companion object {
        fun fromId(id: String): PromptVariant = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("unknown variant '$id' (expected one of cover, aware, truth)")
    }
}

/** A question the player asked a suspect and the answer that was spoken (F-06 수사 수첩). */
data class Testimony(val suspect: String, val question: String, val answer: String)

object KoreanParticles {
    private const val HANGUL_BASE = 0xAC00
    private const val HANGUL_LAST = 0xD7A3
    private const val FINALS = 28
    private const val RIEUL_FINAL = 8

    /** Index of the last syllable's final consonant (0 = none); null if it is not Hangul. */
    fun finalConsonant(word: String): Int? {
        val code = word.lastOrNull()?.code ?: return null
        return if (code in HANGUL_BASE..HANGUL_LAST) (code - HANGUL_BASE) % FINALS else null
    }

    /** 한도윤 -> 한도윤이, 집사 -> 집사가. */
    fun withSubject(word: String): String = word + if (hasFinal(word)) "이" else "가"

    /** 한도윤 -> 한도윤으로, 집사 -> 집사로, 길 -> 길로. */
    fun withInstrumental(word: String): String =
        word + if (hasFinal(word) && finalConsonant(word) != RIEUL_FINAL) "으로" else "로"

    /** 장갑 -> 장갑이에요, 열쇠 -> 열쇠예요. */
    fun withCopula(word: String): String = word + if (hasFinal(word)) "이에요" else "예요"

    private fun hasFinal(word: String): Boolean = (finalConsonant(word) ?: 0) != 0
}

object PromptText {
    const val FAMILY_RULE = "- 가족이 함께 듣는 이야기다. 폭력적이거나 잔인하거나 무서운 묘사는 하지 마라."

    /** Python `\{\{(\w+)\}\}`, spelled out because Java's `\w` is ASCII-only on the JVM. */
    private val PLACEHOLDER = Regex("""\{\{([\p{L}\p{N}_]+)\}\}""")

    fun bullets(lines: Iterable<String>): String = lines.joinToString("\n") { "- $it" }

    /** Replaces {{name}} placeholders; an unknown placeholder is an error, never left in place. */
    fun fillTemplate(template: String, values: Map<String, String>): String {
        val filled = PLACEHOLDER.replace(template) { match ->
            val key = match.groupValues[1]
            values[key] ?: throw IllegalArgumentException("template uses unknown placeholder '{{$key}}'")
        }
        return pythonStrip(filled) + "\n"
    }

    fun themeRule(case: Case): String = if (case.theme == "family") FAMILY_RULE else ""

    fun caseOverview(case: Case): String = "${case.setting}\n${bullets(case.publicFacts)}"

    internal fun requireKnownClues(case: Case, unlocked: Set<String>) {
        val unknown = (unlocked - case.clues.map { it.id }.toSet()).sorted()
        require(unknown.isEmpty()) { "unknown clue id(s): ${unknown.joinToString(", ")}" }
    }
}

/** The system prompt for one suspect, given the clues the player has found. */
object SuspectPrompt {
    private const val SECRET_INTRO =
        "아래는 네가 거짓말을 일관되게 하도록 알려 주는 배경이다. 이 내용을 인정하거나 암시하지 말고, " +
            "플레이어가 아직 찾지 않은 증거를 먼저 꺼내지 마라. 추궁을 받아도 '네가 말할 수 있는 사실'의 " +
            "주장을 지켜라."
    private const val NO_CLUES_YET = "- 아직 플레이어가 찾은 단서가 없다."
    private const val PRESSURE_HEADING = "# 수사가 깊어진 지금 하게 되는 말 (어울릴 때만)"
    private const val BELONGINGS_HEADING = "# 네가 가진 것"
    private const val HIDING_SECTION =
        "# 너만 아는 것\n" +
            "- 너는 이 사건에서 숨기는 일이 있다. '네가 말할 수 있는 사실'의 이야기는 그 일을 감추려는 너의 주장이다. " +
            "무엇을 숨기는지는 플레이어가 찾은 단서가 드러낸 만큼만 떠오른다.\n" +
            "- 들키면 곤란하니 그 주장을 지켜라. 불리한 이야기는 짧게 넘기되 물은 것에는 답하라. " +
            "숨기는 일이 있다는 것은 말로 인정하지 말고, 말투에만 드러나게 하라.\n" +
            "- 거짓말은 그 주장 안에서만 하라. 새 알리바이를 만들거나 남에게 덮어씌우지 마라."

    /** When to confess; the words are under the key clue ([reactionLines]). */
    private const val CONFESSION_SECTION =
        "# 다 들킨 지금\n" +
            "- 플레이어가 단서를 다 찾아 너의 주장이 무너졌다. 플레이어가 그 증거를 들이밀며 너를 범인으로 몰아붙이면, " +
            "더 버티지 말고 '플레이어가 찾은 단서'에서 '너를 범인으로 몰아붙일 때 너의 반응'에 적힌 말대로 털어놓아라. " +
            "그 말에 없는 것은 보태지 마라.\n" +
            "- 아직 털어놓지 않았고 너를 몰아붙이는 말도 아니면, 지금까지처럼 주장을 지켜라."

    /**
     * Ends the rule on who did it once the culprit is cornered: it confesses before it would hold out with a
     * clue's reaction, as rule 3 comes before rule 6 in docs/voice-agent/game-rules.md.
     */
    private const val CONFESSION_RULE =
        "다만 플레이어가 찾은 증거를 들이밀며 너를 범인으로 몰아붙이면, 단서에 대한 반응으로 버티지 말고 " +
            "아래 '다 들킨 지금'의 말대로 털어놓아라."

    /** Reactions whose required clues have all been found. */
    fun unlockedFacts(suspect: Suspect, unlocked: Set<String>): List<UnlockableFact> =
        suspect.unlockable.filter { unlocked.containsAll(it.unlockedBy) }

    /**
     * voice_eval.prompt.shown_facts: the public facts and reactions the prompt shows, an unlocked
     * reaction hiding the lines it [UnlockableFact.replaces]. Prompt only; the answer keys stay.
     */
    fun shownFacts(suspect: Suspect, unlocked: Set<String>): Pair<List<Fact>, List<UnlockableFact>> {
        val reactions = unlockedFacts(suspect, unlocked)
        val replaced = reactions.flatMap { it.replaces }.toSet()
        return suspect.public.filter { it.id !in replaced } to reactions.filter { it.id !in replaced }
    }

    /** Keywords of the clues not found yet, in clue order. */
    fun lockedKeywords(case: Case, unlocked: Set<String>): List<String> =
        case.clues.filter { it.id !in unlocked }.flatMap { it.keywords }

    /** Keywords of clues not found yet that [question] brings up, matched like the spoiler guard. */
    fun raisedTopics(case: Case, unlocked: Set<String>, question: String): List<String> =
        phraseHits(question, lockedKeywords(case, unlocked))

    /**
     * [topics]: from [raisedTopics] for the question being answered; none leaves the prompt unchanged.
     * [stage]: from [InvestigationStage.PRESSING] on, the persona's pressure line is added, if it has one.
     * [confession]: from agent/Confession; only the culprit of [PromptVariant.AWARE] with a confession line can
     * have it, and it takes the pressure line's place.
     */
    fun render(
        case: Case,
        suspect: Suspect,
        unlocked: Set<String>,
        variant: PromptVariant,
        template: String,
        topics: List<String> = emptyList(),
        stage: InvestigationStage = InvestigationStage.OPENING,
        confession: Boolean = false,
    ): String {
        require(!confession || (variant == PromptVariant.AWARE && suspect.id == case.solution.culprit && suspect.confession != null)) {
            "'${suspect.id}' cannot confess in the '${variant.id}' variant"
        }
        PromptText.requireKnownClues(case, unlocked)
        val values = mapOf(
            "case_title" to case.title,
            "name" to suspect.name,
            "name_ga" to KoreanParticles.withSubject(suspect.name),
            "name_ro" to KoreanParticles.withInstrumental(suspect.name),
            "role" to suspect.role,
            "not_understood" to suspect.fallbackNotUnderstood,
            "theme_rule" to PromptText.themeRule(case),
            "topic_rule" to topicRule(suspect, topics),
            "confession_rule" to if (confession) " $CONFESSION_RULE" else "",
            "public_facts" to PromptText.caseOverview(case),
            "profile" to PromptText.bullets(listOf("${suspect.age}세. ${suspect.background}") + suspect.personality + stanceLines(suspect)),
            "speech_style" to speechSection(suspect),
            "public_knowledge" to PromptText.bullets(shownFacts(suspect, unlocked).first.map { it.promptText }),
            "belongings" to belongingsSection(suspect),
            "unlocked_knowledge" to unlockedSection(case, suspect, unlocked, confession),
            // Once cornered the confession takes the stage line's place: the culprit's READY line holds out.
            "pressure" to if (confession) "" else pressureSection(suspect, stage),
            "relationships" to relationshipSection(case, suspect),
            "deflections" to PromptText.bullets(suspect.deflections),
            "secret_section" to secretSection(case, suspect, variant, confession),
        )
        return PromptText.fillTemplate(template, values)
    }

    /** voice_eval.prompt._stance_lines: what the suspect wants and how it treats the detective, for its profile. */
    fun stanceLines(suspect: Suspect): List<String> = listOfNotNull(
        suspect.goal?.let { "이 심문에서 바라는 것: $it" },
        suspect.towardDetective?.let { "탐정을 대하는 태도: $it" },
    )

    /** Follows the public facts directly, so it brings its own blank line; empty without belongings. */
    private fun belongingsSection(suspect: Suspect): String =
        if (suspect.belongings.isEmpty()) "" else "\n\n$BELONGINGS_HEADING\n${PromptText.bullets(suspect.belongings)}"

    /**
     * voice_eval.prompt._unlocked_section: each found clue, then the reactions it brings up. The label
     * names a reaction's clues, since a first-person line does not say when it is said; a reaction to
     * several clues goes once, under the last of them in case order, the clue the narrator's hint names.
     */
    private fun unlockedSection(case: Case, suspect: Suspect, unlocked: Set<String>, confession: Boolean): String {
        val clues = case.clues.filter { it.id in unlocked }
        if (clues.isEmpty()) return NO_CLUES_YET
        val order = case.clues.map { it.id }
        val reactions = shownFacts(suspect, unlocked).second
        val lines = clues.flatMap { clue ->
            listOf("[단서] ${clue.title}: ${clue.summary}") +
                reactions.filter { fact -> fact.unlockedBy.maxBy(order::indexOf) == clue.id }
                    .flatMap { reactionLines(case, suspect, it, order, confession) }
        }
        return PromptText.bullets(lines)
    }

    /**
     * voice_eval.prompt._reaction_lines: a reaction's line. Once the culprit is cornered, each key reaction
     * splits in two: the reaction when it is only asked about, and the confession when that clue is used to
     * accuse it, so the line the question's clue points at already holds the confession.
     */
    private fun reactionLines(
        case: Case,
        suspect: Suspect,
        fact: UnlockableFact,
        order: List<String>,
        confession: Boolean,
    ): List<String> {
        val titles = fact.unlockedBy.sortedBy(order::indexOf).joinToString("·") { id -> case.clues.first { it.id == id }.title }
        val demeanor = if (fact.demeanor.isNullOrEmpty()) "" else " (태도: ${fact.demeanor})"
        if (!(confession && fact.key)) return listOf("[$titles 이야기에 너의 반응] ${fact.promptText}$demeanor")
        return listOf(
            "[$titles 이야기에 너의 반응 (몰아붙이지 않을 때)] ${fact.promptText}$demeanor",
            "[$titles 이야기로 너를 범인으로 몰아붙일 때 너의 반응] ${suspect.confession}",
        )
    }

    /** The persona's line for [stage]: READY falls back to PRESSING, earlier stages have none. */
    fun pressureLine(suspect: Suspect, stage: InvestigationStage): String? = when (stage) {
        InvestigationStage.READY -> suspect.pressure[InvestigationStage.READY] ?: suspect.pressure[InvestigationStage.PRESSING]
        InvestigationStage.PRESSING -> suspect.pressure[InvestigationStage.PRESSING]
        InvestigationStage.OPENING, InvestigationStage.GATHERING -> null
    }

    /** Follows the found-clue section directly, so it brings its own blank line; empty without a line. */
    private fun pressureSection(suspect: Suspect, stage: InvestigationStage): String =
        pressureLine(suspect, stage)?.let { "\n\n$PRESSURE_HEADING\n- $it" } ?: ""

    private fun speechSection(suspect: Suspect): String {
        // Examples go in as written: a repeated opening exclamation is dropped as the answer is spoken
        // (text/dropRepeatedOpening), since cutting it here only moved the habit to another one.
        val examples = suspect.speechExamples.joinToString("\n") { "  - $it" }
        return listOf(
            "- 말투: ${suspect.register}",
            PromptText.bullets(suspect.speechRules),
            "- 예시:\n$examples",
        ).joinToString("\n")
    }

    private fun relationshipSection(case: Case, suspect: Suspect): String = PromptText.bullets(
        suspect.relationships.map { relationship ->
            val member = case.member(relationship.target)
            "${member.name}(${member.role}): ${relationship.attitude}"
        },
    )

    /** Keeps the answer about a raised topic to the listed facts; adds nothing when none is raised. */
    private fun topicRule(suspect: Suspect, topics: List<String>): String {
        if (topics.isEmpty()) return ""
        val quoted = topics.joinToString(", ") { "'$it'" }
        return "\n- 플레이어가 지금 꺼낸 $quoted 이야기는 '네가 말할 수 있는 사실'과 '플레이어가 찾은 단서'에 있는 " +
            "만큼만 말하라. 누가 무엇을 가졌는지, 언제 어디서 무엇을 했는지 새로 보태지 말고, 나머지는 '인물'에 " +
            "적힌 ${suspect.name}의 성격대로 짧게 받아넘겨라."
    }

    /**
     * TRUTH: the hidden truth. AWARE: for the culprit only, that it hides something, or once it is cornered
     * when to confess in place of that; the words are under the key clue ([reactionLines]). Empty otherwise.
     */
    private fun secretSection(case: Case, suspect: Suspect, variant: PromptVariant, confession: Boolean): String {
        if (variant == PromptVariant.TRUTH) {
            val facts = PromptText.bullets(suspect.secret.map { it.text })
            return "\n# 너만 아는 진실 (절대 말하지 말 것)\n$SECRET_INTRO\n$facts"
        }
        if (variant != PromptVariant.AWARE || suspect.id != case.solution.culprit) return ""
        return if (confession) "\n$CONFESSION_SECTION" else "\n$HIDING_SECTION"
    }
}

/** The narrator's system prompt for "지금까지 정리해줘" (F-06). */
object SummaryPrompt {
    /** Only the most recent testimonies go into the prompt, so it stays short and cheap. */
    const val MAX_TESTIMONIES = 12
    const val REQUEST = "지금까지 정리해줘"
    const val EMPTY_NOTEBOOK_PREFIX = "아직 수사 수첩이 비어 있어요. 사건을 다시 들려드릴게요."
    private const val NOTHING_YET = "- 아직 없음"

    fun render(case: Case, template: String, unlocked: Set<String>, testimonies: List<Testimony>): String {
        PromptText.requireKnownClues(case, unlocked)
        val values = mapOf(
            "case_title" to case.title,
            "theme_rule" to PromptText.themeRule(case),
            "public_facts" to PromptText.caseOverview(case),
            "clues" to clueLines(case, unlocked),
            "testimonies" to testimonyLines(case, testimonies),
        )
        return PromptText.fillTemplate(template, values)
    }

    /** F-06 exception: with nothing in the notebook, replay the case overview without an LLM. */
    fun overviewFallback(case: Case): String = "$EMPTY_NOTEBOOK_PREFIX ${case.setting}"

    private fun clueLines(case: Case, unlocked: Set<String>): String {
        val clues = case.clues.filter { it.id in unlocked }
        return if (clues.isEmpty()) NOTHING_YET else PromptText.bullets(clues.map { "${it.title}: ${it.summary}" })
    }

    private fun testimonyLines(case: Case, testimonies: List<Testimony>): String {
        val recent = testimonies.takeLast(MAX_TESTIMONIES)
        if (recent.isEmpty()) return NOTHING_YET
        return PromptText.bullets(
            recent.map { testimony ->
                val member = case.member(testimony.suspect)
                "${member.name}(${member.role})에게 물은 것: ${testimony.question} / 들은 대답: ${testimony.answer}"
            },
        )
    }
}
