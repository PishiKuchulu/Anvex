package com.anvex.core.android.runtime.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** One-off diagnostic (informational): can JS in the :node process LISTEN on loopback? */
@RunWith(AndroidJUnit4::class)
class NodeServerProbeTest {

    @Test(timeout = 60_000)
    fun probeJsListen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "node-server-probe").apply { deleteRecursively(); mkdirs() }
        val script = File(root, "probe.js")
        script.writeText(
            """
            const net = require('net');
            const s = net.createServer(() => {});
            s.on('error', (e) => console.log('SRV-ERROR ' + e.code + ' ' + e.message));
            try {
              s.listen(0, '127.0.0.1', () => console.log('SRV-PORT ' + s.address().port));
            } catch (e) { console.log('SRV-THROW ' + e.message); }
            setTimeout(() => {}, 300);
            """.trimIndent(),
        )
        val runtime = NodeMobileRuntime(context, root)
        val result = runtime.runCommand("node", listOf("probe.js"), timeoutMs = 20_000) { }
        android.util.Log.i("SRV_PROBE", "lines=" + result.lines.map { it.text } + " exit=" + result.exitCode)
        Unit
    }
}
