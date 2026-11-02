package com.anvex.app.project

import com.anvex.core.engine.PathSanitizer
import com.anvex.core.engine.ProjectFileSystem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Extracts the TEXT entries of an attached ZIP into the project workspace so
 * the agent can read the source with its normal tools.
 *
 * Completeness is part of the contract. The previous inline version silently
 * stopped at 200 entries / 2 MB: an 899-entry source archive lost every file
 * past entry 200 — its whole `res/` tree and all Gradle root files sit at the
 * end of the archive — and the reviewing model then reported those folders as
 * "missing from the project" (user incident, 2026-11-02). This extractor
 * therefore:
 *  - scans the WHOLE archive (names only past the write limits),
 *  - reports exactly what was written, what was skipped and why,
 *  - never writes a truncated file (an oversize entry is skipped whole and
 *    named, instead of being persisted half-way), and
 *  - writes a `_EXTRACTION_REPORT.txt` into the extracted folder itself, so
 *    the tree is self-describing for any later list/read.
 */
object ArchiveTextExtractor {

    const val DEFAULT_MAX_ENTRIES = 20_000
    const val DEFAULT_MAX_TOTAL_BYTES = 64L * 1024 * 1024
    const val DEFAULT_MAX_FILE_BYTES = 8L * 1024 * 1024
    const val REPORT_FILE = "_EXTRACTION_REPORT.txt"
    private const val MAX_NAMED_SKIPS = 50

    data class Outcome(
        val dir: String,
        /** Every entry in the archive, files and folders alike. */
        val archiveEntries: Int,
        val filesWritten: Int,
        val bytesWritten: Long,
        /** Non-text entries are never extracted; they stay in the saved archive. */
        val skippedNonText: List<String>,
        /** Text entries skipped whole because they exceeded a size limit. */
        val skippedOversize: List<String>,
        val skippedUnsafe: List<String>,
        val droppedByEntryLimit: Int,
        val droppedByByteBudget: Int,
    ) {
        val complete: Boolean
            get() = droppedByEntryLimit == 0 && droppedByByteBudget == 0 && skippedOversize.isEmpty()

        /** One-paragraph completeness note for the model-facing attachment text. */
        fun summaryLine(): String =
            if (complete) {
                "Extraction is COMPLETE: all $archiveEntries archive entries were processed and " +
                    "$filesWritten text files were written to that folder " +
                    "(${skippedNonText.size} non-text file(s) were not extracted — they exist only " +
                    "inside the saved archive). A $REPORT_FILE in that folder lists everything."
            } else {
                "Extraction is PARTIAL: $filesWritten of $archiveEntries archive entries were written " +
                    "to that folder; the rest were skipped by size/entry limits (see $REPORT_FILE in " +
                    "that folder for exactly what is missing). Files absent from that folder may still " +
                    "exist in the archive — do NOT conclude a file or folder is missing from the " +
                    "archive based on this tree."
            }

        fun reportText(): String = buildString {
            append("Archive extraction report\n")
            append("=========================\n")
            append("Archive entries (files and folders): ").append(archiveEntries).append('\n')
            append("Text files extracted: ").append(filesWritten).append(" (").append(bytesWritten).append(" bytes)\n")
            append("Status: ").append(
                if (complete) "COMPLETE — every text file in the archive is in this folder.\n"
                else "PARTIAL — this folder does NOT contain the whole archive.\n",
            )
            if (skippedNonText.isNotEmpty()) {
                append("\nNon-text files are never extracted (they remain inside the saved archive file): ")
                    .append(skippedNonText.size).append('\n')
                skippedNonText.take(MAX_NAMED_SKIPS).forEach { append("- ").append(it).append('\n') }
                if (skippedNonText.size > MAX_NAMED_SKIPS) {
                    append("- …and ").append(skippedNonText.size - MAX_NAMED_SKIPS).append(" more\n")
                }
            }
            if (skippedOversize.isNotEmpty()) {
                append("\nText files NOT extracted (size limits) — they exist only in the archive:\n")
                skippedOversize.take(MAX_NAMED_SKIPS).forEach { append("- ").append(it).append('\n') }
            }
            if (droppedByEntryLimit > 0) {
                append("\nEntries never reached because of the entry limit: ").append(droppedByEntryLimit).append('\n')
            }
            if (droppedByByteBudget > 0) {
                append("\nEntries skipped because the extraction byte budget ran out: ").append(droppedByByteBudget).append('\n')
            }
            if (skippedUnsafe.isNotEmpty()) {
                append("\nEntries blocked as unsafe paths: ").append(skippedUnsafe.size).append('\n')
                skippedUnsafe.take(MAX_NAMED_SKIPS).forEach { append("- ").append(it).append('\n') }
            }
            append("\nIMPORTANT: absence of a file in this folder is not evidence that it is absent ")
            append("from the archive. Check this report and the archive itself before claiming ")
            append("anything is missing.\n")
        }
    }

    fun extract(
        fs: ProjectFileSystem,
        bytes: ByteArray,
        dir: String,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
        maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    ): Outcome {
        fs.mkdirs(dir)
        var archiveEntries = 0
        var processed = 0
        var filesWritten = 0
        var bytesWritten = 0L
        var droppedByEntryLimit = 0
        var droppedByByteBudget = 0
        val skippedNonText = mutableListOf<String>()
        val skippedOversize = mutableListOf<String>()
        val skippedUnsafe = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                archiveEntries++
                if (!entry.isDirectory) {
                    when {
                        processed >= maxEntries -> droppedByEntryLimit++
                        bytesWritten >= maxTotalBytes -> droppedByByteBudget++
                        else -> {
                            processed++
                            val safe = runCatching { PathSanitizer.sanitize(entry.name) }.getOrNull()
                            when {
                                safe == null -> skippedUnsafe += entry.name.take(180)
                                !isTextExtension(safe) -> skippedNonText += safe
                                else -> {
                                    val budget = minOf(maxFileBytes, maxTotalBytes - bytesWritten)
                                    val (data, fullSize) = readEntryBounded(zip, budget)
                                    when {
                                        fullSize > budget -> skippedOversize += "$safe ($fullSize bytes)"
                                        data.isNotEmpty() -> {
                                            val parent = safe.substringBeforeLast('/', "")
                                            if (parent.isNotEmpty()) fs.mkdirs("$dir/$parent")
                                            fs.writeFileBytes("$dir/$safe", data)
                                            filesWritten++
                                            bytesWritten += data.size
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val outcome = Outcome(
            dir = dir,
            archiveEntries = archiveEntries,
            filesWritten = filesWritten,
            bytesWritten = bytesWritten,
            skippedNonText = skippedNonText,
            skippedOversize = skippedOversize,
            skippedUnsafe = skippedUnsafe,
            droppedByEntryLimit = droppedByEntryLimit,
            droppedByByteBudget = droppedByByteBudget,
        )
        fs.writeFile("$dir/$REPORT_FILE", outcome.reportText())
        return outcome
    }

    /**
     * Reads one entry, keeping at most [budget] bytes in memory but always
     * draining the entry so its true size is known: an oversize file is
     * reported with its real size and skipped whole, never written truncated.
     */
    private fun readEntryBounded(zip: ZipInputStream, budget: Long): Pair<ByteArray, Long> {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            total += n
            if (out.size() < budget) {
                val keep = minOf(n.toLong(), budget - out.size()).toInt()
                if (keep > 0) out.write(buffer, 0, keep)
            }
        }
        return out.toByteArray() to total
    }

    private fun isTextExtension(name: String): Boolean =
        when (name.substringAfterLast('.', "").lowercase()) {
            "txt", "md", "markdown", "json", "xml", "csv", "html", "htm", "css",
            "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "java", "py", "rb",
            "go", "rs", "yaml", "yml", "toml", "gradle", "kts",
            // "properties" was missing from the original set, which alone hid
            // gradle.properties from every extracted tree; scripts are text too.
            "properties", "sh", "bat", "cmd" -> true
            else -> false
        }
}
