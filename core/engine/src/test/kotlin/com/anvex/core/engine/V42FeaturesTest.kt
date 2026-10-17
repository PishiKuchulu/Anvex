package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V42FeaturesTest {
    @Test fun `zip request is detected as export in Persian and English`() {
        assertTrue(UserIntentClassifier.requestsExport("سورس این اپ رو زیپ کن و فایلشو بده"))
        assertTrue(UserIntentClassifier.requestsExport("Create the app source and give it to me as a zip"))
        assertTrue(UserIntentClassifier.requestsExport("یه pdf از این گزارش بساز"))
    }

    @Test fun `informational question about zip is not an export`() {
        assertFalse(UserIntentClassifier.requestsExport("چطور یه فایل zip بسازم؟"))
        assertFalse(UserIntentClassifier.requestsExport("How do I make a zip?"))
        assertFalse(UserIntentClassifier.requestsExport("سلام"))
    }

    @Test fun `assistant prompt teaches artifact delivery memory and tools`() {
        val p = SystemPromptFactory.buildAssistantPrompt(
            SystemPromptFactory.AssistantPromptOptions(
                exportRequested = true,
                memoryEnabled = true,
                memoryBlock = "- (abc123) [preference] Prefers Persian",
                customInstructions = "Be concise",
                webTools = true,
            )
        )
        assertTrue(p.contains("type=\"artifact\""))
        assertTrue(p.contains("EXPORT CONTRACT"))
        assertTrue(p.contains("<user_memory>") && p.contains("Prefers Persian"))
        assertTrue(p.contains("<boltMemory"))
        assertTrue(p.contains("Be concise"))
        assertTrue(p.contains("type=\"fetch\"") && p.contains("type=\"search\""))
    }

    @Test fun `prompt without memory or web tools omits those sections`() {
        val p = SystemPromptFactory.buildAssistantPrompt(SystemPromptFactory.AssistantPromptOptions())
        assertFalse(p.contains("<memory_instructions>"))
        assertFalse(p.contains("type=\"fetch\""))
        assertTrue(p.contains("type=\"read\""))
    }

    @Test fun `parser turns read fetch and search tags into ToolAction`() {
        val actions = ArrayList<BoltAction>()
        val cb = object : ParserCallbacks {
            override fun onActionClose(data: ActionCallbackData) { actions += data.action }
        }
        val parser = BoltStreamParser(cb)
        val text = """<boltArtifact id="r" title="Research">
<boltAction type="read" path="src/App.kt"></boltAction>
<boltAction type="fetch" url="https://example.com/a"></boltAction>
<boltAction type="search" query="kotlin flow"></boltAction>
</boltArtifact>"""
        parser.parse("m1", text)
        parser.flush("m1")
        val tools = actions.filterIsInstance<BoltAction.ToolAction>()
        assertEquals(listOf("read", "fetch", "search"), tools.map { it.name })
        assertEquals(listOf("src/App.kt", "https://example.com/a", "kotlin flow"), tools.map { it.argument })
    }

    @Test fun `tool actions are classified safe`() {
        val a = ActionSafetyPolicy.assess(BoltAction.ToolAction("fetch", "https://example.com"))
        assertEquals(ActionSafetyPolicy.Risk.SAFE, a.risk)
    }

    @Test fun `source package named build is kept by artifact hygiene`() {
        assertTrue(ArtifactHygienePolicy.include("core/android/src/main/kotlin/com/x/build/ToolchainManager.kt"))
        assertFalse(ArtifactHygienePolicy.include("app/build/outputs/apk/debug/app.apk"))
    }
}

class SecretScannerTest {
    private fun text(path: String, body: String) = ArtifactHygienePolicy.redactSecrets(path, body.toByteArray())

    @Test fun `variable references are not secrets`() {
        val gradle = """
            val ksPass = project.findProperty("BOLT_KEYSTORE_PASSWORD") as String?
            storePassword = ksPass
            keyPassword = ksPass
        """.trimIndent()
        assertTrue(ArtifactHygienePolicy.findSecrets("app/build.gradle.kts", gradle).isEmpty())
    }

    @Test fun `quoted literal is redacted with file line and key but never the value`() {
        val src = "android {\n  keyPassword = \"hunter2-Real-Value\"\n}\n"
        val r = text("app/build.gradle.kts", src)
        assertEquals(1, r.findings.size)
        assertEquals(2, r.findings[0].line)
        assertTrue(r.findings[0].key.contains("keyPassword"))
        val out = String(r.bytes)
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("REDACTED"))
    }

    @Test fun `placeholders and docs are left alone`() {
        assertTrue(ArtifactHygienePolicy.findSecrets("README.md", "api_key = \"YOUR_API_KEY_HERE\"").isEmpty())
        assertTrue(ArtifactHygienePolicy.findSecrets("a.kt", "val password = \"\${System.getenv(\"X\")}\"").isEmpty())
        assertTrue(ArtifactHygienePolicy.findSecrets("a.kt", "password = \"........\"").isEmpty())
    }

    @Test fun `bare values are caught only in config files`() {
        assertEquals(1, ArtifactHygienePolicy.findSecrets("app/local.properties", "store.password=Zk93mQvPx81LaTe").size)
        assertTrue(ArtifactHygienePolicy.findSecrets("notes.md", "password=Zk93mQvPx81LaTe").isEmpty())
    }
}
