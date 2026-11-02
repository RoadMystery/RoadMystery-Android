package dev.voiceagent.identify

import dev.voiceagent.content.Case
import dev.voiceagent.text.pythonSplit

private val HONORIFICS = listOf("", "씨", "님")
private val ADDRESSEE_PARTICLES = listOf("한테", "에게", "께")
private val TRAILING = listOf("", "는", "도", "만", "요")
private val MENTION_PARTICLES = listOf(
    "", "은", "는", "이", "가", "을", "를", "의", "도", "만", "랑", "이랑", "하고",
    "와", "과", "에", "요", "야", "아", "께서", "한테서", "에게서",
)
private val MENTION_SUFFIXES = MENTION_PARTICLES.flatMap { particle -> TRAILING.map { particle + it } }.toSet()
private val ADDRESSEE_SUFFIXES = ADDRESSEE_PARTICLES.flatMap { particle -> TRAILING.map { particle + it } }.toSet()

/** Two suffixes of one length cannot both end a token, so only the length order matters. */
private val ADDRESSEE_SUFFIXES_LONGEST_FIRST = ADDRESSEE_SUFFIXES.sortedByDescending { it.length }

private const val HANGUL_BASE = 0xAC00
private const val HANGUL_LAST = 0xD7A3
private const val CHOSEONG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
private const val JUNGSEONG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
private val JONGSEONG = listOf(
    "", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ", "ㄾ",
    "ㄿ", "ㅀ", "ㅁ", "ㅂ", "ㅄ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ",
)
private val NUMBER_CATEGORIES = setOf(
    CharCategory.DECIMAL_DIGIT_NUMBER,
    CharCategory.LETTER_NUMBER,
    CharCategory.OTHER_NUMBER,
)

internal fun decomposeJamo(text: String): String = buildString {
    for (char in text) {
        val code = char.code
        if (code in HANGUL_BASE..HANGUL_LAST) {
            val index = code - HANGUL_BASE
            append(CHOSEONG[index / 588])
            append(JUNGSEONG[(index % 588) / 28])
            append(JONGSEONG[index % 28])
        } else {
            append(char)
        }
    }
}

/**
 * Picks which suspect a spoken question is addressed to (F-05 '용의자 판별'). A port of
 * tools/voice-eval/voice_eval/identify.py; GoldenParityTest keeps the two identical.
 *
 * 1. An explicit addressee - a name followed by 한테/에게/께 ("집사한테 물어봐") - switches suspect.
 *    A jamo-level fuzzy match covers STT errors in that position ("지브사한테" -> 집사).
 * 2. Otherwise the current suspect is kept. Merely mentioning someone ("서진 씨 코트에서 영수증이
 *    나왔어요") is a question about them, and STT output has no commas to mark a vocative.
 * 3. With no current suspect, the first suspect mentioned by name is used.
 *
 * Names must start a word and be followed only by an honorific and/or particle, so "집사람이" or
 * "요리 사진" do not match. Rule-based so it runs on-device without an LLM call.
 */
class SuspectIdentifier(aliasesById: Map<String, List<String>>) {
    /** Insertion (cast) order breaks ties, as dict order does in Python. */
    private val aliases: Map<String, List<String>> = LinkedHashMap(aliasesById)

    /** (suspect id, alias without spaces, its jamo) for every alias long enough to compare. */
    private val fuzzyAliases: List<Triple<String, String, String>> = aliases.flatMap { (suspectId, names) ->
        names.map { pythonSplit(it).joinToString("") }
            .filter { it.length >= 2 }
            .map { Triple(suspectId, it, decomposeJamo(it)) }
    }

    /** Suspect id the utterance is addressed to; [current] is the suspect being questioned now. */
    fun identify(utterance: String, current: String? = null): String? {
        val tokens = tokens(utterance)
        val hits = hits(tokens)
        return earliest(hits.filter { it.addressed }) ?: fuzzyAddressee(tokens) ?: current ?: earliest(hits)
    }

    private data class Hit(val tokenIndex: Int, val aliasLength: Int, val suspectId: String, val addressed: Boolean)

    private enum class Kind { ADDRESSED, MENTION }

    private fun hits(tokens: List<String>): List<Hit> = buildList {
        for ((suspectId, names) in aliases) {
            for (name in names) {
                for (index in tokens.indices) {
                    val kind = matchAt(tokens, index, name) ?: continue
                    add(Hit(index, name.length, suspectId, kind == Kind.ADDRESSED))
                }
            }
        }
    }

    /** The hit nearest the start of the utterance; at one position the longer alias wins. */
    private fun earliest(hits: List<Hit>): String? =
        hits.minWithOrNull(compareBy<Hit>({ it.tokenIndex }, { -it.aliasLength }))?.suspectId

    private fun matchAt(tokens: List<String>, index: Int, alias: String): Kind? {
        val parts = pythonSplit(alias)
        val end = index + parts.size - 1
        val isMultiWord = parts.size > 1 && end < tokens.size && tokens.subList(index, end) == parts.dropLast(1)
        if (isMultiWord && tokens[end].startsWith(parts.last())) {
            suffixKind(tokens[end].substring(parts.last().length))?.let { return it }
        }
        val compact = parts.joinToString("") // STT often drops the space: "서진씨한테", "박여사한테"
        return if (tokens[index].startsWith(compact)) suffixKind(tokens[index].substring(compact.length)) else null
    }

    /** ADDRESSED or MENTION, or null when [rest] is not honorific + particle (집사 + "람이"). */
    private fun suffixKind(rest: String): Kind? {
        for (honorific in HONORIFICS) {
            if (!rest.startsWith(honorific)) continue
            val tail = rest.substring(honorific.length)
            if (tail in ADDRESSEE_SUFFIXES) return Kind.ADDRESSED
            if (tail in MENTION_SUFFIXES) return Kind.MENTION
        }
        return null
    }

    private fun fuzzyAddressee(tokens: List<String>): String? {
        val stems = tokens.mapNotNull(::addresseeStem)
        var bestScore = 0.0
        var bestId: String? = null
        for ((suspectId, alias, aliasJamo) in fuzzyAliases) {
            val score = stems.maxOfOrNull { stem ->
                windows(stem, alias.length).maxOf { SequenceMatcher.ratio(aliasJamo, decomposeJamo(it)) }
            } ?: continue
            if (score > bestScore) {
                bestScore = score
                bestId = suspectId
            }
        }
        return if (bestScore >= FUZZY_THRESHOLD) bestId else null
    }

    companion object {
        const val FUZZY_THRESHOLD = 0.85

        fun forCase(case: Case): SuspectIdentifier = SuspectIdentifier(case.suspects.associate { it.id to it.aliases })

        /** Python `[^\w]+` splitting: `\w` is `str.isalnum()` or '_'. */
        private fun tokens(utterance: String): List<String> = buildList {
            val current = StringBuilder()
            for (char in utterance) {
                if (isWordChar(char)) {
                    current.append(char)
                } else if (current.isNotEmpty()) {
                    add(current.toString())
                    current.setLength(0)
                }
            }
            if (current.isNotEmpty()) add(current.toString())
        }

        private fun isWordChar(char: Char): Boolean = char == '_' || char.isLetter() || char.category in NUMBER_CATEGORIES

        /** The name part of an addressee token ("지브사한테" -> "지브사"), without 씨/님. */
        private fun addresseeStem(token: String): String? {
            val suffix = ADDRESSEE_SUFFIXES_LONGEST_FIRST.firstOrNull { token.endsWith(it) && token.length > it.length }
                ?: return null
            val stem = token.dropLast(suffix.length)
            val honorific = HONORIFICS.drop(1).firstOrNull { stem.endsWith(it) && stem.length > it.length }
            return if (honorific != null) stem.dropLast(honorific.length) else stem
        }

        /** Substrings of [token] one shorter, as long, and one longer than the alias (at least 2). */
        private fun windows(token: String, size: Int): List<String> =
            sortedSetOf(maxOf(2, size - 1), size, size + 1).flatMap { width ->
                (0 until maxOf(1, token.length - width + 1)).map { start ->
                    token.substring(start, minOf(start + width, token.length))
                }
            }
    }
}
