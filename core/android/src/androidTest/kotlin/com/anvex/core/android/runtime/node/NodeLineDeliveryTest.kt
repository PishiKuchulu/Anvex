package com.anvex.core.android.runtime.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic contract for the Node line channel: a script's stdout must
 * reach BOTH the runCommand result snapshot and the live onLine stream.
 * (PiEngine's control-port handshake depends on the live stream.)
 */
@RunWith(AndroidJUnit4::class)
class NodeLineDeliveryTest {

    @Test(timeout = 120_000)
    fun scriptStdoutReachesResultAndLiveStream() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "node-line-test").apply { deleteRecursively(); mkdirs() }
        val marker = "line-delivery-probe-12345"
        val script = File(root, "probe.js")
        script.writeText(
            """
            require('fs').writeFileSync('probe-ran.txt', 'ran');
            console.log('$marker');
            setTimeout(() => console.log('$marker-done'), 50);
            """.trimIndent(),
        )
        val live = Collections.synchronizedList(mutableListOf<String>())
        val runtime = NodeMobileRuntime(context, root)
        val result = runtime.runCommand("node", listOf("probe.js"), timeoutMs = 30_000) { line ->
            live += line.text
        }
        assertTrue("script did not run: ${result.error}", File(root, "probe-ran.txt").isFile)
        val snapshotTexts = result.lines.map { it.text }
        android.util.Log.i("LINE_PROBE", "snapshot=$snapshotTexts live=$live exit=${result.exitCode}")
        assertTrue("stdout missing from result snapshot: $snapshotTexts", snapshotTexts.any { it.contains(marker) })
        assertTrue("stdout missing from live onLine stream: $live", live.any { it.contains(marker) })
        Unit
    }
}
