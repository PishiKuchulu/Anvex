package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regression tests for the 2026-11-02 incident: ZIP attachment extraction
 * silently stopped at 200 entries / 2 MB, so an 899-entry source archive
 * lost its whole res/ tree and Gradle root files (they sit at the END of
 * the archive), and the reviewing model reported them as missing.
 */
class ArchiveTextExtractorTest {

    private class MemFs : ProjectFileSystem {
        val files = sortedMapOf<String, ByteArray>()
        override fun mkdirs(relativePath: String) {}
        override fun exists(relativePath: String): Boolean =
            files.keys.any { it == relativePath || it.startsWith("$relativePath/") }
        override fun isDirectory(relativePath: String): Boolean =
            files.keys.any { it.startsWith("$relativePath/") }
        override fun writeFile(relativePath: String, content: String) {
            files[relativePath] = content.toByteArray(Charsets.UTF_8)
        }
        override fun writeFileBytes(relativePath: String, bytes: ByteArray) {
            files[relativePath] = bytes
        }
        override fun readFile(relativePath: String): String? =
            files[relativePath]?.toString(Charsets.UTF_8)
        override fun readFileBytes(relativePath: String): ByteArray? = files[relativePath]
        override fun appendFile(relativePath: String, content: String) {}
        override fun delete(relativePath: String, recursive: Boolean): Boolean = false
        override fun list(relativePath: String): List<ProjectFileSystem.FileEntry> = emptyList()
        override fun move(from: String, to: String): Boolean = false
        override fun copy(from: String, to: String): Boolean = false
        override fun walk(): List<String> = files.keys.toList()
        fun text(path: String): String? = files[path]?.toString(Charsets.UTF_8)
    }

    private fun zipOf(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun tunedLikeZip(): ByteArray {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        // 250 source files first — the old 200-entry wall cut the archive here.
        repeat(250) { entries += "app/src/main/java/com/tuned/F%03d.kt".format(it) to "class F".toByteArray() }
        // res/ and the Gradle root files sit at the END of the archive,
        // exactly as in the real Tuned zip (res starts at entry 588 of 899).
        entries += "app/src/main/res/values/strings.xml" to "<resources/>".toByteArray()
        entries += "app/src/main/res/layout/main.xml" to "<LinearLayout/>".toByteArray()
        entries += "settings.gradle.kts" to "rootProject.name = \"Tuned\"".toByteArray()
        entries += "gradle.properties" to "org.gradle.jvmargs=-Xmx2g".toByteArray()
        entries += "gradle/wrapper/gradle-wrapper.properties" to "distributionUrl=x".toByteArray()
        entries += "gradle/wrapper/gradle-wrapper.jar" to byteArrayOf(0x50, 0x4B, 0x03)
        entries += "app/src/main/res/mipmap/icon.png" to byteArrayOf(0x89.toByte(), 0x50)
        return zipOf(entries)
    }

    @Test fun extractionGoesPastTheOld200EntryWall() {
        val fs = MemFs()
        val outcome = ArchiveTextExtractor.extract(fs, tunedLikeZip(), ".anvex/attachments/d-extracted")
        assertTrue(outcome.complete)
        assertEquals(257, outcome.archiveEntries)
        // Files the old cap silently dropped are all present now.
        assertEquals("<resources/>", fs.text(".anvex/attachments/d-extracted/app/src/main/res/values/strings.xml"))
        assertEquals("rootProject.name = \"Tuned\"", fs.text(".anvex/attachments/d-extracted/settings.gradle.kts"))
        // gradle.properties is extracted too — the old text-extension set
        // lacked "properties", which alone hid it from every extracted tree.
        assertEquals("org.gradle.jvmargs=-Xmx2g", fs.text(".anvex/attachments/d-extracted/gradle.properties"))
        assertEquals("distributionUrl=x", fs.text(".anvex/attachments/d-extracted/gradle/wrapper/gradle-wrapper.properties"))
        // Binaries stay in the archive, but they are NAMED as skipped, so
        // nobody can claim they do not exist.
        assertTrue(outcome.skippedNonText.contains("gradle/wrapper/gradle-wrapper.jar"))
        assertTrue(outcome.skippedNonText.contains("app/src/main/res/mipmap/icon.png"))
        val report = fs.text(".anvex/attachments/d-extracted/_EXTRACTION_REPORT.txt").orEmpty()
        assertTrue(report.contains("COMPLETE"))
        assertTrue(report.contains("gradle-wrapper.jar"))
    }

    @Test fun partialExtractionIsReportedNotSilent() {
        val fs = MemFs()
        val outcome = ArchiveTextExtractor.extract(
            fs, tunedLikeZip(), ".anvex/attachments/d-extracted", maxEntries = 10,
        )
        assertFalse(outcome.complete)
        assertTrue(outcome.droppedByEntryLimit > 0)
        assertTrue(outcome.summaryLine().contains("PARTIAL"))
        assertEquals(null, fs.text(".anvex/attachments/d-extracted/settings.gradle.kts"))
        val report = fs.text(".anvex/attachments/d-extracted/_EXTRACTION_REPORT.txt").orEmpty()
        assertTrue(report.contains("PARTIAL"))
        assertTrue(report.contains("not evidence"))
    }

    @Test fun oversizeFileIsSkippedWholeNeverTruncated() {
        val big = ByteArray(100_000) { 'a'.code.toByte() }
        val zip = zipOf(
            listOf(
                "src/Big.kt" to big,
                "src/Small.kt" to "ok".toByteArray(),
            ),
        )
        val fs = MemFs()
        val outcome = ArchiveTextExtractor.extract(fs, zip, "x-extracted", maxFileBytes = 1_000)
        // The big file is absent — NOT present with truncated content — and
        // the skip is reported with its true size; later files still extract.
        assertEquals(null, fs.text("x-extracted/src/Big.kt"))
        assertEquals(1, outcome.skippedOversize.size)
        assertTrue(outcome.skippedOversize.single().contains("100000"))
        assertEquals("ok", fs.text("x-extracted/src/Small.kt"))
        assertFalse(outcome.complete)
    }
}
