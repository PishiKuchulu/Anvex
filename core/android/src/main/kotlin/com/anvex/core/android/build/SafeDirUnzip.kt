package com.anvex.core.android.build

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * v4.0 (GOAL 3): zip extraction with zip-slip and zip-bomb protections.
 *
 * Hard limits: [MAX_ENTRIES] entries, [MAX_TOTAL_BYTES] total uncompressed
 * bytes, [MAX_ENTRY_BYTES] per entry. Entries with absolute paths, `..`
 * segments or directory-traversal names are rejected. Symbolic-link entries
 * are skipped on runtimes that expose the zip unix mode (best-effort; see
 * [isUnixSymlink]).
 */
object SafeDirUnzip {

    const val MAX_ENTRIES = 20_000
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
    const val MAX_ENTRY_BYTES = 512L * 1024 * 1024 // 512 MB
    private const val BUFFER = 64 * 1024

    /**
     * Extract [input] into [target]. Returns the number of files written.
     * @throws SecurityException on a zip-slip attempt or unsafe entry
     * @throws IllegalStateException when a hard limit is exceeded
     */
    fun extract(input: java.io.InputStream, target: File): Int {
        require(target.isDirectory) { "Target must be an existing directory" }
        var count = 0
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry: ZipEntry = zip.nextEntry ?: break
                try {
                    if (count >= MAX_ENTRIES) error("Bundle has too many entries (limit $MAX_ENTRIES).")
                    val raw = entry.name
                    // Reject absolute paths and any .. segment before touching the filesystem.
                    if (raw.startsWith("/") || raw.contains('\\') && File(raw).isAbsolute) {
                        throw SecurityException("Absolute entry path: $raw")
                    }
                    val segments = raw.split('/').filter { it.isNotEmpty() }
                    if (segments.any { it == ".." }) throw SecurityException("Zip-slip entry: $raw")
                    if (raw.contains("..")) throw SecurityException("Zip-slip entry: $raw")

                    if (entry.isDirectory) {
                        File(target, raw).mkdirs()
                        continue
                    }
                    // Skip link entries; never materialize a symlink from a bundle.
                    if (entry.isUnixSymlink) continue

                    val out = File(target, raw)
                    out.parentFile?.mkdirs()
                    if (out.canonicalPath != File(target, raw).canonicalPath ||
                        !out.canonicalPath.startsWith(target.canonicalPath + File.separator)
                    ) {
                        throw SecurityException("Entry escapes target directory: $raw")
                    }
                    var written = 0L
                    val sizeLimit = minOf(entry.size.takeIf { it > 0 } ?: MAX_ENTRY_BYTES, MAX_ENTRY_BYTES)
                    out.outputStream().use { fos ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            written += n
                            if (written > sizeLimit) throw IllegalStateException("Entry exceeds size limit: $raw")
                            fos.write(buf, 0, n)
                        }
                    }
                    total += written
                    if (total > MAX_TOTAL_BYTES) throw IllegalStateException("Bundle exceeds total size limit.")
                    out.setReadable(true, false)
                    out.setExecutable(raw.contains("/bin/"), false)
                    count++
                } finally {
                    zip.closeEntry()
                }
            }
        }
        return count
    }

    /**
     * Best-effort symlink detection: the zip unix mode is not part of the
     * public java.util.zip API (it is resolved reflectively when the runtime
     * exposes it). Where it is unavailable, entries are extracted as plain
     * files — [java.io.FileOutputStream] can never materialize a symlink, so
     * the canonical-path checks above remain the real zip-slip protection.
     */
    private val unixModeMethod: java.lang.reflect.Method? by lazy {
        runCatching { ZipEntry::class.java.getMethod("getUnixMode") }.getOrNull()
    }

    private val ZipEntry.isUnixSymlink: Boolean
        get() = (unixModeMethod?.invoke(this) as? Int)?.let { (it and 0xF000) == 0xA000 } ?: false
}
