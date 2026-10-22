package com.anvex.core.android.runtime.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-2 POC gate: the Pi coding-agent engine (v1.4.0) running INSIDE
 * Anvex's bundled Node 24 process, driven over its JSONL RPC protocol
 * through an in-process stream loopback (Android cannot exec a node
 * binary, so the driver swaps process.stdin/stdout before importing
 * Pi's CLI bundle).
 *
 * The POC extension registers a scripted fake provider (no network, no
 * API key): turn 1 writes a file, turn 2 attempts a bash call that the
 * POC approval gateway must BLOCK, turn 3 reports. Success requires the
 * file written with exact content, the shell side-effect file absent,
 * and both gateway decisions logged — the same shape Phase 3 wires to
 * CommandApprovalService.
 *
 * Assets: pi-poc.zip (Pi dist/bundle + jiti + vendored event-stream +
 * driver + extension), built from the hash-verified npm tarball
 * (pi-coding-agent 1.4.0, sha256 d155c16e…c7428).
 */
@RunWith(AndroidJUnit4::class)
class PiPocTest {

    @Test(timeout = 420_000)
    fun piEngineRunsFileTaskAndGatewayBlocksShell() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "pi-poc-test")
        root.deleteRecursively()
        root.mkdirs()
        unzipAsset(context, "pi-poc.zip", root)

        val work = File(root, "work")
        val cli = File(root, "pi/dist/bundle/cli.js")
        val ext = File(root, "ext/fake-provider.ts")
        assertTrue("pi bundle cli missing after unzip", cli.isFile)
        assertTrue("poc extension missing after unzip", ext.isFile)

        val runtime = NodeMobileRuntime(context, root)
        val lines = mutableListOf<String>()
        val result = runtime.runCommand(
            tool = "node",
            args = listOf(
                "driver/run-inproc.mjs",
                cli.absolutePath,
                work.absolutePath,
                ext.absolutePath,
            ),
            timeoutMs = 300_000,
        ) { line -> lines += line.text }

        val resultsFile = File(work, "results-inproc.json")
        assertTrue(
            "POC driver produced no results file; node exit=${result.exitCode} " +
                "error=${result.error}; output tail: ${lines.takeLast(15)}",
            resultsFile.isFile,
        )
        val r = JSONObject(resultsFile.readText())
        println("PI-POC-RESULTS: $r")

        assertTrue("pi RPC get_state failed: $r", r.getBoolean("stateOk"))
        assertEquals("started", r.getString("promptDisposition"))
        assertTrue("scripted write tool call did not produce the file: $r", r.getBoolean("helloWritten"))
        assertEquals("hello from pi poc\n", r.getString("helloContent"))
        assertFalse("GATEWAY FAILURE: blocked bash call executed: $r", r.getBoolean("pwnedExists"))

        val gateway = r.getJSONArray("gatewayLog")
        assertEquals("gateway must see exactly 2 tool calls: $r", 2, gateway.length())
        assertEquals("allow", gateway.getJSONObject(0).getString("decision"))
        assertEquals("write", gateway.getJSONObject(0).getString("tool"))
        assertEquals("block", gateway.getJSONObject(1).getString("decision"))
        assertEquals("bash", gateway.getJSONObject(1).getString("tool"))

        assertTrue("node run failed: exit=${result.exitCode} error=${result.error}", result.success)
        Unit
    }

    private fun unzipAsset(context: android.content.Context, name: String, destDir: File) {
        context.assets.open(name).use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val out = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    entry = zip.nextEntry
                }
            }
        }
    }
}
