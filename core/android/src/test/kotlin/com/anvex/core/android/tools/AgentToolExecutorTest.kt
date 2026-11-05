package com.anvex.core.android.tools

import com.anvex.core.engine.BoltAction
import com.anvex.core.engine.ProjectFileSystem
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class AgentToolExecutorTest {
    private fun executor(vararg ip: String) = AgentToolExecutor(resolver = { ip.map { InetAddress.getByName(it) } })

    @Test fun rejectsPrivateAndLoopbackTargets() {
        for (ip in listOf("127.0.0.1", "10.0.0.5", "192.168.1.9", "169.254.1.1", "100.64.0.1", "fd00::1")) {
            val failed = runCatching { executor(ip).checkPublicHttps("https://example.com/".toHttpUrl()) }.isFailure
            assertTrue("$ip should be blocked", failed)
        }
    }

    @Test fun connectTimeDnsRefusesPrivateAnswers() {
        // The same predicate backs the OkHttp Dns hook, so a rebinding host cannot pass the
        // pre-check with a public IP and then connect to a private one.
        assertFalse(isPublicAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(isPublicAddress(InetAddress.getByName("::1")))
        assertFalse(isPublicAddress(InetAddress.getByName("192.168.0.10")))
        assertFalse(isPublicAddress(InetAddress.getByName("100.64.0.1")))
        assertTrue(isPublicAddress(InetAddress.getByName("93.184.216.34")))
    }

    @Test fun fakeIpVpnPoolAndTranslatedAddressesAreJudgedByTheirTarget() {
        // 198.18.0.0/15 is the Fake-IP pool of sing-box/Clash-style VPNs: behind one, every
        // public hostname resolves there. It must not be refused (v4.14.5 regression test).
        assertTrue(isPublicAddress(InetAddress.getByName("198.18.0.7")))
        // NAT64 / 6to4 are judged by the IPv4 they embed, not blanket-blocked.
        assertTrue(isPublicAddress(InetAddress.getByName("64:ff9b::5db8:d822"))) // 93.184.216.34
        assertFalse(isPublicAddress(InetAddress.getByName("64:ff9b::c0a8:101"))) // 192.168.1.1
        assertTrue(isPublicAddress(InetAddress.getByName("2002:5db8:d822::"))) // 93.184.216.34
        assertFalse(isPublicAddress(InetAddress.getByName("2002:c0a8:101::"))) // 192.168.1.1
    }

    @Test fun mixedResolutionPassesWhenAnyAnswerIsPublic() {
        // Split-DNS/VPN resolvers may add a non-public record next to the public one; the
        // connect-time filter drops it, so the pre-check only requires one public answer.
        executor("192.168.1.1", "93.184.216.34").checkPublicHttps("https://example.com/".toHttpUrl())
        val failed = runCatching {
            executor("10.0.0.5", "192.168.1.1").checkPublicHttps("https://example.com/".toHttpUrl())
        }.isFailure
        assertTrue("all-private resolution should still be blocked", failed)
    }

    @Test fun allowsPublicHttpsAndRejectsHttp() {
        executor("93.184.216.34").checkPublicHttps("https://example.com/".toHttpUrl())
        assertTrue(runCatching { executor("93.184.216.34").checkPublicHttps("http://example.com/".toHttpUrl()) }.isFailure)
    }

    @Test fun htmlToTextDropsScriptsAndTags() {
        val text = executor("93.184.216.34").htmlToText("<html><head><title>x</title></head><body><script>evil()</script><p>Hello &amp; <b>world</b></p></body></html>")
        assertEquals("Hello & world", text)
        assertFalse(text.contains("evil"))
    }

    // ---- list tool: a capped view must never read as a complete tree ----
    // (2026-11-02 incident: an 899-file extracted archive listed flat with a
    // 300-entry alphabetical cap; the res/ subtree sorted past the slice and
    // the reviewing model reported it as missing.)

    private class ListFs(files: Map<String, String>) : ProjectFileSystem {
        private val files = files.toSortedMap()
        override fun mkdirs(relativePath: String) {}
        override fun exists(relativePath: String): Boolean =
            files.keys.any { it == relativePath || it.startsWith("$relativePath/") }
        override fun isDirectory(relativePath: String): Boolean =
            files.keys.any { it.startsWith("$relativePath/") }
        override fun writeFile(relativePath: String, content: String) {}
        override fun writeFileBytes(relativePath: String, bytes: ByteArray) {}
        override fun readFile(relativePath: String): String? = files[relativePath]
        override fun readFileBytes(relativePath: String): ByteArray? =
            files[relativePath]?.toByteArray(Charsets.UTF_8)
        override fun appendFile(relativePath: String, content: String) {}
        override fun delete(relativePath: String, recursive: Boolean): Boolean = false
        override fun list(relativePath: String): List<ProjectFileSystem.FileEntry> = emptyList()
        override fun move(from: String, to: String): Boolean = false
        override fun copy(from: String, to: String): Boolean = false
        override fun walk(): List<String> = files.keys.toList()
    }

    @Test fun listingNeverCallsPerPathFileSize() {
        // Same contract as ContextBuilder (2026-11-03): sizes must ride
        // along with the walk, not be re-resolved per listed path.
        var fileSizeCalls = 0
        val fs = object : ProjectFileSystem by ListFs(mapOf("a.txt" to "1", "dir/b.txt" to "22")) {
            override fun walkWithSizes(): List<Pair<String, Long?>> =
                listOf("a.txt" to 1L, "dir/b.txt" to 2L)

            override fun fileSize(relativePath: String): Long? {
                fileSizeCalls++
                return null
            }
        }
        val text = listTool(fs, ".")
        assertEquals(0, fileSizeCalls)
        assertTrue(text.contains("a.txt (1 bytes)"))
        assertTrue(text.contains("dir/b.txt (2 bytes)"))
    }

    private fun listTool(fs: ProjectFileSystem, path: String): String = runBlocking {
        AgentToolExecutor(resolver = { listOf(InetAddress.getByName("93.184.216.34")) })
            .run(BoltAction.ToolAction("list", path), fs, webAllowed = false).text
    }

    @Test fun cappedListingBecomesDirectorySummaryThatStillNamesLateFolders() {
        val files = buildMap {
            repeat(400) { put("app/src/main/java/com/tuned/pkg/F%03d.kt".format(it), "x") }
            repeat(120) { put("app/src/main/res/values/R%03d.xml".format(it), "x") }
            put("settings.gradle.kts", "x")
        }
        val text = listTool(ListFs(files), ".")
        assertTrue(text.startsWith("Listing 521 file(s)"))
        // The res subtree — alphabetically past any flat 300-slice — is named
        // with its exact count, so it cannot be reported as missing.
        assertTrue(text.contains("app/src/main/res/ — 120 file(s)"))
        assertTrue(text.contains("app/src/main/res/values/ — 120 file(s)"))
        assertTrue(text.contains("PARTIAL"))
        assertFalse(text.contains("F399.kt"))
    }

    @Test fun smallListingStaysFlatWithHeader() {
        val fs = ListFs(mapOf("a.txt" to "1", "dir/b.txt" to "2", "dir/c.txt" to "3"))
        val text = listTool(fs, ".")
        assertTrue(text.startsWith("Listing 3 file(s) under '.':"))
        assertTrue(text.contains("dir/b.txt (1 bytes)"))
        assertFalse(text.contains("PARTIAL"))
    }

    @Test fun subfolderListingShowsItsOwnFiles() {
        val files = buildMap {
            repeat(400) { put("src/main/F%03d.kt".format(it), "x") }
            repeat(5) { put("src/res/R$it.xml", "x") }
        }
        val text = listTool(ListFs(files), "src/res")
        assertTrue(text.startsWith("Listing 5 file(s) under 'src/res':"))
        assertTrue(text.contains("src/res/R4.xml"))
        assertFalse(text.contains("PARTIAL"))
    }

    @Test fun missingPathIsReportedAsNotFound() {
        val text = listTool(ListFs(mapOf("a.txt" to "1")), "no/such/dir")
        assertTrue(text.contains("Path not found in this workspace"))
    }

    // ---- read tool: paged windows over large files ----
    // (2026-11-06: reads used to return only the first 60,000 chars of a
    // file, so a review of a 64K ViewModel silently never saw its tail —
    // and re-reading returned the same head. Windows fix that.)

    private fun readResult(fs: ProjectFileSystem, action: BoltAction.ToolAction): AgentToolExecutor.ToolResult = runBlocking {
        AgentToolExecutor(resolver = { listOf(InetAddress.getByName("93.184.216.34")) })
            .run(action, fs, webAllowed = false)
    }

    @Test fun smallFileReadIsReturnedWholeWithoutFooter() {
        val r = readResult(ListFs(mapOf("a.txt" to "hello world")), BoltAction.ToolAction("read", "a.txt"))
        assertTrue(r.ok)
        assertEquals("hello world", r.text)
    }

    @Test fun firstWindowOfLargeFileNamesItsRangeAndContinuation() {
        val content = "x".repeat(65_000) + "THE-END"
        val r = readResult(ListFs(mapOf("big.kt" to content)), BoltAction.ToolAction("read", "big.kt"))
        assertTrue(r.ok)
        assertTrue(r.text.startsWith("x".repeat(1_000)))
        assertTrue(r.text.contains("chars 0–60000 of 65007"))
        assertTrue(r.text.contains("5007 chars remain — read again with offset=60000"))
        assertFalse(r.text.contains("THE-END"))
    }

    @Test fun secondWindowReachesTheTail() {
        val content = "x".repeat(65_000) + "THE-END"
        val r = readResult(ListFs(mapOf("big.kt" to content)), BoltAction.ToolAction("read", "big.kt", offset = 60_000))
        assertTrue(r.ok)
        assertTrue(r.text.contains("THE-END"))
        assertTrue(r.text.contains("chars 60000–65007 of 65007"))
        assertFalse(r.text.contains("chars remain"))
    }

    @Test fun explicitLimitShrinksTheWindow() {
        val r = readResult(ListFs(mapOf("a.txt" to "y".repeat(500))), BoltAction.ToolAction("read", "a.txt", limit = 100))
        assertTrue(r.ok)
        assertTrue(r.text.contains("chars 0–100 of 500"))
        assertTrue(r.text.contains("offset=100"))
    }

    @Test fun offsetPastEndIsAnErrorNotAnEmptySuccess() {
        val r = readResult(ListFs(mapOf("a.txt" to "short")), BoltAction.ToolAction("read", "a.txt", offset = 1_000))
        assertFalse(r.ok)
        assertTrue(r.text.contains("past the end"))
    }

    @Test fun protectedFileStaysRefusedForPagedReads() {
        val r = readResult(ListFs(mapOf(".env" to "SECRET=1")), BoltAction.ToolAction("read", ".env", offset = 2))
        assertFalse(r.ok)
        assertTrue(r.text.contains("protected file"))
    }

    @Test fun singleHugeFolderStaysFlatButMarkedPartial() {
        val files = buildMap { repeat(400) { put("docs/D%03d.md".format(it), "x") } }
        val text = listTool(ListFs(files), ".")
        assertTrue(text.contains("PARTIAL"))
        assertTrue(text.contains("…and 100 more file(s) in this same folder"))
        assertTrue(text.contains("docs/D000.md"))
        assertFalse(text.contains("docs/D399.md"))
    }
}
