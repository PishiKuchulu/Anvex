package com.anvex.core.android.build

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LOG-005 (forensic audit, 2026-11-07): entry names containing ".." as
 * part of a segment (`a..b.txt`) are legitimate files and must extract;
 * an actual `..` segment is traversal and must still be rejected, and
 * the canonical-path containment check remains the hard guarantee.
 */
class SafeDirUnzipTest {

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "anvex-unzip-test-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun `double dot inside a file name extracts`() {
        val dir = tempDir()
        val count = SafeDirUnzip.extract(
            ByteArrayInputStream(zipOf("a..b.txt" to "hello", "dir/c..d/e.txt" to "x")),
            dir,
        )
        assertEquals(2, count)
        assertEquals("hello", File(dir, "a..b.txt").readText())
        assertEquals("x", File(dir, "dir/c..d/e.txt").readText())
    }

    @Test
    fun `dot dot segment is still rejected`() {
        val dir = tempDir()
        val ex = runCatching {
            SafeDirUnzip.extract(ByteArrayInputStream(zipOf("../evil.txt" to "x")), dir)
        }.exceptionOrNull()
        assertTrue(ex is SecurityException)
        assertFalse(File(dir.parentFile, "evil.txt").exists())
    }
}
