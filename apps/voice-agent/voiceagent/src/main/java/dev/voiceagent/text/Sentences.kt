package dev.voiceagent.text

private const val END_MARKS = ".?!。？！"
private const val HESITATION_MARKS = ".…"

/** voice_eval.metrics: "하, 참.", "맨발이요?", "잠시만요, 탐정님." open an answer rather than being one. */
private const val OPENER_MAX_LETTERS = 7
private const val MAX_FREE_OPENERS = 2

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
 * voice_eval.metrics.counted_sentences: how many of [sentences] count toward an answer's sentence
 * limit. Up to two short openers at the start are free, so an interjection or an echoed question
 * does not push the last sentence, often the denial, out.
 */
fun countedSentences(sentences: List<String>): Int =
    sentences.size - sentences.take(MAX_FREE_OPENERS).takeWhile(::isShortOpener).size

/** Letters as Python str.isalpha counts them, by code point. */
private fun isShortOpener(sentence: String): Boolean =
    sentence.codePoints().filter(Character::isLetter).count() <= OPENER_MAX_LETTERS

/** voice_eval.metrics: suspects only speak, so brackets hold a stage direction or a rule label. */
private const val READOUT_BRACKETS = "[]()（）【】"
private const val READOUT_TRAILING = " .!?。？！…~\"'”’」』"
private const val SUBJECT_PARTICLES = "이가은는"
private const val HANGUL_FIRST = 0xAC00
private const val HANGUL_COUNT = 11172
private const val FINALS = 28
private const val FINAL_BIEUP = 17

/**
 * voice_eval.metrics.looks_like_readout: a sentence no suspect would say aloud, a bracketed stage
 * direction or label, Latin letters, the [speaker]'s own name as the subject ("윤서진이 아는 사실에
 * 없는 질문이라 …"), or the plain "-다" of instructions ("윤하린은 이 말을 처음 듣는다."). Suspects
 * speak Korean, so English is a note the model wrote to itself ("Player asks about …", often after a
 * tag such as "[think]"). They speak 해요체 or 하십시오체, so "-ㅂ니다" and the propositive "-ㅂ시다"
 * are speech.
 */
fun looksLikeReadout(sentence: String, speaker: String = ""): Boolean {
    val text = pythonStrip(sentence)
    if (text.any { it in READOUT_BRACKETS }) return true
    if (text.any { it in 'a'..'z' || it in 'A'..'Z' }) return true
    if (speaker.isNotEmpty() && namesSpeaker(text, speaker)) return true
    val core = text.trimEnd { it in READOUT_TRAILING }
    return core.endsWith("다") && !isPoliteEnding(core)
}

/**
 * Suspects say 저 or 제가, so their full name and a subject particle is the prompt talking about them.
 * "윤서진이에요" and "윤서진입니다" introduce the speaker: no space follows the particle.
 */
private fun namesSpeaker(text: String, speaker: String): Boolean {
    if (!text.startsWith(speaker)) return false
    val rest = text.substring(speaker.length)
    return rest.length >= 2 && rest[0] in SUBJECT_PARTICLES && isPythonWhitespace(rest[1])
}

/** "-습니다", "-합니다", "-합시다": the syllable before 니다 or 시다 ends in ㅂ, unlike "아니다". */
private fun isPoliteEnding(core: String): Boolean {
    if (core.length < 3 || !(core.endsWith("니다") || core.endsWith("시다"))) return false
    val syllable = core[core.length - 3].code - HANGUL_FIRST
    return syllable in 0 until HANGUL_COUNT && syllable % FINALS == FINAL_BIEUP
}

/** voice_eval.metrics: "[짧게]", "(sigh)", "（한숨）", "【속마음】" and markdown asterisks around them. */
private val STAGE_DIRECTION = Regex("""\[[^\]]*\]|\([^)]*\)|（[^）]*）|【[^】]*】|\*""")

/**
 * voice_eval.metrics.strip_stage_directions: the sentence as the app speaks it, with bracketed stage
 * directions and asterisks removed and whitespace collapsed, or "" if no word is left. Unchanged if
 * it has none, so "[짧게] 어머, 탐정님." is spoken as "어머, 탐정님." before [looksLikeReadout] judges it.
 */
fun stripStageDirections(sentence: String): String {
    if (!STAGE_DIRECTION.containsMatchIn(sentence)) return sentence
    val rest = pythonSplit(STAGE_DIRECTION.replace(sentence, " ")).joinToString(" ")
    return if (rest.any(Char::isLetterOrDigit)) rest else ""
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
