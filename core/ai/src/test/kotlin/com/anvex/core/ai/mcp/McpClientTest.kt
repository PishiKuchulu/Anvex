package com.anvex.core.ai.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.0 (GOAL 2): MCP streamable-HTTP client protocol tests against a MockWebServer,
 * covering plain-JSON and NDJSON framing plus error propagation.
 * Loopback HTTP is used here purely as a test transport (permitted by McpServerConfig).
 */
class McpClientTest {

    @Test
    fun `initialize parses server info`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"echo","version":"1.2"}}}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        server.start()
        try {
            val url = server.url("/mcp").toString()
            val client = McpClient(url, client = McpClient.defaultClient())
            val session = runBlocking { client.initialize() }.getOrThrow()
            assertEquals("echo", session.serverInfo.name)
            assertEquals("1.2", session.serverInfo.version)
            assertEquals("2025-03-26", session.protocolVersion)

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("initialize"))
            assertTrue(body.contains("Anvex-Harness"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `list tools parses ndjson stream`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setBody(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n" +
                        // M-12: the response id must match the request id (1) —
                        // the old test used id 2 and relied on the removed
                        // wrong-id fallback.
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"Echoes text\",\"inputSchema\":{\"type\":\"object\"}}]}}",
                )
                .setHeader("Content-Type", "application/json"),
        )
        server.start()
        try {
            val client = McpClient(server.url("/mcp").toString(), client = McpClient.defaultClient())
            val tools = runBlocking { client.listTools() }.getOrThrow()
            assertEquals(1, tools.size)
            assertEquals("echo", tools.first().name)
            assertEquals("Echoes text", tools.first().description)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `tools call returns text content`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                // M-12: id must match the request (first rpc id is 1).
                """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"hello world"}],"isError":false}}""",
            ),
        )
        server.start()
        try {
            val client = McpClient(server.url("/mcp").toString(), client = McpClient.defaultClient())
            val call = runBlocking {
                client.callTool(
                    "echo",
                    mapOf("text" to JsonPrimitive("hello world")),
                )
            }.getOrThrow()
            assertTrue(call.success)
            assertEquals("hello world", call.text)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `json rpc error propagates as failure`() {
        val server = MockWebServer()
        server.enqueue(
            // M-12: id must match the request (first rpc id is 1).
            MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"method not found"}}"""),
        )
        server.start()
        try {
            val client = McpClient(server.url("/mcp").toString(), client = McpClient.defaultClient())
            val result = runBlocking { client.callTool("missing", emptyMap()) }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("method not found") == true)
        } finally {
            server.shutdown()
        }
    }

    // M-12: a response for another request's id must be an error, never
    // that request's result.
    @Test
    fun `mismatched response id is an error not a result`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":99,"result":{"content":[{"type":"text","text":"WRONG CALL"}],"isError":false}}""",
            ),
        )
        server.start()
        try {
            val client = McpClient(server.url("/mcp").toString(), client = McpClient.defaultClient())
            val result = runBlocking { client.callTool("echo", emptyMap()) }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("No JSON-RPC response") == true)
        } finally {
            server.shutdown()
        }
    }

    // M-12: the session id from initialize must be echoed on later calls.
    @Test
    fun `session id header is echoed on later requests`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"echo","version":"1.0"}}}""")
                .setHeader("Mcp-Session-Id", "sess-42"),
        )
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        server.enqueue(
            MockResponse().setBody("""{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}"""),
        )
        server.start()
        try {
            val client = McpClient(server.url("/mcp").toString(), client = McpClient.defaultClient())
            runBlocking { client.ensureInitialized() }.getOrThrow()
            runBlocking { client.listTools() }.getOrThrow()
            server.takeRequest() // initialize
            server.takeRequest() // notifications/initialized
            val toolsRequest = server.takeRequest()
            assertEquals("sess-42", toolsRequest.getHeader("Mcp-Session-Id"))
            assertEquals("2025-03-26", toolsRequest.getHeader("MCP-Protocol-Version"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `config rejects remote http urls`() {
        val rejected = runCatching { McpServerConfig("id", "name", "http://example.com/mcp") }
        assertTrue(rejected.isFailure)
        val accepted = McpServerConfig("id", "name", "https://example.com/mcp")
        assertEquals("https://example.com/mcp", accepted.url)
    }
}
