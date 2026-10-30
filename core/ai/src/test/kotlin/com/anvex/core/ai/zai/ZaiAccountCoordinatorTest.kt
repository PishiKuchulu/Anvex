package com.anvex.core.ai.zai

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.4: Z.ai account login — mocked-transport tests for the ZCode polling
 * flow (zai.txt Phase 6). No real credentials are used anywhere; responses
 * mirror the 9Router-verified wire format.
 */
class ZaiAccountCoordinatorTest {

    /** Scriptable fake transport; records calls, serves queued responses. */
    private open class FakeTransport : ZaiAccountCoordinator.HttpTransport {
        val postCalls = mutableListOf<Triple<String, Map<String, String>, String?>>()
        val getCalls = mutableListOf<Pair<String, Map<String, String>>>()
        val queue = ArrayDeque<Pair<Int, String>>() // (httpCode, body); 200 default

        override suspend fun post(url: String, headers: Map<String, String>, jsonBody: String?): String {
            postCalls.add(Triple(url, headers, jsonBody))
            val next = queue.removeFirstOrNull() ?: (200 to "{}")
            if (next.first != 200) throw ZaiAccountCoordinator.TransportError(next.first, "HTTP ${next.first}: ${next.second}")
            return next.second
        }

        override suspend fun get(url: String, headers: Map<String, String>): String {
            getCalls.add(url to headers)
            val next = queue.removeFirstOrNull() ?: (200 to "{}")
            if (next.first != 200) throw ZaiAccountCoordinator.TransportError(next.first, "HTTP ${next.first}: ${next.second}")
            return next.second
        }
    }

    private fun ok(body: String) = 200 to body

    // Wire fixtures (9Router-verified shapes, fake values only)
    private val initBody = """
        {"code":0,"msg":"ok","data":{
          "authorize_url":"https://zcode.z.ai/auth/cli?flow_id=fl_test_9981&provider=zai",
          "flow_id":"fl_test_9981","poll_interval_sec":1,"expires_at":${System.currentTimeMillis() + 60_000}
        }}
    """.trimIndent()
    private val pendingBody = """{"code":0,"msg":"ok","data":{"status":"pending"}}"""
    private val readyBody = """
        {"code":0,"msg":"ok","data":{
          "status":"ready","token":"zcode.jwt.value",
          "user":{"name":"Test User","email":"test@z.ai"},
          "zai":{"access_token":"zai_fake_oauth_token"}
        }}
    """.trimIndent()
    private val businessLoginBody = """{"code":0,"msg":"ok","data":{"access_token":"fake_business_jwt"}}"""
    private val customerInfoBody = """
        {"code":0,"msg":"ok","data":{"organizations":[
          {"organizationId":"org-7","organizationName":"默认机构","projects":[
            {"projectId":"prj-1","projectName":"默认项目","projectType":"1"},
            {"projectId":"prj-9","projectName":"team stuff","projectType":"2"}
          ]},
          {"organizationId":"org-8","organizationName":"other","projects":[{"projectId":"prj-2","projectName":"side","projectType":"1"}]}
        ]}}
    """.trimIndent()
    private val apiKeyListBody = """
        [{"name":"zcode-api-key","apiKey":"ak_test_123"},{"name":"other","apiKey":"ak_other"}]
    """.trimIndent()
    private val apiKeyCopyBody = """{"code":0,"msg":"ok","data":{"secretKey":"sk_test_456"}}"""

    private fun fullHappyPath(): FakeTransport = FakeTransport().apply {
        queue.addAll(
            listOf(
                ok(initBody),                    // init
                ok(pendingBody), ok(pendingBody), // two pending polls
                ok(readyBody),                   // ready (with zai.access_token)
                ok(businessLoginBody),            // business login
                ok(customerInfoBody),             // customer info
                ok(apiKeyListBody),              // api keys list (has zcode-api-key)
                ok(apiKeyCopyBody),              // copy -> secretKey
            )
        )
    }

    // ---- 1/2: init returns a valid authorize URL + flow id; browser uses it ----

    @Test
    fun `login initiation returns valid authorization url and flow id`() = runTest {
        val t = FakeTransport().apply {
            queue.add(ok(initBody))
            queue.add(ok(pendingBody))
            queue.add(500 to "{}")
        }
        val seen = mutableListOf<ZaiAccountCoordinator.Progress>()
        val outcome = ZaiAccountCoordinator(t) { }.login { seen.add(it) }
        assertTrue(seen.any { it.stage == ZaiAccountCoordinator.Stage.INITIATING })
        // The authorize URL surfaced to the UI is exactly the upstream-generated one.
        val waitProgress = seen.first { it.stage == ZaiAccountCoordinator.Stage.WAITING_FOR_BROWSER }
        assertEquals("https://zcode.z.ai/auth/cli?flow_id=fl_test_9981&provider=zai", waitProgress.authorizeUrl)
        assertTrue(outcome is ZaiAccountCoordinator.Outcome.Failed)
    }

    // ---- 3: pending polling continues --------------------------------------

    @Test
    fun `pending polling responses continue without premature termination`() = runTest {
        val t = FakeTransport().apply {
            queue.addAll(listOf(ok(initBody), ok(pendingBody), ok(pendingBody), ok(readyBody), ok(businessLoginBody), ok(customerInfoBody), ok(apiKeyListBody), ok(apiKeyCopyBody)))
        }
        val outcome = ZaiAccountCoordinator(t, sleeper = { delay(it) }).login { }
        assertTrue("expected Connected, got $outcome", outcome is ZaiAccountCoordinator.Outcome.Connected)
        // 3 polls + customerInfo + api_keys list + copy = 6 GETs (init is a POST).
        assertEquals(6, t.getCalls.size)
    }

    // ---- 4: successful polling parses the documented structure --------------

    @Test
    fun `successful polling parses documented response structure`() = runTest {
        val t = fullHappyPath()
        val outcome = ZaiAccountCoordinator(t) { }.login { }
        val connected = outcome as? ZaiAccountCoordinator.Outcome.Connected
            ?: error("expected Connected, got $outcome")
        assertEquals("ak_test_123.sk_test_456", connected.planApiKey)
        assertEquals("Test User", connected.accountName)
        assertEquals("test@z.ai", connected.accountEmail)
    }

    // ---- 5: malformed ready payload fails safely ------------------------------

    @Test
    fun `missing or malformed credentials fail safely`() = runTest {
        val malformed = """{"code":0,"msg":"ok","data":{"status":"ready","garbage":true}}"""
        val t = FakeTransport().apply {
            queue.addAll(listOf(ok(initBody), ok(pendingBody), ok(malformed)))
        }
        val outcome = ZaiAccountCoordinator(t) { }.login { }
        assertTrue(outcome is ZaiAccountCoordinator.Outcome.Failed)
        assertTrue((outcome as ZaiAccountCoordinator.Outcome.Failed).reason.contains("missing the Z.ai access token"))
    }

    // ---- 6: user denial is handled ------------------------------------------

    @Test
    fun `user denial returns actionable error`() = runTest {
        val denied = """{"code":0,"msg":"ok","data":{"status":"failed"}}"""
        val t = FakeTransport().apply {
            queue.addAll(listOf(ok(initBody), ok(denied)))
        }
        val outcome = ZaiAccountCoordinator(t) { }.login { }
        val failed = outcome as? ZaiAccountCoordinator.Outcome.Failed
            ?: error("expected Failed")
        assertEquals("poll", failed.stage)
        assertTrue(failed.reason.contains("denied"))
    }

    // ---- 7: init failure terminates cleanly ---------------------------------

    @Test
    fun `flow initialization failure terminates cleanly`() = runTest {
        val t = FakeTransport().apply { queue.add(404 to """{"error":{"message":"ZCode CLI route deprecated"}}""") }
        val outcome = ZaiAccountCoordinator(t) { }.login { }
        val failed = outcome as? ZaiAccountCoordinator.Outcome.Failed ?: error("expected Failed")
        assertEquals("init", failed.stage)
        assertTrue(failed.reason.contains("HTTP 404"))
    }

    // ---- 8: duplicate login attempts do not race -----------------------------

    /** Transport whose FIRST poll GET suspends until the test releases the gate. */
    private class GatedTransport(
        private val gate: kotlinx.coroutines.CompletableDeferred<Unit>,
        val reachedGate: kotlinx.coroutines.CompletableDeferred<Unit> = kotlinx.coroutines.CompletableDeferred(),
    ) : FakeTransport() {
        private var pollCount = 0
        override suspend fun get(url: String, headers: Map<String, String>): String {
            if (url.contains("/poll/") && pollCount++ == 0) {
                reachedGate.complete(Unit)
                gate.await() // hold the first poll in-flight until released
            }
            return super.get(url, headers)
        }
    }

    @Test
    fun `duplicate login attempts do not race`() {
        // Real threads (runBlocking): the single-flight lock must reject a second
        // start WHILE the first flow is provably in-flight (held at the gate).
        kotlinx.coroutines.runBlocking {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val reachedGate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val t = GatedTransport(gate, reachedGate).apply {
                queue.addAll(listOf(ok(initBody), ok(pendingBody), ok(readyBody), ok(businessLoginBody), ok(customerInfoBody), ok(apiKeyListBody), ok(apiKeyCopyBody)))
            }
            val first = ZaiAccountCoordinator(t, sleeper = { delay(it) })
            val a = async(kotlinx.coroutines.Dispatchers.Default) { first.login { } }
            reachedGate.await() // first flow is now suspended in its first poll
            // Second login on the SAME coordinator (double-tap) must be rejected.
            val b = first.login { }
            assertTrue("second concurrent start must be rejected, got $b", b is ZaiAccountCoordinator.Outcome.Failed && b.stage == "busy")
            gate.complete(Unit)
            val aResult = a.await()
            assertTrue("first flow must still finish cleanly, got $aResult", aResult is ZaiAccountCoordinator.Outcome.Connected)
        }
    }

    // ---- 9: progress payload never leaks secrets -----------------------------

    @Test
    fun `no secret appears in progress or failure messages`() = runTest {
        val t = fullHappyPath()
        val seen = mutableListOf<String>()
        ZaiAccountCoordinator(t) { }.login { p ->
            seen.add(p.authorizeUrl ?: "")
            seen.add(p.stage.name)
        }
        val all = seen.joinToString(" ")
        assertFalse(all.contains("zai_fake_oauth_token"))
        assertFalse(all.contains("fake_business_jwt"))
        assertFalse(all.contains("sk_test_456"))
        assertFalse(all.contains("ak_test_123"))
    }

    // ---- 10: cancellation ----------------------------------------------------

    @Test
    fun `cancellation is honored during polling`() = runTest {
        val t = FakeTransport().apply {
            queue.add(ok(initBody))
            repeat(100) { queue.add(ok(pendingBody)) }
        }
        val coordinator = ZaiAccountCoordinator(t, sleeper = { delay(it) })
        val outcome = async { coordinator.login { } }
        delay(150) // login is inside its first poll-interval sleep; cancel now
        coordinator.cancel()
        val result = outcome.await()
        assertTrue("expected Cancelled, got $result", result is ZaiAccountCoordinator.Outcome.Cancelled)
    }

    // ---- 11: protocol parser unit checks -------------------------------------

    @Test
    fun `parsePoll handles pending denied and ready`() {
        assertEquals(ZaiProtocol.PollState.Pending, ZaiProtocol.parsePoll(pendingBody))
        val denied = ZaiProtocol.parsePoll("""{"code":0,"data":{"status":"failed"}}""")
        assertEquals(ZaiProtocol.PollState.Denied, denied)
        val ready = ZaiProtocol.parsePoll(readyBody) as ZaiProtocol.PollState.Ready
        assertEquals("zai_fake_oauth_token", ready.zaiAccessToken)
    }

    @Test
    fun `parseInit rejects non-https authorize urls`() {
        val bad = initBody.replace("https://zcode.z.ai", "http://zcode.z.ai")
        assertNull(ZaiProtocol.parseInit(bad))
        assertNotNull(ZaiProtocol.parseInit(initBody))
        // L-14: a look-alike host must be rejected even over https.
        val foreign = initBody.replace("https://zcode.z.ai", "https://zcode.z.ai.evil.example")
        assertNull(ZaiProtocol.parseInit(foreign))
    }

    @Test
    fun `parseCustomerInfo prefers default org and non-team projects`() {
        val info = ZaiProtocol.parseCustomerInfo(customerInfoBody)
        assertNotNull(info)
        assertEquals("org-7", info!!.organizationId)
        assertEquals("prj-1", info.projectId)
    }

    @Test
    fun `apiKeyList parses the list form directly`() {
        val list = ZaiProtocol.parseApiKeyList(apiKeyListBody)
        assertEquals(2, list.size)
        assertEquals("zcode-api-key", list[0].name)
        assertEquals("ak_test_123", list[0].apiKey)
    }
}
