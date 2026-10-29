package com.anvex.core.engine

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
}
