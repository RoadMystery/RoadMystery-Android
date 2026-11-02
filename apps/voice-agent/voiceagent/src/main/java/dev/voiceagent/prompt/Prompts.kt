package dev.voiceagent.prompt

import dev.voiceagent.content.Case
import dev.voiceagent.content.Suspect
import dev.voiceagent.content.UnlockableFact
import dev.voiceagent.text.phraseHits
import dev.voiceagent.text.pythonStrip

// Port of tools/voice-eval/voice_eval/prompt.py and the prompt half of summary.py. The app must
// send exactly the prompts that were evaluated, so GoldenParityTest replays the Python output.

/**
 * COVER: the suspect knows only its cover story. TRUTH: the hidden truth is included so the model
 * can lie consistently; tools/voice-eval measures which one leaks less.
 */
enum class PromptVariant(val id: String) {
    COVER("cover"),
    TRUTH("truth"),
    ;

    companion object {
        fun fromId(id: String): PromptVariant = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("unknown variant '$id' (expected one of cover, truth)")
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

    /** Reactions whose required clues have all been found. */
    fun unlockedFacts(suspect: Suspect, unlocked: Set<String>): List<UnlockableFact> =
        suspect.unlockable.filter { unlocked.containsAll(it.unlockedBy) }

    /** Keywords of the clues not found yet, in clue order. */
    fun lockedKeywords(case: Case, unlocked: Set<String>): List<String> =
        case.clues.filter { it.id !in unlocked }.flatMap { it.keywords }

    /** Keywords of clues not found yet that [question] brings up, matched like the spoiler guard. */
    fun raisedTopics(case: Case, unlocked: Set<String>, question: String): List<String> =
        phraseHits(question, lockedKeywords(case, unlocked))

    /** [topics]: from [raisedTopics] for the question being answered; none leaves the prompt unchanged. */
    fun render(
        case: Case,
        suspect: Suspect,
        unlocked: Set<String>,
        variant: PromptVariant,
        template: String,
        topics: List<String> = emptyList(),
    ): String {
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
            "public_facts" to PromptText.caseOverview(case),
            "profile" to PromptText.bullets(listOf("${suspect.age}세. ${suspect.background}") + suspect.personality),
            "speech_style" to speechSection(suspect),
            "public_knowledge" to PromptText.bullets(suspect.public.map { it.text }),
            "unlocked_knowledge" to unlockedSection(case, suspect, unlocked),
            "relationships" to relationshipSection(case, suspect),
            "deflections" to PromptText.bullets(suspect.deflections),
            "secret_section" to secretSection(suspect, variant),
        )
        return PromptText.fillTemplate(template, values)
    }

    private fun unlockedSection(case: Case, suspect: Suspect, unlocked: Set<String>): String {
        val clues = case.clues.filter { it.id in unlocked }
        if (clues.isEmpty()) return NO_CLUES_YET
        val reactions = unlockedFacts(suspect, unlocked).map { fact ->
            val demeanor = if (fact.demeanor.isNullOrEmpty()) "" else " (태도: ${fact.demeanor})"
            "[너의 반응] ${fact.text}$demeanor"
        }
        return PromptText.bullets(clues.map { "[단서] ${it.title}: ${it.summary}" } + reactions)
    }

    private fun speechSection(suspect: Suspect): String {
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

    private fun secretSection(suspect: Suspect, variant: PromptVariant): String {
        if (variant != PromptVariant.TRUTH) return ""
        val facts = PromptText.bullets(suspect.secret.map { it.text })
        return "\n# 너만 아는 진실 (절대 말하지 말 것)\n$SECRET_INTRO\n$facts"
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
