package com.anvex.core.engine

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Project ZIP import/export (brief §27) with explicit protections:
 *  - zip-slip rejection ([PathSanitizer] on every entry)
 *  - zip-bomb guards: entry count, per-entry and total uncompressed caps
 *  - no executing / interpreting anything from the archive
 */
object ZipCodec {

    const val MAX_ENTRIES = 10_000
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    class ZipSecurityException(message: String) : Exception(message)

    /**
     * Paths that never belong in an export/backup archive (v4.10.1, BUG-11):
     * dependency/VCS/build junk that made exports of real projects blow the
     * entry/size caps and fail outright, plus runtime log files and crash
     * residue (`.<name>.tmp-<uuid>` atomic-write leftovers).
     */
    fun isExportable(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/')
        if (parts.any { it == ".git" || it == "node_modules" || it == ".gradle" || it == ".idea" || it == ".anvex-pi" }) return false
        val buildIndex = parts.indexOfFirst { it == "build" }
        if (buildIndex >= 0 && parts.take(buildIndex).none { it == "src" }) return false
        val name = parts.last()
        if (name.startsWith(".anvex-shell-") || name.startsWith(".anvex-node-")) return false
        // Leftover temp files from an interrupted atomic write (".<name>.tmp-<uuid>").
        if (name.startsWith(".") && name.contains(".tmp-")) return false
        if (path.startsWith(".anvex/logs/")) return false
        return true
    }

    /**
     * M-06: secrets never leave the device in a shared archive.
     * [excludeSecrets] is true by default (the SAF share/export path);
     * the password-encrypted backup path passes false deliberately —
     * a backup that silently drops `.env` restores a broken project,
     * and its whole archive is encrypted with the user's password.
     * Skipped files are reported through [onSkipped] (reason included)
     * instead of vanishing silently, as the old `?: continue` did.
     */
    fun export(
        fs: ProjectFileSystem,
        out: OutputStream,
        onEntry: (String) -> Unit = {},
        excludeSecrets: Boolean = true,
        onSkipped: (String, String) -> Unit = { _, _ -> },
    ) {
        ZipOutputStream(out.buffered()).use { zip ->
            val files = fs.walk().filter {
                isExportable(it) &&
                    !(excludeSecrets && (SensitivePaths.isSecretFile(it) || it.startsWith(".anvex/")))
            }
            if (files.size > MAX_ENTRIES) throw ZipSecurityException("Project exceeds $MAX_ENTRIES files")
            var total = 0L
            for (path in files) {
                if (excludeSecrets && SensitivePaths.isSecretFile(path)) {
                    onSkipped(path, "secret file")
                    continue
                }
                val bytes = fs.readFileBytes(path)
                if (bytes == null) {
                    onSkipped(path, "unreadable")
                    continue
                }
                if (bytes.size.toLong() > MAX_ENTRY_BYTES) throw ZipSecurityException("Entry '$path' exceeds size cap")
                total += bytes.size
                if (total > MAX_TOTAL_BYTES) throw ZipSecurityException("Project exceeds total archive size cap")
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
                onEntry(path)
            }
        }
    }

    fun import(input: InputStream, fs: ProjectFileSystem, onEntry: (String) -> Unit = {}): Int {
        var count = 0
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                try {
                    if (++count > MAX_ENTRIES) {
                        throw ZipSecurityException("Archive exceeds $MAX_ENTRIES entries")
                    }
                    if (entry.isDirectory) {
                        val n = entry.name?.trimEnd('/')?.takeIf { it.isNotBlank() }
                            ?: throw ZipSecurityException("Archive contains an invalid directory entry")
                        val safe = PathSanitizer.sanitizeOrNull(n)
                            ?: throw ZipSecurityException("Rejected unsafe directory path '$n'")
                        fs.mkdirs(safe)
                        continue
                    }
                    if (entry.size > MAX_ENTRY_BYTES) {
                        throw ZipSecurityException("Entry '${entry.name}' too large")
                    }
                    val name = PathSanitizer.sanitizeOrNull(entry.name)
                        ?: throw ZipSecurityException("Rejected unsafe entry path '${entry.name}'")
                    val bounded = CountingInputStream(zip, MAX_ENTRY_BYTES) { delta ->
                        total += delta
                        if (total > MAX_TOTAL_BYTES) throw ZipSecurityException("Archive total size exceeds limit")
                    }
                    PathSanitizer.parent(name)?.let(fs::mkdirs)
                    fs.writeFileStream(name, bounded, MAX_ENTRY_BYTES)
                    onEntry(name)
                } finally {
                    zip.closeEntry()
                }
            }
        }
        return count
    }

    /** InputStream wrapper that enforces per-entry and archive-wide byte ceilings while streaming. */
    private class CountingInputStream(
        private val delegate: InputStream,
        private val maxEntry: Long,
        private val onBytes: (Long) -> Unit,
    ) : InputStream() {
        private var entryBytes = 0L
        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) { entryBytes++; onBytes(1); checkLimit() }
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = delegate.read(buffer, offset, length)
            if (read > 0) { entryBytes += read; onBytes(read.toLong()); checkLimit() }
            return read
        }
        private fun checkLimit() {
            if (entryBytes > maxEntry) throw ZipSecurityException("ZIP entry exceeds size cap")
        }
        override fun close() { /* ZipInputStream owns the underlying stream. */ }
    }
}
