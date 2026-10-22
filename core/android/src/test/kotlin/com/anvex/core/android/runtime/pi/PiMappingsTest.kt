package com.anvex.core.android.runtime.pi

import com.anvex.core.ai.ProviderRuntimeConfig
import com.anvex.core.engine.ActionSafetyPolicy
import com.anvex.core.engine.BoltAction
import com.anvex.core.model.ProviderConfig
import com.anvex.core.model.ProviderKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-3 wiring contracts: Pi tool calls must land on the same
 * BoltAction safety semantics as the native harness, and provider
 * mapping must never embed a plaintext key in models.json.
 */
class PiMappingsTest {

    private fun input(json: String) = JSONObject(json)

    @Test
    fun `write maps to a safe file action`() {
        val action = PiToolMapping.toBoltAction("write", input("""{"path":"a.txt","content":"hi"}"""))
        assertTrue(action is BoltAction.FileAction)
        assertEquals("a.txt", (action as BoltAction.FileAction).filePath)
        assertFalse(ActionSafetyPolicy.assess(action).requiresApproval)
    }

    @Test
    fun `plain echo redirect stays safe like the native engine`() {
        val action = PiToolMapping.toBoltAction("bash", input("""{"command":"echo ok > out.txt"}"""))
        assertTrue(action is BoltAction.ShellAction)
        assertFalse(ActionSafetyPolicy.assess(action).requiresApproval)
    }

    @Test
    fun `compound shell requires approval`() {
        val action = PiToolMapping.toBoltAction("bash", input("""{"command":"echo x > a.txt && echo y > b.txt"}"""))
        assertTrue(ActionSafetyPolicy.assess(action).requiresApproval)
    }

    @Test
    fun `read only tools are safe tool actions`() {
        for (tool in listOf("read", "grep", "find", "ls")) {
            val action = PiToolMapping.toBoltAction(tool, input("""{"path":"src","pattern":"foo"}"""))
            assertTrue("$tool must be a ToolAction", action is BoltAction.ToolAction)
            assertFalse("$tool must not require approval", ActionSafetyPolicy.assess(action).requiresApproval)
        }
    }

    @Test
    fun `mcp and unknown tools fail closed`() {
        val mcp = PiToolMapping.toBoltAction("mcp__server__call", input("""{}"""))
        assertTrue(ActionSafetyPolicy.assess(mcp).requiresApproval)
        val unknown = PiToolMapping.toBoltAction("teleport", input("""{"to":"mars"}"""))
        assertTrue(unknown is BoltAction.UnknownAction)
        assertTrue(ActionSafetyPolicy.assess(unknown).requiresApproval)
    }

    @Test
    fun `gateway confirm payload round trips`() {
        val message = "tool=bash\ninput={\"command\":\"ls\"}\n\nTool: bash\n{\"command\":\"ls\"}"
        val parsed = PiToolMapping.parseGatewayConfirm(message)
        assertEquals("bash", parsed?.first)
        assertEquals("ls", parsed?.second?.optString("command"))
        assertNull(PiToolMapping.parseGatewayConfirm("some other extension's prose"))
        assertNull(PiToolMapping.parseGatewayConfirm("tool=bash\nnot-input"))
    }

    @Test
    fun `provider families map to pi apis`() {
        assertEquals("openai-completions", PiProviderMapping.apiFor(ProviderKind.DEEPSEEK))
        assertEquals("openai-completions", PiProviderMapping.apiFor(ProviderKind.CUSTOM_OPENAI))
        assertEquals("anthropic-messages", PiProviderMapping.apiFor(ProviderKind.ANTHROPIC))
        assertEquals("google-generative-ai", PiProviderMapping.apiFor(ProviderKind.GEMINI))
        assertNull(PiProviderMapping.apiFor(ProviderKind.ZAI_ACCOUNT))
    }

    @Test
    fun `models json references the key via env only`() {
        val runtime = ProviderRuntimeConfig(
            config = ProviderConfig(
                id = "p1", name = "DeepSeek", kind = ProviderKind.DEEPSEEK,
                baseUrl = "https://api.deepseek.com", model = "deepseek-chat",
            ),
            apiKey = "sk-must-never-appear",
        )
        val mapped = PiProviderMapping.forRuntime(runtime)
        assertEquals("anvex-p1", mapped?.providerName)
        assertEquals("deepseek-chat", mapped?.modelId)
        val json = mapped!!.modelsJson.toString()
        assertFalse("plaintext key leaked into models.json", json.contains("sk-must-never-appear"))
        assertTrue(json.contains("$" + "ANVEX_PI_API_KEY"))
        assertTrue(json.contains("openai-completions"))
    }

    @Test
    fun `unsupported provider yields null mapping`() {
        val runtime = ProviderRuntimeConfig(
            config = ProviderConfig(id = "z", name = "Z", kind = ProviderKind.ZAI_ACCOUNT, model = "glm-4.6"),
            apiKey = "",
        )
        assertNull(PiProviderMapping.forRuntime(runtime))
    }
}
