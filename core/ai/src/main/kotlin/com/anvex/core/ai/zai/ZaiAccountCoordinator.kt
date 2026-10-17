package com.anvex.core.ai.zai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v4.4: authentication coordinator for the Z.ai GLM Coding Plan.
 *
 * Implements the ZCode-CLI-compatible polling flow (verified against 9Router
 * ce4460e — see [ZaiProtocol] for the source chain):
 *
 *   1. POST zcode.z.ai/api/v1/oauth/cli/init   (Authorization: Bearer <one-time pollToken>, {"provider":"zai"})
 *      -> { code:0, data: { authorize_url, flow_id, poll_interval_sec, expires_at } }
 *   2. The app opens authorize_url in the system browser; the user signs in
 *      with their own Z.ai account and approves.
 *   3. GET zcode.z.ai/api/v1/oauth/cli/poll/{flow_id} (same pollToken) until
 *      data.status becomes "ready" (nested data.zai.access_token) / "failed".
 *   4. POST api.z.ai/api/auth/z/login {"token": zaiAccessToken} -> business JWT.
 *   5. getCustomerInfo -> default organization/project -> api_keys
 *      list/create("zcode-api-key") -> copy -> "apiKey.secretKey" coding-plan key.
 *
 * Security contract (zai.txt):
 *  - The one-time poll token lives only in memory for the duration of a flow
 *    and is NEVER logged, returned, or persisted.
 *  - No token of any kind appears in progress updates, failures, or toString.
 *  - Polling is bounded by the upstream expiry (default 300s) and interval;
 *    it never runs indefinitely.
 *  - A single-flight lock prevents concurrent flows racing each other.
 */
class ZaiAccountCoordinator(
    private val transport: HttpTransport = OkHttpTransport(),
    private val now: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {

    /** Minimal blocking HTTP facade — injectable for tests. */
    interface HttpTransport {
        /** @throws TransportError on non-2xx or IO failure (body included when available). */
        suspend fun post(url: String, headers: Map<String, String>, jsonBody: String?): String

        /** @throws TransportError on non-2xx or IO failure. */
        suspend fun get(url: String, headers: Map<String, String>): String
    }

    class TransportError(val httpCode: Int?, message: String) : Exception(message)

    /** Default transport: OkHttp, HTTPS-only, 20s call timeout. */
    class OkHttpTransport(
        private val client: OkHttpClient = OkHttpClient.Builder()
            .callTimeout(java.time.Duration.ofSeconds(20))
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build(),
    ) : HttpTransport {
        override suspend fun post(url: String, headers: Map<String, String>, jsonBody: String?): String =
            call(url, headers) { it.apply { post((jsonBody ?: "").toRequestBody("application/json".toMediaType())) } }

        override suspend fun get(url: String, headers: Map<String, String>): String =
            call(url, headers) { it.get() }

        private fun call(url: String, headers: Map<String, String>, method: (Request.Builder) -> Request.Builder): String {
            val u = java.net.URI(url)
            require(u.scheme == "https") { "Only HTTPS endpoints are allowed: $url" }
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            val request = method(builder).build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw TransportError(resp.code, "HTTP ${resp.code}: ${text.take(200)}")
                return text
            }
        }
    }

    // ---- outcomes -----------------------------------------------------------

    sealed class Outcome {
        /** The long-lived coding-plan key ("apiKey.secretKey") + safe account identity. */
        data class Connected(val planApiKey: String, val accountName: String?, val accountEmail: String?) : Outcome()
        data class Failed(val stage: String, val reason: String) : Outcome()
        object Cancelled : Outcome()
    }

    enum class Stage { INITIATING, WAITING_FOR_BROWSER, POLLING, EXCHANGING, MINTING_KEY }

    data class Progress(
        val stage: Stage,
        val authorizeUrl: String? = null,
        val secondsLeft: Int? = null,
        val attempt: Int? = null,
    )

    private val inFlight = AtomicBoolean(false)
    @Volatile private var cancelRequested = false

    /** Request cancellation of the running flow (idempotent). */
    fun cancel() { cancelRequested = true }

    /**
     * Run the whole account login. [onProgress] is called on every stage
     * change and poll tick; it never receives any secret.
     */
    suspend fun login(onProgress: (Progress) -> Unit = {}): Outcome {
        if (!inFlight.compareAndSet(false, true)) {
            return Outcome.Failed("busy", "A login flow is already running.")
        }
        cancelRequested = false
        return try {
            withContext(Dispatchers.IO) { runFlow(onProgress) }
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun runFlow(onProgress: (Progress) -> Unit): Outcome {
        // 1. one-time poll token (32 random bytes hex). Memory-only.
        val pollToken = ByteArray(32).also { SECURE_RANDOM.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }

        // 2. init
        onProgress(Progress(Stage.INITIATING))
        val init = try {
            transport.post(
                INIT_URL,
                mapOf("Authorization" to "Bearer $pollToken", "Content-Type" to "application/json"),
                """{"provider":"zai"}""",
            ).let(ZaiProtocol::parseInit) ?: return Outcome.Failed("init", "The ZCode service returned an unusable init response.")
        } catch (e: TransportError) {
            return Outcome.Failed("init", "Could not start the login flow (HTTP ${e.httpCode ?: "?"}). ${e.message?.take(120)}")
        } catch (e: Exception) {
            kotlin.coroutines.coroutineContext.ensureActive()
            return Outcome.Failed("init", "Network error while starting the login flow.")
        }

        // 3. browser authorization — the caller opens init.authorizeUrl
        onProgress(Progress(Stage.WAITING_FOR_BROWSER, authorizeUrl = init.authorizeUrl))
        val deadlineAt = now() + (ZaiProtocol.relativeSeconds(init.expiresAtEpochMs, now()) ?: DEFAULT_EXPIRY_SECONDS) * 1000L

        // 4. bounded polling
        var attempt = 0
        var ready: ZaiProtocol.PollState.Ready? = null
        while (ready == null) {
            if (cancelRequested) return Outcome.Cancelled
            if (now() >= deadlineAt) return Outcome.Failed("poll", "The login window expired. Try connecting again.")
            attempt++
            onProgress(Progress(Stage.POLLING, authorizeUrl = init.authorizeUrl, attempt = attempt, secondsLeft = ((deadlineAt - now()) / 1000).toInt()))
            when (val state = try {
                transport.get(
                    "$POLL_URL/${init.flowId}",
                    mapOf("Authorization" to "Bearer $pollToken"),
                ).let(ZaiProtocol::parsePoll)
            } catch (e: TransportError) {
                return Outcome.Failed("poll", "Polling failed (HTTP ${e.httpCode ?: "?"}).")
            } catch (e: Exception) {
                kotlin.coroutines.coroutineContext.ensureActive()
                return Outcome.Failed("poll", "Network error while waiting for authorization.")
            }) {
                is ZaiProtocol.PollState.Ready -> ready = state
                ZaiProtocol.PollState.Denied -> return Outcome.Failed("poll", "The authorization was denied or cancelled.")
                is ZaiProtocol.PollState.Malformed -> return Outcome.Failed("poll", state.reason)
                ZaiProtocol.PollState.Pending -> {
                    // honor the server interval; also wake early for cancel checks
                    var waited = 0L
                    while (waited < init.pollIntervalSec * 1000L) {
                        if (cancelRequested) return Outcome.Cancelled
                        val step = minOf(250L, init.pollIntervalSec * 1000L - waited)
                        sleeper(step)
                        waited += step
                    }
                }
            }
        }

        // 5. exchange the Z.AI OAuth token for a business JWT
        onProgress(Progress(Stage.EXCHANGING))
        val businessToken = try {
            transport.post(
                BUSINESS_LOGIN_URL,
                mapOf("Content-Type" to "application/json"),
                """{"token":"${ready.zaiAccessToken}"}""",
            ).let(ZaiProtocol::parseBusinessLogin)
                ?: return Outcome.Failed("exchange", "Z.ai did not return a business token for this account.")
        } catch (e: TransportError) {
            return Outcome.Failed("exchange", "Z.ai business login failed (HTTP ${e.httpCode ?: "?"}).")
        } catch (e: Exception) {
            kotlin.coroutines.coroutineContext.ensureActive()
            return Outcome.Failed("exchange", "Network error during the token exchange.")
        }
        val auth = mapOf(
            "Authorization" to "Bearer $businessToken",
            "Content-Type" to "application/json",
        )

        // 6. resolve the coding-plan API key
        onProgress(Progress(Stage.MINTING_KEY))
        try {
            val info = transport.get("$API_BASE/api/biz/customer/getCustomerInfo", auth)
                .let(ZaiProtocol::parseCustomerInfo)
                ?: return Outcome.Failed("mint", "Could not resolve a Z.ai organization and project for the coding plan.")

            val listUrl = "$API_BASE/api/biz/v1/organization/${info.organizationId}/projects/${info.projectId}/api_keys"
            val existing = transport.get(listUrl, auth).let(ZaiProtocol::parseApiKeyList)
            val entry = existing.firstOrNull { it.name == PLAN_API_KEY_NAME }
                ?: transport.post(listUrl, auth, """{"name":"$PLAN_API_KEY_NAME"}""").let(ZaiProtocol::parseApiKeyCreate)
                ?: return Outcome.Failed("mint", "Could not create the coding-plan API key on Z.ai.")

            val secret = transport.get("$listUrl/copy/${entry.apiKey}", auth).let(ZaiProtocol::parseApiKeyCopy)
                ?: return Outcome.Failed("mint", "Z.ai did not return the secret for the coding-plan key.")

            val planApiKey = "${entry.apiKey}.$secret"
            return Outcome.Connected(planApiKey, ready.name, ready.email)
        } catch (e: TransportError) {
            return Outcome.Failed("mint", "Coding-plan key provisioning failed (HTTP ${e.httpCode ?: "?"}).")
        } catch (e: Exception) {
            kotlin.coroutines.coroutineContext.ensureActive()
            return Outcome.Failed("mint", "Network error while provisioning the coding-plan key.")
        }
    }

    companion object {
        const val INIT_URL = "https://zcode.z.ai/api/v1/oauth/cli/init"
        const val POLL_URL = "https://zcode.z.ai/api/v1/oauth/cli/poll"
        const val BUSINESS_LOGIN_URL = "https://api.z.ai/api/auth/z/login"
        const val API_BASE = "https://api.z.ai"
        const val PLAN_API_KEY_NAME = "zcode-api-key"
        const val DEFAULT_EXPIRY_SECONDS = 300
        private val SECURE_RANDOM = SecureRandom()
    }
}
