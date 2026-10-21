package com.anvex.core.engine

/**
 * v4.7: minimal semantic-version handling for release tags — the in-app
 * updater compares the installed versionName against GitHub release tags.
 * Pure and JVM-tested; only strict `x.y.z` (optional `v` prefix, optional
 * pre-release/build suffix) counts as a version — anything else (e.g. the
 * rolling `latest` tag) is not a version and never wins a comparison.
 */
object VersionNumbers {

    private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""")

    fun parse(tagOrVersion: String): List<Int>? =
        PATTERN.matchEntire(tagOrVersion.trim())?.let { m ->
            listOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }

    /** Like [Comparable.compareTo]; an unparsable value sorts below any version. */
    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        if (pa == null && pb == null) return 0
        if (pa == null) return -1
        if (pb == null) return 1
        for (i in 0..2) if (pa[i] != pb[i]) return pa[i].compareTo(pb[i])
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0
}
