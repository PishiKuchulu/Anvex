package com.anvex.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A parsed SSE frame (event name + joined data payload). */
data class SseEvent(
    val event: String?,
    val data: String,
    /** Last `id:` seen before this event (null if none). Used for diagnostics only —
     * we never auto-reconnect (providers are one-shot request streams). */
    val id: String? = null,
)

/**
 * Hand-written Server-Sent-Events reader over OkHttp (no Node AI SDK — port
 * requirement §3/§9). Reads line-by-line from the response body; emits frames
 * on blank-line boundaries; cancellation cancels the HTTP call.
 *
 * v1.1 §16 hardening:
 *  - `data:` strips exactly ONE leading space (per the SSE spec) instead of all
 *    leading whitespace, so JSON payloads with meaningful indentation survive;
 *  - `id:` / `retry:` lines are recognized and handled (id surfaced on events;
 *    retry accepted and ignored — we never auto-reconnect a chat stream);
 *  - `event`/`data` arriving in any order within a frame is handled;
 *  - fragmented chunks, multiple events per HTTP chunk, CRLF line endings,
 *    UTF-8 boundaries (readUtf8Line), keep-alive comments, and missing blank
 *    line at EOF (trailing event flush) are all handled;
 *  - every failure path (HTTP error, IOException, malformed stream) maps to an
 *    [AiError]; cancellation is detected via `call.isCanceled()` and stays silent.
 */
internal object Sse {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun stream(
        client: OkHttpClient,
        request: Request,
        onError: (AiError) -> Unit,
    ): Flow<SseEvent> = channelFlow {
        val call = client.newCall(request)
        launch(Dispatchers.IO) {
            try {
                val response = call.execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val body = try { resp.body?.string() ?: "" } catch (_: Exception) { "" }
                        onError(mapHttpError(resp.code, body))
                        close()
                        return@launch
                    }
                    val source = resp.body?.source()
                        ?: run { onError(AiError(AiError.Kind.PROTOCOL, "Empty response body")); close(); return@launch }
                    var event: String? = null
                    var lastId: String? = null
                    val data = StringBuilder()
                    var hasDataField = false
                    while (true) {
                        val line = try {
                            source.readUtf8Line() ?: break
                        } catch (e: IOException) {
                            if (call.isCanceled()) return@launch
                            onError(AiError(AiError.Kind.NETWORK, e.message ?: "connection lost"))
                            close()
                            return@launch
                        }
                        when {
                            line.isEmpty() -> {
                                // Blank line = event boundary. An explicit empty data field is still a valid SSE event.
                                if (hasDataField) send(SseEvent(event, data.toString(), lastId))
                                event = null
                                data.setLength(0)
                                hasDataField = false
                            }
                            line.startsWith(":") -> Unit // keep-alive comment / heartbeat
                            line.startsWith("event:") -> event = stripSseField(line, "event:")
                            line.startsWith("data:") -> {
                                if (hasDataField) data.append('\n')
                                data.append(stripSseField(line, "data:"))
                                hasDataField = true
                            }
                            line.startsWith("id:") -> {
                                // Server-set event id; accepted, surfaced on the next event.
                                stripSseField(line, "id:").takeIf { it.isNotEmpty() }?.let { lastId = it }
                            }
                            line.startsWith("retry:") -> {
                                // Reconnection hint — deliberately ignored: chat streams are
                                // one-shot requests; auto-retry would duplicate prompts.
                            }
                            else -> Unit // unknown field per spec: ignore
                        }
                    }
                    // EOF without trailing blank line: flush any pending event.
                    if (hasDataField) send(SseEvent(event, data.toString(), lastId))
                    close()
                }
            } catch (e: IOException) {
                if (!call.isCanceled()) {
                    onError(AiError(AiError.Kind.NETWORK, e.message ?: "network error"))
                }
                close()
            } catch (e: Exception) {
                if (!call.isCanceled()) {
                    onError(AiError(AiError.Kind.PROTOCOL, e.message ?: "unexpected error"))
                }
                close()
            }
        }
        awaitClose { call.cancel() }
    }

    fun mapHttpError(code: Int, body: String): AiError {
        val kind = when (code) {
            // v4.4.1: 401 and 403 split — a bad/missing key is AUTH; a policy refusal
            // (e.g. OpenCode FreeTierError) is ACCESS_RESTRICTED. Different user actions.
            401 -> AiError.Kind.AUTH
            403 -> AiError.Kind.ACCESS_RESTRICTED
            404 -> AiError.Kind.NOT_FOUND
            429 -> AiError.Kind.RATE_LIMIT
            in 500..599 -> AiError.Kind.NETWORK
            else -> AiError.Kind.PROTOCOL
        }
        val detail = extractErrorMessage(body)
        return AiError(kind, detail ?: "HTTP $code", code)
    }

    /** Pull `error.message` from common provider error bodies, defensively. */
    fun extractErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val obj = json.parseToJsonElement(body.trim()).jsonObjectOrNull ?: return null
            val err = obj["error"]?.jsonObjectOrNull
            (err?.get("message")?.jsonPrimitiveOrNull?.content)
                ?: (err?.get("type")?.jsonPrimitiveOrNull?.content)?.let { "error type: $it" }
                ?: obj["message"]?.jsonPrimitiveOrNull?.content
                ?: null
        } catch (_: Exception) {
            null
        }
    }
    /**
     * SSE field value: strip the field prefix then EXACTLY ONE leading space
     * (spec: "If the field value starts with a U+0020 SPACE, remove it"). `.trim()`
     * would corrupt data whose leading spaces are meaningful.
     */
    private fun stripSseField(line: String, prefix: String): String {
        val value = line.removePrefix(prefix)
        return if (value.startsWith(" ")) value.substring(1) else value
    }
}

internal val kotlinx.serialization.json.JsonElement.jsonObjectOrNull
    get() = this as? kotlinx.serialization.json.JsonObject
internal val kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull
    get() = this as? kotlinx.serialization.json.JsonPrimitive
internal val kotlinx.serialization.json.JsonElement.jsonArrayOrNull
    get() = this as? kotlinx.serialization.json.JsonArray
