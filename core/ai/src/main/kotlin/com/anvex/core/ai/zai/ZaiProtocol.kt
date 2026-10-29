package com.anvex.core.ai.zai

import com.anvex.core.ai.jsonArrayOrNull
import com.anvex.core.ai.jsonObjectOrNull
import com.anvex.core.ai.jsonPrimitiveOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * v4.4: pure parsing for the Z.ai GLM Coding Plan account flow.
 *
 * Protocol source: 9Router (https://github.com/decolua/9router, commit ce4460e)
 * src/lib/oauth/providers/glm.js — which itself mirrors the official ZCode CLI
 * (apps/zcode-cli adapters/auth/cli-oauth.ts + coding-plan-api-key.ts).
 * Every function here is envelope-aware ({code, msg, data}; code 0/200/absent
 * = success) and totally side-effect free so it is trivially unit-testable.
 */
object ZaiProtocol {

    val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- envelopes ---------------------------------------------------------

    fun isSuccessCode(code: Long?): Boolean = code == null || code == 0L || code == 200L

    private fun envelope(body: String): JsonObject? = try {
        json.parseToJsonElement(body).jsonObjectOrNull
    } catch (_: Exception) {
        null
    }

    private fun dataOf(root: JsonObject): JsonObject? =
        root["data"]?.jsonObjectOrNull

    // ---- step 1: cli/init ----------------------------------------------------

    data class InitData(
        val flowId: String,
        val authorizeUrl: String,
        val pollIntervalSec: Int,
        val expiresAtEpochMs: Long?,
    )

    fun parseInit(body: String): InitData? {
        val root = envelope(body) ?: return null
        if (!isSuccessCode(root["code"]?.jsonPrimitiveOrNull?.longOrNull)) return null
        val data = dataOf(root) ?: return null
        val flowId = data["flow_id"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null
        val authorizeUrl = data["authorize_url"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null
        // L-14: the old check accepted ANY https URL — a tampered init
        // response could send the user to an attacker site to "authorize".
        // The authorize page must live on Z.ai itself.
        val authorizeHost = runCatching { java.net.URI(authorizeUrl).host?.lowercase() }.getOrNull()
        if (!authorizeUrl.startsWith("https://") ||
            !(authorizeHost == "z.ai" || (authorizeHost?.endsWith(".z.ai") == true))
        ) return null
        val interval = (data["poll_interval_sec"]?.jsonPrimitiveOrNull?.longOrNull ?: 3L).coerceAtLeast(1L)
        val expiresRaw = data["expires_at"]?.jsonPrimitiveOrNull?.longOrNull
        val expiresMs = expiresRaw?.let { raw ->
            if (raw <= 0) null else if (raw > 1e12.toLong()) raw else raw * 1000
        }
        return InitData(flowId, authorizeUrl, interval.toInt(), expiresMs)
    }

    // ---- step 2: cli/poll ----------------------------------------------------

    sealed class PollState {
        object Pending : PollState()
        /** Upstream reports the user failed / cancelled the authorization. */
        object Denied : PollState()
        data class Ready(val zaiAccessToken: String, val name: String?, val email: String?) : PollState()
        /** Structurally valid response but no usable credential — fail safe. */
        data class Malformed(val reason: String) : PollState()
    }

    fun parsePoll(body: String): PollState {
        val root = envelope(body) ?: return PollState.Malformed("poll response is not JSON")
        if (!isSuccessCode(root["code"]?.jsonPrimitiveOrNull?.longOrNull)) {
            return PollState.Malformed(root["msg"]?.jsonPrimitiveOrNull?.contentOrNull ?: "poll business error")
        }
        val data = dataOf(root) ?: return PollState.Malformed("poll response has no data")
        return when (data["status"]?.jsonPrimitiveOrNull?.contentOrNull) {
            "pending" -> PollState.Pending
            "failed" -> PollState.Denied
            "ready" -> {
                // ready nests the ZAI OAuth tokens under data[providerId] (zai):
                // { status:"ready", token, user, zai: { access_token, refresh_token? } }.
                val providerData = data["zai"]?.jsonObjectOrNull
                val zaiAccessToken = providerData?.get("access_token")?.jsonPrimitiveOrNull?.contentOrNull
                    ?: providerData?.get("accessToken")?.jsonPrimitiveOrNull?.contentOrNull
                    ?: data["access_token"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: data["accessToken"]?.jsonPrimitiveOrNull?.contentOrNull
                if (zaiAccessToken.isNullOrBlank()) {
                    PollState.Malformed("ready payload is missing the Z.ai access token")
                } else {
                    val user = data["user"]?.jsonObjectOrNull
                    PollState.Ready(
                        zaiAccessToken = zaiAccessToken,
                        name = user?.get("name")?.jsonPrimitiveOrNull?.contentOrNull,
                        email = user?.get("email")?.jsonPrimitiveOrNull?.contentOrNull,
                    )
                }
            }
            else -> PollState.Malformed("unknown poll status")
        }
    }

    // ---- step 3: business login ----------------------------------------------

    /** POST {businessLoginUrl} {"token": zaiAccessToken} -> data.access_token (business JWT). */
    fun parseBusinessLogin(body: String): String? {
        val root = envelope(body) ?: return null
        if (!isSuccessCode(root["code"]?.jsonPrimitiveOrNull?.longOrNull)) return null
        val data = dataOf(root)
        val token = data?.get("access_token")?.jsonPrimitiveOrNull?.contentOrNull
            ?: data?.get("accessToken")?.jsonPrimitiveOrNull?.contentOrNull
        return token?.takeIf { it.isNotBlank() }
    }

    // ---- step 4: customer info -> default org/project ---------------------------

    data class OrgProject(val organizationId: String, val projectId: String)

    fun parseCustomerInfo(body: String): OrgProject? {
        val root = envelope(body) ?: return null
        val organizations = root["organizations"]?.jsonArrayOrNull
            ?: dataOf(root)?.get("organizations")?.jsonArrayOrNull
            ?: return null
        val candidates = organizations.mapNotNull { orgEl ->
            val org = orgEl.jsonObjectOrNull ?: return@mapNotNull null
            val projects = (org["projects"]?.jsonArrayOrNull ?: emptyList())
                .mapNotNull { it.jsonObjectOrNull }
                .filter { (it["projectType"]?.jsonPrimitiveOrNull?.contentOrNull ?: "").trim() != "2" }
            val orgId = org["organizationId"]?.jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
            if (projects.isEmpty()) null
            else org["organizationName"]?.jsonPrimitiveOrNull?.contentOrNull to (orgId to projects)
        }
        if (candidates.isEmpty()) return null
        fun isDefault(name: String?): Boolean {
            val n = (name ?: "").trim().lowercase()
            return n.contains("默认机构") || n.contains("默认项目") || n == "default"
        }
        val chosen = candidates.firstOrNull { isDefault(it.first) } ?: candidates.first()
        val projects = chosen.second.second
        val project = projects.firstOrNull {
            isDefault(it["projectName"]?.jsonPrimitiveOrNull?.contentOrNull)
        } ?: projects.first()
        val projectId = project["projectId"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null
        return OrgProject(chosen.second.first, projectId)
    }

    // ---- step 5: api keys ------------------------------------------------------

    data class ApiKeyEntry(val name: String, val apiKey: String)

    fun parseApiKeyList(body: String): List<ApiKeyEntry> {
        // The api_keys endpoint returns the list directly (bare JSON array),
        // possibly wrapped as {data: [...]}.
        val list = envelope(body)?.get("data")?.jsonArrayOrNull
            ?: runCatching { json.parseToJsonElement(body).jsonArrayOrNull }.getOrNull()
            ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el.jsonObjectOrNull ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
            val apiKey = o["apiKey"]?.jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
            ApiKeyEntry(name, apiKey)
        }
    }

    /** POST create returns the created entry (either {code,data:{...}} or the bare entry). */
    fun parseApiKeyCreate(body: String): ApiKeyEntry? {
        val data = envelope(body)?.let { dataOf(it) }
            ?: runCatching { json.parseToJsonElement(body) }.getOrNull()?.jsonObjectOrNull
            ?: return null
        val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null
        val apiKey = data["apiKey"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null
        return ApiKeyEntry(name, apiKey)
    }

    /** GET api_keys/copy/{key} -> { data: { secretKey } }. */
    fun parseApiKeyCopy(body: String): String? {
        val root = envelope(body) ?: return null
        val data = dataOf(root) ?: return null
        return data["secretKey"]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    // ---- misc -----------------------------------------------------------------

    /** Absolute epoch (s or ms) -> seconds from now; null when absent/invalid/past. */
    fun relativeSeconds(expiresAtEpochMs: Long?, nowMs: Long = System.currentTimeMillis()): Int? {
        if (expiresAtEpochMs == null || expiresAtEpochMs <= 0) return null
        val seconds = ((expiresAtEpochMs - nowMs) / 1000).toInt()
        return if (seconds > 0) seconds else null
    }
}
