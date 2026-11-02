package dev.voiceagent.text

private const val END_MARKS = ".?!。？！"
private const val HESITATION_MARKS = ".…"

/**
 * A single terminal mark at [index]; ellipses ("음...", "…") are hesitation, not sentence ends.
 * Python: `(?<![.…])[.?!。？！](?![.…])`.
 */
private fun isTerminalMark(text: CharSequence, index: Int): Boolean =
    text[index] in END_MARKS &&
        (index == 0 || text[index - 1] !in HESITATION_MARKS) &&
        (index + 1 >= text.length || text[index + 1] !in HESITATION_MARKS)

/** voice_eval.metrics.split_sentences: a terminal mark ends a sentence before whitespace or the end. */
fun splitSentences(text: String): List<String> {
    val sentences = mutableListOf<String>()
    var start = 0
    for (index in text.indices) {
        val isEnd = isTerminalMark(text, index) && (index + 1 == text.length || isPythonWhitespace(text[index + 1]))
        if (!isEnd) continue
        val sentence = pythonStrip(text.substring(start, index + 1))
        if (sentence.isNotEmpty()) sentences += sentence
        start = index + 1
    }
    val remainder = pythonStrip(text.substring(start))
    if (remainder.isNotEmpty()) sentences += remainder
    return sentences
}

/**
 * Cuts streamed LLM text into sentences as soon as each one is certain, so the first sentence can
 * go to TTS while the rest is still being generated. A mark counts only once the whitespace after
 * it has arrived ("약 3." may continue as "약 3.5초"). Pushing every chunk and then calling
 * [finish] yields exactly [splitSentences] of the whole text.
 */
class StreamingSentenceSplitter {
    private val text = StringBuilder()
    private var sentenceStart = 0
    private var nextIndex = 0

    /** Adds [chunk] and returns the sentences it completed. */
    fun push(chunk: String): List<String> {
        text.append(chunk)
        val completed = mutableListOf<String>()
        // Only marks whose next character has arrived can be decided.
        while (nextIndex < text.length - 1) {
            if (isTerminalMark(text, nextIndex) && isPythonWhitespace(text[nextIndex + 1])) {
                val sentence = pythonStrip(text.substring(sentenceStart, nextIndex + 1))
                if (sentence.isNotEmpty()) completed += sentence
                sentenceStart = nextIndex + 1
            }
            nextIndex++
        }
        return completed
    }

    /** Ends the stream and returns what is left, including a last sentence without a mark. */
    fun finish(): List<String> = splitSentences(text.substring(sentenceStart))
}
