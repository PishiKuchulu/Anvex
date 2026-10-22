package com.anvex.core.android.runtime.pi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic gate for the Pi runtime asset extraction. */
@RunWith(AndroidJUnit4::class)
class PiRuntimeTest {

    @Test(timeout = 60_000)
    fun runtimeExtractsDriverAndBundle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = PiRuntime.ensureInstalled(context)
        assertNotNull("ensureInstalled returned null", dir)
        val tree = dir!!.walkTopDown().take(30).joinToString("\n") { it.path }
        android.util.Log.i("PI_RUNTIME", "tree:\n$tree")
        assertTrue("driver missing; tree:\n$tree", PiRuntime.driverFile(context).isFile)
        assertTrue("gateway missing", PiRuntime.gatewayExtension(context).isFile)
        assertTrue(
            "pi cli bundle missing",
            File(dir, "pi/dist/bundle/cli.js").isFile,
        )
    }
}
