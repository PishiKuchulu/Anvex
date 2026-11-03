package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-17: secret files must never reach model context — neither as contents nor as list lines. */
class ContextBuilderSecurityTest {

    @Test
    fun `secret files never reach model context`() {
        val fs = FakeFs()
        fs.writeFile(".env", "OPENAI_KEY=sk-live-secret")
        fs.writeFile("credentials.json", "{\"token\": \"ghp_secretvalue\"}")
        fs.writeFile("package.json", "{}")
        fs.writeFile("AndroidManifest.xml", "<manifest package=\"com.example\" />")
        val context = ContextBuilder.build(fs, openFilePath = ".env")
        assertFalse("secret content leaked", context.contains("sk-live-secret"))
        assertFalse("secret content leaked", context.contains("ghp_secretvalue"))
        assertFalse("non-open secret path leaked in list", context.contains("credentials.json"))
        assertTrue("open sensitive file should be explained, not silently dropped",
            context.contains("contents withheld"))
        assertTrue("Android priority file content missing", context.contains("<manifest"))
    }

    @Test
    fun `budget scales with the callers maxTotalChars`() {
        val fs = FakeFs()
        repeat(200) { i -> fs.writeFile("src/File$i.kt", "fun f$i() = $i") }
        val small = ContextBuilder.build(fs, maxTotalChars = 8_000)
        val big = ContextBuilder.build(fs, maxTotalChars = 96_000)
        assertTrue(small.length <= 8_000)
        assertTrue(big.length > small.length)
    }

    @Test fun contextBuildNeverCallsPerPathFileSize() {
        // Regression (2026-11-03): the file-list block asked fileSize() for
        // every listed path. On SAF-backed workspaces each call re-resolves
        // the path through the documents provider, so one chat send paid
        // hundreds of IPC round-trips and appeared to hang for minutes.
        // Sizes must ride along with the walk (walkWithSizes) instead.
        var fileSizeCalls = 0
        val backing = FakeFs()
        // A backend with native traversal sizes (as the SAF and local
        // implementations now provide): per-path lookups must not occur.
        val fs = object : ProjectFileSystem by backing {
            override fun walkWithSizes(): List<Pair<String, Long?>> =
                backing.files.map { (path, content) -> path to content.toByteArray().size.toLong() }.sortedBy { it.first }

            override fun fileSize(relativePath: String): Long? {
                fileSizeCalls++
                return backing.fileSize(relativePath)
            }
        }
        repeat(60) { backing.writeFile("src/pkg/F%02d.kt".format(it), "class F$it") }
        backing.writeFile("README.md", "hello")
        val context = ContextBuilder.build(fs)
        assertEquals(0, fileSizeCalls)
        assertTrue(context.contains("README.md (5 bytes)"))
        assertTrue(context.contains("Project file list (61 files):"))
    }

    @Test fun buildWithStatusSurfacesBackendFailureAsWarning() {
        // Regression (2026-11-03): a throwing walk (SAF permission loss)
        // used to degrade to the generic fallback text with NO signal —
        // the model proceeded blind and the user was never told.
        val backing = FakeFs()
        val fs = object : ProjectFileSystem by backing {
            override fun walkWithSizes(): List<Pair<String, Long?>> =
                throw IllegalStateException("backend down")
        }
        val result = ContextBuilder.buildWithStatus(fs)
        assertTrue(result.context.startsWith("Project context could not be read safely"))
        assertTrue(result.warning?.contains("Project context unavailable (IllegalStateException)") == true)
    }

    @Test fun buildWithStatusSuccessHasNoWarning() {
        val fs = FakeFs()
        fs.writeFile("a.txt", "hello")
        assertEquals(null, ContextBuilder.buildWithStatus(fs).warning)
    }

    @Test fun laterPriorityFilesAreNotStarvedByOneHugeEarlierFile() {
        // Regression (2026-11-03): every priority file was capped at the
        // same 14k, so a huge first file consumed the content budget and
        // later key files were truncated to whatever crumbs remained —
        // here the LAST file's tail marker only survives with fair shares.
        val fs = FakeFs()
        fs.writeFile("src/open.txt", "OPEN " + "o".repeat(12_995))
        fs.writeFile("src/p1.txt", "P1 " + "a".repeat(11_997))
        fs.writeFile("src/p2.txt", "b".repeat(8_994) + "P2TAIL")
        val context = ContextBuilder.build(
            fs,
            openFilePath = "src/open.txt",
            mentionedFiles = listOf("src/p1.txt", "src/p2.txt"),
            maxTotalChars = 36_000,
        )
        assertTrue("last priority file lost its tail to budget starvation", context.contains("P2TAIL"))
    }
}
