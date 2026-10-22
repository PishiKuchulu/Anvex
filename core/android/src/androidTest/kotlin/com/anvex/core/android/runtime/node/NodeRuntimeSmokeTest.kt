package com.anvex.core.android.runtime.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke for the bundled Node.js runtime package (v4.8.0+).
 *
 * Loading `anvex-node` forces the Android linker to resolve libnode.so and
 * its whole DT_NEEDED closure (libz.so.1, libcares.so, libsqlite3.so,
 * libcrypto.so.3, libssl.so.3, libicui18n.so.78, libicuuc.so.78,
 * libicudata.so.78) from the app's native lib directory. Any missing or
 * misnamed library — the failure mode of the first libnode-v24.18.0
 * package — flips [NodeBridge.loaded] to false and fails this test.
 */
@RunWith(AndroidJUnit4::class)
class NodeRuntimeSmokeTest {

    @Test
    fun bundledLibnodeLoadsWithFullClosure() {
        assertTrue(
            "libnode.so or one of its DT_NEEDED dependencies failed to load: ${NodeBridge.loadError}",
            NodeBridge.loaded,
        )
        assertTrue(
            "bundled runtime must report available on supported ABIs",
            NodeMobileRuntime.isBundledAvailable(),
        )
    }
}
