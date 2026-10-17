package com.anvex.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewHarnessSecurityTest {
    @Test fun entryPathIsHtmlScriptSafe() {
        val html = PreviewHarness.generate(
            manifestJson = "{}",
            entryPath = "x</script><script>window.pwned=1</script>",
            cssLinks = emptyList(),
            importMapJson = "{}",
        )
        assertTrue(html.contains("\\u003C/script\\u003E"))
        assertFalse(html.contains("window.__boltEntry = \"x</script>"))
    }
}
