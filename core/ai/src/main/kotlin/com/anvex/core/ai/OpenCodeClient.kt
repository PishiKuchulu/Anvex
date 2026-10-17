package com.anvex.core.ai

import com.anvex.core.model.ModelInfo
import com.anvex.core.model.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OpenCode adapter (v4.4.1) — one isolated client for both OpenCode tiers:
 *
 *  * [ProviderKind.OPENCODE_FREE] — no credential. Model discovery is public
 *    (verified live 2026-10-15: GET /inference/v1/models answers HTTP 200 with the
 *    full catalog), but the chat gateway answers **HTTP 403 FreeTierError** for
 *    any client that is not the official OpenCode app. Anvex does NOT reproduce
 *    the official client fingerprint (User-Agent `opencode/<version>`,
 *    `x-opencode-client`, `x-opencode-session`, `Bearer public`, forced tool list —
 *    the approach taken by the opencode-free-9router reference adapter). The 403
 *    is surfaced verbatim as an [AiError.Kind.ACCESS_RESTRICTED] failure instead,
 *    and [validateConnection] reports both facts separately: discovery works,
 *    chat is restricted.
 *
 *  * [ProviderKind.OPENCODE_ZEN] — the API-key tier ("OpenCode Console", formerly
 *    Zen). Documented auth: `Authorization: Bearer <console API key>`. Verified
 *    live 2026-10-15: the models endpoint validates the bearer when present
 *    (bogus key -> HTTP 401 "Invalid credential"), so key validation costs zero
 *    tokens. Chat runs over the same OpenAI wire protocol as the free tier.
 *
 * Endpoint layout (verified live 2026-10-15):
 *  * chat:  `{base}/chat/completions` where base = https://opencode.ai/inference/openai/v1
 *  * models: https://opencode.ai/inference/v1/models — i.e. the FAMILY ROOT, not
 *    `{base}/models` (that path 404s). [modelsUrl] derives it by dropping the
 *    `/openai` family segment; legacy rows persisted with the v4.4 base
 *    (https://opencode.ai/zen/v1) keep working because `{base}/models` is used
 *    as the fallback for any other base shape.
 *
 * Transport reuse: streaming, SSE parsing, reasoning deltas, usage parsing and
 * cancellation semantics come from [OpenAiCompatibleClient] — the only change is
 * an honest `User-Agent: Anvex/<version> (Android)` via an OkHttp interceptor,
 * so OpenCode-specific handling cannot drift into the shared client.
 */
class OpenCodeClient(
    private val runtime: ProviderRuntimeConfig,
    http: OkHttpClient = defaultHttpClient(),
) : ProviderClient {

    override val kind = runtime.config.kind

    private val json = Sse.json
    private val base = runtime.config.baseUrl.trimEnd('/')
    private val apiKey = runtime.apiKey

    /**
     * Honest client identity. Deliberately NOT the official OpenCode client's
     * User-Agent or any attestation header — sending those would be client
     * impersonation to bypass the free-tier gate (explicitly out of bounds).
     */
    private val honestHttp: OkHttpClient = http.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", HONEST_USER_AGENT).build())
        }
        .build()

    private val transport = OpenAiCompatibleClient(runtime, honestHttp)

    /** Chat streaming: shared OpenAI wire protocol (SSE, reasoning deltas, usage, cancellation). */
    override fun streamChat(request: ChatRequest): Flow<StreamEvent> = transport.streamChat(request)

    /**
     * The models endpoint of the official layout lives at the inference family
     * root (`/inference/v1/models`), not next to chat (`/inference/openai/v1/models`
     * answers 404 — verified live). Bases that don't follow that shape (legacy
     * `/zen/v1` rows, custom mirrors) fall back to `{base}/models`.
     */
    internal fun modelsUrl(): String = when {
        base.endsWith("/openai/v1") -> base.removeSuffix("/openai/v1") + "/v1/models"
        else -> "$base/models"
    }

    /**
     * Live model discovery — never a hardcoded list. The free tier filters to the
     * `-free`-suffixed ids the endpoint itself reports (the only free-tier signal
     * the endpoint exposes; the upstream pricing page additionally lists a few
     * free models without the suffix — the user can still type any id, the chat
     * gate applies regardless). The Console tier lists the whole catalog.
     */
    override suspend fun listModels(): List<ModelInfo> {
        val builder = Request.Builder()
            .url(modelsUrl())
            .get()
            .header("User-Agent", HONEST_USER_AGENT)
        // Console: the bearer both authenticates and (when invalid) gets 401 here —
        // free tier: no credential exists, none is sent.
        if (kind == ProviderKind.OPENCODE_ZEN && apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        val models = makeCall(honestHttp, builder.build()) { bodyText ->
            // Malformed/non-JSON bodies degrade to an empty list, never a crash.
            val root = runCatching { json.parseToJsonElement(bodyText).jsonObjectOrNull }.getOrNull()
                ?: return@makeCall emptyList()
            val data = root["data"]?.jsonArrayOrNull ?: return@makeCall emptyList()
            data.mapNotNull { el ->
                val o = el.jsonObjectOrNull ?: return@mapNotNull null
                val id = o["id"]?.jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
                ModelInfo(id = id, displayName = o["display_name"]?.jsonPrimitiveOrNull?.contentOrNull ?: id)
            }
        }
        return if (kind == ProviderKind.OPENCODE_FREE) models.filter { it.id.endsWith("-free") } else models
    }

    /**
     * Honest connection test — reports what actually works, never a fabricated OK.
     *
     *  * FREE: (1) public model discovery must answer 200; (2) a minimal chat
     *    probe then reports the real gate state. Today that probe returns
     *    HTTP 403 FreeTierError, which is reported verbatim (sanitized to the
     *    upstream message) with the exact status code — clearly labeled as an
     *    upstream restriction, distinct from connection/auth/rate-limit errors.
     *  * ZEN: an authenticated models fetch (the endpoint validates the bearer)
     *    verifies the key at zero token cost. A 401 is surfaced verbatim.
     */
    override suspend fun validateConnection(): ValidationResult {
        val t0 = System.currentTimeMillis()
        // Console without a pasted key: the public models endpoint would answer
        // 200 regardless, so a "verified" claim here would be false. Refuse first.
        if (kind == ProviderKind.OPENCODE_ZEN && apiKey.isBlank()) {
            return ValidationResult(false, 0, "No API key set — paste your OpenCode Console API key (create one at opencode.ai console).")
        }
        return try {
            val models = listModels()
            when (kind) {
                ProviderKind.OPENCODE_FREE -> validateFreeTierChat(t0, models.size)
                else -> ValidationResult(
                    ok = true,
                    latencyMs = System.currentTimeMillis() - t0,
                    message = "Connected. ${models.size} models available (API key verified).",
                    modelsFound = models.size,
                )
            }
        } catch (e: AiHttpException) {
            ValidationResult(
                false,
                System.currentTimeMillis() - t0,
                describeHttpFailure(e),
            )
        } catch (e: Exception) {
            ValidationResult(false, System.currentTimeMillis() - t0, "Network error: ${e.message ?: "unreachable"}")
        }
    }

    /**
     * Free-tier second step: probe chat with the user's selected model and report
     * the true gate state. A 200 here would mean upstream policy changed — report
     * success honestly then; anything else is reported with its exact status.
     */
    private suspend fun validateFreeTierChat(t0: Long, modelsFound: Int): ValidationResult {
        return try {
            transport.probeChatEndpoint(honestHttp)
            ValidationResult(
                true,
                System.currentTimeMillis() - t0,
                "Connected — chat endpoint answered (upstream policy now permits direct clients). $modelsFound `-free` models listed.",
                modelsFound,
            )
        } catch (e: AiHttpException) {
            ValidationResult(
                false,
                System.currentTimeMillis() - t0,
                "Model discovery OK ($modelsFound `-free` models listed), but chat is blocked upstream: " +
                    describeHttpFailure(e) +
                    ". OpenCode's free tier is restricted to the official OpenCode client; Anvex will not impersonate it. " +
                    "Use \"OpenCode Console (API key)\" for direct access with an API key.",
            )
        } catch (e: Exception) {
            ValidationResult(
                false,
                System.currentTimeMillis() - t0,
                "Model discovery OK ($modelsFound `-free` models listed) but the chat probe failed: ${e.message ?: "unreachable"}",
            )
        }
    }

    private fun describeHttpFailure(e: AiHttpException): String {
        val upstream = e.body?.let { Sse.extractErrorMessage(it) }
        return "HTTP ${e.code} — ${upstream ?: e.message ?: "request failed"}"
    }

    companion object {
        /** Honest identity, fixed on purpose (see class kdoc). */
        const val HONEST_USER_AGENT = "Anvex/4.4 (Android; +https://anvex.app)"
    }
}
