package com.anvex.core.engine

/**
 * Path sanitization for all relative project paths. Rejects traversal
 * (`..` segments), absolute paths, windows drive paths, null bytes, and
 * backslash separators.
 */
object PathSanitizer {

    fun sanitize(relativePath: String): String {
        require(!relativePath.contains('\u0000')) { "Null byte in path" }
        val raw = relativePath.trim().replace('\\', '/')
        require(raw.isNotEmpty()) { "Empty path" }
        require(!raw.startsWith("/") && !Regex("""^[A-Za-z]:""").containsMatchIn(raw)) {
            "Absolute paths are not allowed: $relativePath"
        }
        val parts = ArrayList<String>()
        for (seg in raw.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> {
                    if (parts.isEmpty()) throw IllegalArgumentException("Path traversal rejected: $relativePath")
                    parts.removeAt(parts.size - 1)
                }
                else -> {
                    require(seg.length <= 255) { "Path segment too long" }
                    require(!seg.contains('\u0000')) { "Bad segment" }
                    // v4.10.1 (SEC-11): control characters and bidi overrides
                    // in file names can forge how names render in the UI
                    // (e.g. U+202E making "exe.txt" display as text) — reject.
                    require(seg.none { it.code < 0x20 || it.code == 0x7F || it.code in 0x202A..0x202E || it.code in 0x2066..0x2069 }) {
                        "Control or bidi characters are not allowed in path segments"
                    }
                    parts.add(seg)
                }
            }
        }
        require(parts.isNotEmpty()) { "Empty path after normalization" }
        // Reject reserved names on the fs we control
        return parts.joinToString("/")
    }

    fun sanitizeOrNull(relativePath: String): String? = try {
        sanitize(relativePath)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun parent(path: String): String? {
        val idx = path.lastIndexOf('/')
        return if (idx <= 0) (if (idx == 0) "" else null) else path.substring(0, idx)
    }

    fun fileName(path: String): String = path.substringAfterLast('/')
}
