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
                        // M-16: surface Retry-After so failover can back off instead of hammering.
                        val retryAfter = resp.header("Retry-After")?.toLongOrNull()
                        onError(mapHttpError(resp.code, body, retryAfter))
                        close()
                        return@launch
                    }
                    val source = resp.body?.source()
                        ?: run { onError(AiError(AiError.Kind.PROTOCOL, "Empty response body")); close(); return@launch }
                    var event: String? = null
                    var lastId: String? = null
                    val data = StringBuilder()
                    var hasDataField = false
                    // M-16: a 200 with a non-SSE JSON body (gateway/proxy error)
                    // used to yield only "Stream ended without content". Collect
                    // non-SSE lines (capped) so the real error can be surfaced.
                    var sentEvents = 0
                    val nonSseBody = StringBuilder()
                    while (true) {
                        val line = try {
                            readLineCapped(source) ?: break
                        } catch (e: LineTooLongException) {
                            if (call.isCanceled()) return@launch
                            onError(AiError(AiError.Kind.PROTOCOL, e.message ?: "SSE line too long"))
                            close()
                            return@launch
                        } catch (e: IOException) {
                            if (call.isCanceled()) return@launch
                            onError(AiError(AiError.Kind.NETWORK, e.message ?: "connection lost"))
                            close()
                            return@launch
                        }
                        when {
                            line.isEmpty() -> {
                                // Blank line = event boundary. An explicit empty data field is still a valid SSE event.
                                if (hasDataField) { send(SseEvent(event, data.toString(), lastId)); sentEvents++ }
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
                            else -> {
                                // Unknown field per spec — but a body made only of
                                // such lines is not SSE at all (gateway error JSON).
                                if (nonSseBody.length < NON_SSE_BODY_CAP) {
                                    nonSseBody.append(line).append('\n')
                                }
                            }
                        }
                    }
                    // EOF without trailing blank line: flush any pending event.
                    if (hasDataField) { send(SseEvent(event, data.toString(), lastId)); sentEvents++ }
                    if (sentEvents == 0 && nonSseBody.isNotBlank()) {
                        val raw = nonSseBody.toString()
                        onError(
                            AiError(
                                AiError.Kind.PROTOCOL,
                                extractErrorMessage(raw)
                                    ?: "Provider returned a non-streaming response: ${raw.take(500)}",
                            )
                        )
                    }
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

    fun mapHttpError(code: Int, body: String, retryAfterSeconds: Long? = null): AiError {
        val kind = when (code) {
            // v4.4.1: 401 and 403 split — a bad/missing key is AUTH; a policy refusal
            // (e.g. OpenCode FreeTierError) is ACCESS_RESTRICTED. Different user actions.
            401 -> AiError.Kind.AUTH
            // M-16: 402 is quota/credits exhausted, not a generic protocol error.
            402 -> AiError.Kind.RATE_LIMIT
            403 -> AiError.Kind.ACCESS_RESTRICTED
            404 -> AiError.Kind.NOT_FOUND
            // M-16: 408 is a network timeout; 413/422 stay PROTOCOL but keep
            // their explicit HTTP code in the message below.
            408 -> AiError.Kind.NETWORK
            429 -> AiError.Kind.RATE_LIMIT
            in 500..599 -> AiError.Kind.NETWORK
            else -> AiError.Kind.PROTOCOL
        }
        val detail = extractErrorMessage(body)
        val base = detail ?: "HTTP $code"
        val message = if (retryAfterSeconds != null) "$base (retry after ${retryAfterSeconds}s)" else base
        return AiError(kind, message, code, retryAfterSeconds)
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
    /** Cap for the collected non-SSE body (M-16): enough for a gateway error JSON, never unbounded. */
    private const val NON_SSE_BODY_CAP = 8_192

    /** M-16: `readUtf8Line()` is unbounded (a malicious server can OOM the app with one line). */
    private class LineTooLongException : IOException("SSE line exceeds 1 MB cap")

    /**
     * Reads one SSE line (LF or CRLF terminated) with a byte cap, decoding
     * UTF-8 only at the line boundary so multi-byte characters split across
     * reads stay intact. Returns null at EOF with nothing buffered.
     */
    private fun readLineCapped(source: okio.BufferedSource, limit: Int = 1_048_576): String? {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            if (out.size() > limit) throw LineTooLongException()
            val b = try {
                source.readByte()
            } catch (e: java.io.EOFException) {
                return if (out.size() == 0) null else String(out.toByteArray(), Charsets.UTF_8)
            }
            if (b == '\n'.code.toByte()) break
            if (b != '\r'.code.toByte()) out.write(b.toInt())
        }
        return String(out.toByteArray(), Charsets.UTF_8)
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
