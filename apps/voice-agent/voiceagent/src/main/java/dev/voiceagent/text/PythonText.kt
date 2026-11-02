package dev.voiceagent.text

import java.text.Normalizer

// Python string rules that tools/voice-eval relies on, so the app cuts and matches text exactly like
// the evaluated code. Java's regex `\s` is ASCII-only on the JVM but Unicode-aware on Android, so
// none of this goes through regex character classes.

/** `str.isspace()`, which Python's `\s`, `strip()` and `split()` use. */
internal fun isPythonWhitespace(char: Char): Boolean = char.isWhitespace() || char == '\u0085'

/** `str.strip()`. */
internal fun pythonStrip(text: String): String = text.trim(::isPythonWhitespace)

/** `str.split()` without a separator: whitespace runs separate words, empty words are dropped. */
internal fun pythonSplit(text: String): List<String> = buildList {
    var start = -1
    for (index in text.indices) {
        val isSpace = isPythonWhitespace(text[index])
        if (!isSpace && start < 0) start = index
        if (isSpace && start >= 0) {
            add(text.substring(start, index))
            start = -1
        }
    }
    if (start >= 0) add(text.substring(start))
}

/** Unicode categories P*, S*, Z* and C*: Python's `unicodedata.category(char)[0] in "PSZC"`. */
private val IGNORED_TYPES: Set<Int> = setOf(
    Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
    Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
    Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL,
    Character.OTHER_SYMBOL, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
    Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
).mapTo(HashSet()) { it.toInt() }

/**
 * voice_eval.metrics.normalize_for_match: NFKC and lowercase, keeping only letters, digits and
 * marks, so a forbidden phrase still matches through spacing, punctuation ("범인은… 집사입니다",
 * "동·백"), zero-width characters or decomposed Hangul.
 */
fun normalizeForMatch(text: String): String {
    val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
    val kept = StringBuilder(folded.length)
    var index = 0
    while (index < folded.length) {
        val codePoint = folded.codePointAt(index)
        if (Character.getType(codePoint) !in IGNORED_TYPES) kept.appendCodePoint(codePoint)
        index += Character.charCount(codePoint)
    }
    return kept.toString()
}

/** The [phrases] found in [text] once both are normalized (voice_eval.metrics.phrase_hits). */
fun phraseHits(text: String, phrases: List<String>): List<String> {
    val normalized = normalizeForMatch(text)
    return phrases.filter { phrase -> normalizeForMatch(phrase).let { it.isNotEmpty() && it in normalized } }
}
