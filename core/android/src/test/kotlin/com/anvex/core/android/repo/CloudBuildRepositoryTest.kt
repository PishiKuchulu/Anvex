package com.anvex.core.android.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * JVM tests for the pure parts of the cloud build repository: the generated
 * workflow template and the capped run-log decoding. The HTTP surface is not
 * exercised here (org.json is an android.jar stub in local unit tests).
 */
class CloudBuildRepositoryTest {

    private val repo = CloudBuildRepository(tokenProvider = { null })

    // ---- workflow template -------------------------------------------------

    @Test
    fun `workflow template is a bounded dispatch build and upload pipeline`() {
        val t = CloudBuildRepository.WORKFLOW_TEMPLATE
        assertTrue(t.contains("workflow_dispatch:"))
        assertTrue(t.contains("variant:"))
        assertTrue(t.contains("""default: "debug""""))
        assertTrue(t.contains("actions/checkout@v4"))
        assertTrue(t.contains("actions/setup-java@v4"))
        assertTrue(t.contains("--no-daemon"))
        assertTrue(t.contains("assemble"))
        assertTrue(t.contains("actions/upload-artifact@v4"))
        assertTrue(t.contains("**/build/outputs/apk/**/*.apk"))
        // 15-25 lines of YAML, spaces only (tabs are invalid YAML).
        val lines = t.trimEnd('\n').lines()
        assertTrue("template has ${lines.size} lines", lines.size in 15..25)
        assertFalse(t.contains('\t'))
    }

    @Test
    fun `workflow template never splices the raw input into the command`() {
        val t = CloudBuildRepository.WORKFLOW_TEMPLATE
        // The variant reaches the shell only through the VARIANT env var and
        // is capitalized with sed; no direct ${{ }} interpolation in run:.
        assertEquals(1, Regex("""\$\{\{ inputs\.variant \}\}""").findAll(t).count())
        assertTrue(t.contains("VARIANT: "))
        assertTrue(t.contains("""sed 's/^./\U&/'"""))
        assertFalse(t.contains("assemble\${{"))
    }

    // ---- run log decoding ---------------------------------------------------

    @Test
    fun `plain text log passes through and notes overflow`() {
        val text = repo.decodeRunLogArchive("hello".toByteArray(), overflow = true, cap = 512 * 1024)
        assertTrue(text.startsWith("hello"))
        assertTrue(text.contains("truncated"))
    }

    @Test
    fun `zip archive of per-job logs is concatenated with job headers`() {
        val zip = zipOf("1_build.txt" to "alpha\n", "2_test.txt" to "beta\n")
        val text = repo.decodeRunLogArchive(zip, overflow = false, cap = 512 * 1024)
        assertTrue(text.contains("── 1_build.txt ──"))
        assertTrue(text.contains("── 2_test.txt ──"))
        assertTrue(text.contains("alpha"))
        assertTrue(text.contains("beta"))
        assertTrue("job order preserved", text.indexOf("alpha") < text.indexOf("beta"))
        assertFalse(text.contains("truncated"))
    }

    @Test
    fun `zip bomb entries are capped at the byte budget`() {
        val big = "x".repeat(64 * 1024)
        val zip = zipOf("job.txt" to big)
        val cap = 16 * 1024
        val text = repo.decodeRunLogArchive(zip, overflow = false, cap = cap)
        assertTrue(
            "decoded ${text.length} chars for cap $cap",
            text.length <= cap + 128, // content + header + truncation note
        )
        assertTrue(text.contains("truncated"))
    }

    @Test
    fun `archives with too many entries stop at the entry cap`() {
        val entries = (1..(CloudBuildRepository.MAX_LOG_ENTRIES + 10)).associate { "job$it.txt" to "line\n" }
        val text = repo.decodeRunLogArchive(zipOf(*entries.map { it.key to it.value }.toTypedArray()), overflow = false)
        assertTrue(text.contains("truncated"))
        // only the first MAX_LOG_ENTRIES entries are decoded
        assertFalse(text.contains("── job${CloudBuildRepository.MAX_LOG_ENTRIES + 10}.txt ──"))
    }

    @Test
    fun `truncated zip payload keeps whatever decoded cleanly`() {
        val full = zipOf("job.txt" to "data\n")
        val cut = full.copyOf(full.size / 2) // corrupt the central directory
        val text = repo.decodeRunLogArchive(cut, overflow = false)
        // Either it decoded the stored (pre-central-directory) content or it
        // flagged truncation — but it must never throw.
        assertTrue(text.isNotEmpty())
    }

    // ---- helpers -------------------------------------------------------------

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
