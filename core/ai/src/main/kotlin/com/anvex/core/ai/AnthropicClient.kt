package com.anvex.core.ai

import com.anvex.core.model.MessageRole
import com.anvex.core.model.ModelInfo
import com.anvex.core.model.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Anthropic Messages API client (`/v1/messages` + SSE), covering Anthropic and
 * user-configured Anthropic-compatible endpoints (brief §11).
 */
class AnthropicClient(
    private val runtime: ProviderRuntimeConfig,
    private val http: OkHttpClient = defaultHttpClient(),
) : ProviderClient {

    override val kind = runtime.config.kind

    private val json = Sse.json
    private val base = runtime.config.baseUrl.trimEnd('/')

    private fun requestBuilder(): Request.Builder {
        val b = Request.Builder()
            .header("x-api-key", runtime.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
        for ((k, v) in runtime.config.extraHeaders) b.header(k, v)
        return b
    }

    override fun streamChat(request: ChatRequest): Flow<StreamEvent> = flow {
        val lastUserIndex = request.turns.indexOfLast { it.role == MessageRole.USER }
        val messages = buildJsonArray {
            request.turns.forEachIndexed { index, turn ->
                val isLastUser = turn.role == MessageRole.USER && index == lastUserIndex
                add(buildJsonObject {
                    put("role", if (turn.role == MessageRole.USER) "user" else "assistant")
                    if (isLastUser && request.attachments.isNotEmpty()) {
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", turn.content) })
                            request.attachments.forEach { a ->
                                when {
                                    a.isImage && a.base64 != null -> add(buildJsonObject {
                                        put("type", "image")
                                        put("source", buildJsonObject {
                                            put("type", "base64"); put("media_type", a.mimeType); put("data", a.base64)
                                        })
                                    })
                                    !a.text.isNullOrBlank() -> add(buildJsonObject { put("type", "text"); put("text", "\n[Attachment: ${a.name}]\n${a.text}") })
                                }
                            }
                        })
                    } else put("content", turn.content)
                })
            }
        }
        val body = buildJsonObject {
            put("model", runtime.config.model)
            // v4.4 (architect feature 8): prompt caching. Real Anthropic supports
            // cache_control on the system block; z.ai (ZAI_ACCOUNT) may not, so the
            // block form is only used for the genuine Anthropic kind.
            if (runtime.config.kind == com.anvex.core.model.ProviderKind.ANTHROPIC) {
                put("system", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", request.systemPrompt)
                        put("cache_control", buildJsonObject { put("type", "ephemeral") })
                    })
                })
            } else {
                put("system", request.systemPrompt)
            }
            put("messages", messages)
            if (request.webSearch) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "web_search_20250305")
                        put("name", "web_search")
                        put("max_uses", 3)
                    })
                })
            }
            put("stream", true)
            val modelLower = runtime.config.model.lowercase()
            val adaptive = modelLower.contains("opus-4-6") || modelLower.contains("sonnet-4-6") ||
                modelLower.contains("opus-4.6") || modelLower.contains("sonnet-4.6") ||
                modelLower.contains("claude-4-7") || modelLower.contains("claude-4.7") ||
                modelLower.contains("claude-5")
            val requestedMax = request.maxTokens ?: runtime.config.maxTokens ?: 8192
            val effectiveMax = if (!adaptive && request.thinkingLevel != com.anvex.core.model.ThinkingLevel.NORMAL) {
                maxOf(requestedMax, request.thinkingLevel.budgetTokens + 1024)
            } else requestedMax
            put("max_tokens", effectiveMax)
            (request.temperature ?: runtime.config.temperature)?.let { put("temperature", it) }
            if (request.thinkingLevel != com.anvex.core.model.ThinkingLevel.NORMAL) {
                if (adaptive) {
                    val effort = when (request.thinkingLevel) {
                        com.anvex.core.model.ThinkingLevel.LOW -> "low"
                        com.anvex.core.model.ThinkingLevel.NORMAL -> "medium"
                        com.anvex.core.model.ThinkingLevel.HIGH,
                        com.anvex.core.model.ThinkingLevel.XHIGH,
                        com.anvex.core.model.ThinkingLevel.MAX -> "high"
                    }
                    put("thinking", buildJsonObject { put("type", "adaptive") })
                    put("output_config", buildJsonObject { put("effort", effort) })
                } else {
                    put("thinking", buildJsonObject { put("type", "enabled"); put("budget_tokens", request.thinkingLevel.budgetTokens) })
                }
            }
        }
        val req = requestBuilder()
            .url("$base/v1/messages")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var usage: Usage? = null
        var failure: AiError? = null
        var tokenCount = 0
        var stopReason: String? = null
        var sawStop = false

        Sse.stream(http, req) { err -> failure = err }.collect { frame ->
            val obj = try { json.parseToJsonElement(frame.data).jsonObjectOrNull } catch (_: Exception) { null }
                ?: return@collect
            when (frame.event) {
                "message_start" -> {
                    val u = obj["message"]?.jsonObjectOrNull?.get("usage")?.let(::parseUsage)
                    if (u != null) usage = mergeUsage(usage, u)
                }
                "content_block_delta" -> {
                    val delta = obj["delta"]?.jsonObjectOrNull
                    when (delta?.get("type")?.jsonPrimitiveOrNull?.contentOrNull) {
                        "text_delta" -> {
                            val text = delta["text"]?.jsonPrimitiveOrNull?.contentOrNull
                            if (!text.isNullOrEmpty()) {
                                tokenCount++
                                emit(StreamEvent.Token(text))
                            }
                        }
                        // v4.4 (architect feature 1): extended thinking deltas.
                        "thinking_delta" -> {
                            val thought = delta["thinking"]?.jsonPrimitiveOrNull?.contentOrNull
                            if (!thought.isNullOrEmpty()) emit(StreamEvent.Reasoning(thought))
                        }
                    }
                }
                "message_delta" -> {
                    obj["usage"]?.let { u ->
                        val parsed = parseUsage(u)
                        if (parsed != null) usage = mergeUsage(usage, parsed)
                    }
                    obj["delta"]?.jsonObjectOrNull?.get("stop_reason")?.jsonPrimitiveOrNull?.contentOrNull
                        ?.let { stopReason = it }
                }
                "content_block_start" -> {
                    val block = obj["content_block"]?.jsonObjectOrNull
                    if (block?.get("type")?.jsonPrimitiveOrNull?.contentOrNull == "web_search_tool_result") {
                        val sources = block["content"]?.jsonArrayOrNull?.mapNotNull { r ->
                            val o = r.jsonObjectOrNull ?: return@mapNotNull null
                            val url = o["url"]?.jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
                            WebSource(o["title"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(), url)
                        }.orEmpty()
                        if (sources.isNotEmpty()) emit(StreamEvent.Sources(sources))
                    }
                }
                "message_stop" -> sawStop = true
                "error" -> {
                    val msg = obj["error"]?.jsonObjectOrNull?.get("message")?.jsonPrimitiveOrNull?.contentOrNull
                        ?: "Provider stream error"
                    failure = AiError(AiError.Kind.PROTOCOL, msg)
                }
            }
        }
        val err = failure
        when {
            err != null -> emit(StreamEvent.Failed(err))
            sawStop || tokenCount > 0 -> emit(StreamEvent.Completed(stopReason, usage))
            else -> emit(StreamEvent.Failed(AiError(AiError.Kind.PROTOCOL, "Stream ended without content")))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(): List<ModelInfo> {
        val req = requestBuilder().url("$base/v1/models?limit=1000").get().build()
        val listed = makeCall(http, req) { text ->
            val root = json.parseToJsonElement(text).jsonObjectOrNull
            val data = root?.get("data")?.jsonArrayOrNull ?: return@makeCall emptyList()
            data.mapNotNull { el ->
                val o = el.jsonObjectOrNull ?: return@mapNotNull null
                val id = o["id"]?.jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
                ModelInfo(id = id, displayName = o["display_name"]?.jsonPrimitiveOrNull?.contentOrNull ?: id)
            }
        }
        // v4.4: the GLM Coding Plan (ZAI_ACCOUNT) does not reliably expose Anthropic-style
        // /v1/models (verified live: business envelope code 1001 without a key). Fall back
        // to the 9Router-verified GLM catalog instead of an empty list — honest and useful.
        return if (runtime.config.kind == com.anvex.core.model.ProviderKind.ZAI_ACCOUNT && listed.isEmpty()) {
            GLM_CODING_PLAN_MODELS
        } else listed
    }

    override suspend fun validateConnection(): ValidationResult {
        val t0 = System.currentTimeMillis()
        return try {
            val models = listModels()
            ValidationResult(true, System.currentTimeMillis() - t0, "Connected. ${models.size} models available.", models.size)
        } catch (e: AiHttpException) {
            if (e.code == 404 || e.code == 405) validateViaMinimalChat()
            else ValidationResult(false, System.currentTimeMillis() - t0, Sse.mapHttpError(e.code, e.body ?: "").message)
        } catch (e: Exception) {
            ValidationResult(false, System.currentTimeMillis() - t0, "Network error: ${e.message ?: "unreachable"}")
        }
    }

    private suspend fun validateViaMinimalChat(): ValidationResult {
        val t0 = System.currentTimeMillis()
        return try {
            val body = buildJsonObject {
                put("model", runtime.config.model)
                put("max_tokens", 1)
                put("messages", buildJsonArray {
                    add(buildJsonObject { put("role", "user"); put("content", "ping") })
                })
            }
            val req = requestBuilder()
                .url("$base/v1/messages")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            makeCall(http, req) { "OK" }
            ValidationResult(true, System.currentTimeMillis() - t0, "Connected (messages endpoint responded).")
        } catch (e: AiHttpException) {
            ValidationResult(false, System.currentTimeMillis() - t0, Sse.mapHttpError(e.code, e.body ?: "").message)
        } catch (e: Exception) {
            ValidationResult(false, System.currentTimeMillis() - t0, "Network error: ${e.message ?: "unreachable"}")
        }
    }

    /** Anthropic splits usage across message_start / message_delta — merge per field. */
    private fun mergeUsage(current: Usage?, update: Usage): Usage = Usage(
        promptTokens = if (update.promptTokens > 0) update.promptTokens else current?.promptTokens ?: 0,
        completionTokens = if (update.completionTokens > 0) update.completionTokens else current?.completionTokens ?: 0,
        totalTokens = if (update.totalTokens > 0) update.totalTokens else current?.totalTokens ?: 0,
    )

    private fun parseUsage(el: kotlinx.serialization.json.JsonElement): Usage? {
        val o = el.jsonObjectOrNull ?: return null
        return Usage(
            promptTokens = o["input_tokens"].longOrNullCompat ?: 0,
            completionTokens = o["output_tokens"].longOrNullCompat ?: 0,
            totalTokens = (o["input_tokens"].longOrNullCompat ?: 0) + (o["output_tokens"].longOrNullCompat ?: 0),
        )
    }
}


/** v4.4: 9Router-verified GLM Coding Plan model catalog (open-sse registry glm.js, 2026). */
val GLM_CODING_PLAN_MODELS: List<ModelInfo> = listOf(
    ModelInfo("glm-5.3", "GLM 5.3"),
    ModelInfo("glm-5.3-flash", "GLM 5.3 Flash (Vision)"),
    ModelInfo("glm-5.2", "GLM 5.2"),
    ModelInfo("glm-5.1", "GLM 5.1"),
    ModelInfo("glm-5-turbo", "GLM 5 Turbo"),
    ModelInfo("glm-5", "GLM 5"),
    ModelInfo("glm-4.7", "GLM 4.7"),
    ModelInfo("glm-4.6v", "GLM 4.6V (Vision)"),
)
