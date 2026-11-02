package dev.voiceagent.identify

/**
 * `difflib.SequenceMatcher(None, a, b).ratio()`, which identify.py uses for fuzzy names. Nothing
 * is junk, and difflib's "popular element" heuristic only starts at 200 characters in [b]; fuzzy
 * matching compares short name windows, so neither applies here.
 */
internal object SequenceMatcher {
    fun ratio(a: String, b: String): Double {
        val length = a.length + b.length
        return if (length == 0) 1.0 else 2.0 * matches(a, b) / length
    }

    /** Characters in difflib's matching blocks: the longest common block, then both sides of it. */
    private fun matches(a: String, b: String): Int {
        val positions = HashMap<Char, MutableList<Int>>()
        b.forEachIndexed { j, char -> positions.getOrPut(char) { mutableListOf() }.add(j) }
        var total = 0
        val queue = ArrayDeque<IntArray>()
        queue.addLast(intArrayOf(0, a.length, 0, b.length))
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo, bhi) = queue.removeLast()
            val (i, j, k) = longestMatch(a, positions, alo, ahi, blo, bhi)
            if (k == 0) continue
            total += k
            if (alo < i && blo < j) queue.addLast(intArrayOf(alo, i, blo, j))
            if (i + k < ahi && j + k < bhi) queue.addLast(intArrayOf(i + k, ahi, j + k, bhi))
        }
        return total
    }

    /**
     * difflib's find_longest_match without junk: the longest block, ties broken by the smallest
     * start in a, then in b. Returns (start in a, start in b, size).
     */
    private fun longestMatch(
        a: String,
        positions: Map<Char, List<Int>>,
        alo: Int,
        ahi: Int,
        blo: Int,
        bhi: Int,
    ): Triple<Int, Int, Int> {
        var bestI = alo
        var bestJ = blo
        var bestSize = 0
        var lengthEndingAt = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val next = HashMap<Int, Int>()
            for (j in positions[a[i]].orEmpty()) {
                if (j < blo) continue
                if (j >= bhi) break
                val k = (lengthEndingAt[j - 1] ?: 0) + 1
                next[j] = k
                if (k > bestSize) {
                    bestI = i - k + 1
                    bestJ = j - k + 1
                    bestSize = k
                }
            }
            lengthEndingAt = next
        }
        return Triple(bestI, bestJ, bestSize)
    }
}
