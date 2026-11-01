package dev.voiceagent.speechbench

/** Keeps letters and digits only, so spacing and punctuation differences are not errors. */
fun normalizeForCer(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()

/** Character error rate: edit distance divided by the expected length (after normalizing). */
fun characterErrorRate(expected: String, recognized: String): Double {
    val reference = normalizeForCer(expected)
    val hypothesis = normalizeForCer(recognized)
    if (reference.isEmpty()) return if (hypothesis.isEmpty()) 0.0 else 1.0
    return editDistance(reference, hypothesis).toDouble() / reference.length
}

private fun editDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        for (j in 1..b.length) {
            val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
        }
        previous = current
    }
    return previous[b.length]
}
