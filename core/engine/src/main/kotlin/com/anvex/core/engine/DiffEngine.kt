package com.anvex.core.engine

/**
 * Line diff via LCS dynamic programming (role of the `diff` npm package upstream).
 * Bounded: inputs beyond [MAX_LINES] fall back to a whole-file replace diff so a
 * pathological file cannot exhaust CPU or memory.
 */
object DiffEngine {

    const val MAX_LINES = 4000

    enum class Op { EQUAL, INSERT, DELETE }

    data class DiffLine(val op: Op, val text: String, val oldLine: Int? = null, val newLine: Int? = null)

    fun diff(oldText: String, newText: String): List<DiffLine> {
        val old = oldText.split('\n')
        val new = newText.split('\n')
        if (old.size > MAX_LINES || new.size > MAX_LINES) {
            return fallback(old, new)
        }
        return lcs(old, new)
    }

    /** Produces a compact change summary (+ added / ~ modified / - deleted file level). */
    fun stats(oldText: String, newText: String): Stats {
        val lines = diff(oldText, newText)
        return Stats(
            added = lines.count { it.op == Op.INSERT },
            removed = lines.count { it.op == Op.DELETE },
        )
    }

    data class Stats(val added: Int, val removed: Int)

    private fun lcs(old: List<String>, new: List<String>): List<DiffLine> {
        val n = old.size
        val m = new.size
        // DP table of (n+1) x (m+1) Ints
        val dp = IntArray((n + 1) * (m + 1))
        fun idx(i: Int, j: Int) = i * (m + 1) + j
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[idx(i, j)] = if (old[i] == new[j]) dp[idx(i + 1, j + 1)] + 1
                else maxOf(dp[idx(i + 1, j)], dp[idx(i, j + 1)])
            }
        }
        val out = ArrayList<DiffLine>(n + m)
        var i = 0
        var j = 0
        var oldNo = 1
        var newNo = 1
        while (i < n && j < m) {
            when {
                old[i] == new[j] -> {
                    out.add(DiffLine(Op.EQUAL, old[i], oldNo, newNo)); i++; j++; oldNo++; newNo++
                }
                dp[idx(i + 1, j)] >= dp[idx(i, j + 1)] -> {
                    out.add(DiffLine(Op.DELETE, old[i], oldNo, null)); i++; oldNo++
                }
                else -> {
                    out.add(DiffLine(Op.INSERT, new[j], null, newNo)); j++; newNo++
                }
            }
        }
        while (i < n) { out.add(DiffLine(Op.DELETE, old[i], oldNo, null)); i++; oldNo++ }
        while (j < m) { out.add(DiffLine(Op.INSERT, new[j], null, newNo)); j++; newNo++ }
        return out
    }

    private fun fallback(old: List<String>, new: List<String>): List<DiffLine> {
        val out = ArrayList<DiffLine>()
        old.forEachIndexed { k, l -> out.add(DiffLine(Op.DELETE, l, k + 1, null)) }
        new.forEachIndexed { k, l -> out.add(DiffLine(Op.INSERT, l, null, k + 1)) }
        return out
    }
}
