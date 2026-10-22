package com.anvex.core.android.runtime.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic bisect (temporary): loads each bundled library individually,
 * in dependency order, and reports the exact per-library outcome so a
 * single CI run shows where the chain breaks.
 */
@RunWith(AndroidJUnit4::class)
class NodeRuntimeBisectTest {

    @Test
    fun bisectLoadChain() {
        val order = listOf(
            "z", "cares", "sqlite3", "crypto", "ssl",
            "icudata", "icuuc", "icui18n", "node", "anvex-node",
        )
        val report = StringBuilder()
        for (name in order) {
            try {
                System.loadLibrary(name)
                report.append(name).append("=OK; ")
            } catch (t: Throwable) {
                report.append(name).append("=FAIL(").append(t.message?.take(220)).append("); ")
            }
        }
        fail("BISECT: $report")
    }
}
