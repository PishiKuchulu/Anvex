package com.anvex.core.engine

/** Fast, bounded project-wide text search/replace over the abstract workspace. */
object ProjectSearch {
    data class Match(val path: String, val line: Int, val column: Int, val preview: String)
    data class ReplaceResult(val path: String, val replacements: Int)

    private val ignoredRoots = setOf(".git", ".gradle", "build", "node_modules", ".idea", ".anvex")

    fun search(fs: ProjectFileSystem, query: String, caseSensitive: Boolean = false, maxMatches: Int = 2000): List<Match> {
        if (query.isEmpty() || maxMatches <= 0) return emptyList()
        val needle = if (caseSensitive) query else query.lowercase()
        val out = ArrayList<Match>()
        for (path in fs.walk()) {
            if (out.size >= maxMatches) break
            if (shouldSkip(path)) continue
            val size = fs.fileSize(path) ?: continue
            if (size > MAX_TEXT_FILE_BYTES) continue
            val text = fs.readFile(path) ?: continue
            if (text.indexOf('\u0000') >= 0) continue
            text.lineSequence().forEachIndexed { index, line ->
                if (out.size >= maxMatches) return@forEachIndexed
                val hay = if (caseSensitive) line else line.lowercase()
                var start = hay.indexOf(needle)
                while (start >= 0 && out.size < maxMatches) {
                    out += Match(path, index + 1, start + 1, line.trim().take(240))
                    start = hay.indexOf(needle, start + needle.length.coerceAtLeast(1))
                }
            }
        }
        return out
    }

    fun replaceAll(fs: ProjectFileSystem, query: String, replacement: String, caseSensitive: Boolean = false, maxFiles: Int = 500): List<ReplaceResult> {
        if (query.isEmpty() || maxFiles <= 0) return emptyList()
        val out = ArrayList<ReplaceResult>()
        for (path in fs.walk()) {
            if (out.size >= maxFiles) break
            if (shouldSkip(path)) continue
            val size = fs.fileSize(path) ?: continue
            if (size > MAX_TEXT_FILE_BYTES) continue
            val old = fs.readFile(path) ?: continue
            if (old.indexOf('\u0000') >= 0) continue
            val newText: String
            val count: Int
            if (caseSensitive) {
                count = countOccurrences(old, query)
                if (count == 0) continue
                newText = old.replace(query, replacement)
            } else {
                val regex = Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
                count = regex.findAll(old).count()
                if (count == 0) continue
                newText = regex.replace(old, replacement)
            }
            fs.writeFile(path, newText)
            out += ReplaceResult(path, count)
        }
        return out
    }

    private fun shouldSkip(path: String): Boolean = path.split('/').firstOrNull() in ignoredRoots

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var i = text.indexOf(needle)
        while (i >= 0) { count++; i = text.indexOf(needle, i + needle.length.coerceAtLeast(1)) }
        return count
    }

    private const val MAX_TEXT_FILE_BYTES = 4L * 1024L * 1024L
}
