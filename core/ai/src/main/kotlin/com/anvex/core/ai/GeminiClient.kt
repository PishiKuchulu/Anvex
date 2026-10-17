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
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Google Gemini client — `:streamGenerateContent?alt=sse` streaming and
 * `:models` listing. The API key travels as a query parameter (Google's
 * documented transport) or x-goog-api-key header.
 */
class GeminiClient(
    private val runtime: ProviderRuntimeConfig,
    private val http: OkHttpClient = defaultHttpClient(),
) : ProviderClient {

    override val kind = runtime.config.kind

    private val json = Sse.json
    private val base = runtime.config.baseUrl.trimEnd('/')

    override fun streamChat(request: ChatRequest): Flow<StreamEvent> = flow {
        val lastUserIndex = request.turns.indexOfLast { it.role == MessageRole.USER }
        val contents = buildJsonArray {
            request.turns.forEachIndexed { index, turn ->
                val isLastUser = turn.role == MessageRole.USER && index == lastUserIndex
                add(buildJsonObject {
                    put("role", if (turn.role == MessageRole.USER) "user" else "model")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", turn.content) })
                        if (isLastUser) request.attachments.forEach { a ->
                            when {
                                a.isImage && a.base64 != null -> add(buildJsonObject {
                                    put("inlineData", buildJsonObject { put("mimeType", a.mimeType); put("data", a.base64) })
                                })
                                !a.text.isNullOrBlank() -> add(buildJsonObject { put("text", "\n[Attachment: ${a.name}]\n${a.text}") })
                            }
                        }
                    })
                })
            }
        }
        val body = buildJsonObject {
            put(
                "systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", request.systemPrompt) })
                    })
                }
            )
            put("contents", contents)
            if (request.webSearch) {
                put("tools", buildJsonArray { add(buildJsonObject { put("google_search", buildJsonObject { }) }) })
            }
            put("generationConfig", buildJsonObject {
                (request.temperature ?: runtime.config.temperature)?.let { put("temperature", it) }
                (request.maxTokens ?: runtime.config.maxTokens)?.let { put("maxOutputTokens", it) }
                val modelId = runtime.config.model
                val supportsThinkingLevel = GeminiCapabilities.supportsThinkingLevel(modelId)
                val supportsThinkingBudget = GeminiCapabilities.supportsThinkingBudget(modelId)
                if (supportsThinkingLevel) {
                    val level = when (request.thinkingLevel) {
                        com.anvex.core.model.ThinkingLevel.LOW -> "low"
                        com.anvex.core.model.ThinkingLevel.NORMAL -> "medium"
                        com.anvex.core.model.ThinkingLevel.HIGH,
                        com.anvex.core.model.ThinkingLevel.XHIGH,
                        com.anvex.core.model.ThinkingLevel.MAX -> "high"
                    }
                    put("thinkingConfig", buildJsonObject { put("thinkingLevel", level) })
                } else if (supportsThinkingBudget) {
                    put("thinkingConfig", buildJsonObject { put("thinkingBudget", request.thinkingLevel.budgetTokens) })
                }
            })
        }
        val model = runtime.config.model
        val urlBuilder = base.toHttpUrl().newBuilder()
        if (!urlBuilder.build().pathSegments.any { it.equals("v1beta", ignoreCase = true) }) {
            urlBuilder.addPathSegment("v1beta")
        }
        val url = urlBuilder
            .addPathSegment("models")
            .addPathSegment("$model:streamGenerateContent")
            .addQueryParameter("alt", "sse")
            .build()
        val builder = Request.Builder()
            .url(url)
            .header("x-goog-api-key", runtime.apiKey)
            .header("Content-Type", "application/json")
        for ((k, v) in runtime.config.extraHeaders) builder.header(k, v)
        val req = builder
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var usage: Usage? = null
        var failure: AiError? = null
        var tokenCount = 0

        Sse.stream(http, req) { err -> failure = err }.collect { frame ->
            val obj = try { json.parseToJsonElement(frame.data).jsonObjectOrNull } catch (_: Exception) { null }
                ?: return@collect
            // Error frames: {"error": {"code":..., "message":...}}
            obj["error"]?.jsonObjectOrNull?.let { err ->
                val msg = err["message"]?.jsonPrimitiveOrNull?.contentOrNull ?: "Gemini stream error"
                failure = AiError(AiError.Kind.PROTOCOL, msg)
                return@collect
            }
            obj["promptFeedback"]?.jsonObjectOrNull?.get("blockReason")?.jsonPrimitiveOrNull?.contentOrNull?.let { reason ->
                failure = AiError(AiError.Kind.PROTOCOL, "Gemini blocked the prompt: $reason")
                return@collect
            }
            val candidate = obj["candidates"]?.jsonArrayOrNull?.firstOrNull()?.jsonObjectOrNull
            val finishReason = candidate?.get("finishReason")?.jsonPrimitiveOrNull?.contentOrNull
            if (finishReason.equals("SAFETY", ignoreCase = true) || finishReason.equals("BLOCKLIST", ignoreCase = true) || finishReason.equals("PROHIBITED_CONTENT", ignoreCase = true)) {
                failure = AiError(AiError.Kind.PROTOCOL, "Gemini blocked the response: ${finishReason}")
                return@collect
            }
            candidate
                ?.get("content")?.jsonObjectOrNull
                ?.get("parts")?.jsonArrayOrNull
                ?.forEach { part ->
                    val partObj = part.jsonObjectOrNull
                    val text = partObj?.get("text")?.jsonPrimitiveOrNull?.contentOrNull
                    if (!text.isNullOrEmpty()) {
                        // v4.4 (architect feature 1): Gemini thought summaries stream with
                        // part.thought == true — surface them as reasoning, not answer text.
                        val isThought = partObj?.get("thought")?.jsonPrimitiveOrNull?.contentOrNull == "true"
                        if (isThought) {
                            emit(StreamEvent.Reasoning(text))
                        } else {
                            tokenCount++
                            emit(StreamEvent.Token(text))
                        }
                    }
                }
            candidate?.get("groundingMetadata")?.jsonObjectOrNull?.get("groundingChunks")?.jsonArrayOrNull?.let { chunks ->
                val sources = chunks.mapNotNull { chunk ->
                    val web = chunk.jsonObjectOrNull?.get("web")?.jsonObjectOrNull ?: return@mapNotNull null
                    val url = web["uri"]?.jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
                    WebSource(web["title"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(), url)
                }
                if (sources.isNotEmpty()) emit(StreamEvent.Sources(sources))
            }
            obj["usageMetadata"]?.jsonObjectOrNull?.let { u ->
                usage = Usage(
                    promptTokens = u["promptTokenCount"].longOrNullCompat ?: 0,
                    completionTokens = u["candidatesTokenCount"].longOrNullCompat ?: 0,
                    totalTokens = u["totalTokenCount"].longOrNullCompat ?: 0,
                )
            }
        }
        val err = failure
        when {
            err != null -> emit(StreamEvent.Failed(err))
            tokenCount > 0 -> emit(StreamEvent.Completed("STOP", usage))
            else -> emit(StreamEvent.Failed(AiError(AiError.Kind.PROTOCOL, "Stream ended without content")))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(): List<ModelInfo> {
        val modelsUrlBuilder = base.toHttpUrl().newBuilder()
        if (!modelsUrlBuilder.build().pathSegments.any { it.equals("v1beta", ignoreCase = true) }) {
            modelsUrlBuilder.addPathSegment("v1beta")
        }
        val modelsUrl = modelsUrlBuilder.addPathSegment("models").addQueryParameter("pageSize", "1000").build()
        val req = Request.Builder()
            .url(modelsUrl)
            .header("x-goog-api-key", runtime.apiKey)
            .get()
            .build()
        return makeCall(http, req) { text ->
            val root = json.parseToJsonElement(text).jsonObjectOrNull
            val models = root?.get("models")?.jsonArrayOrNull ?: return@makeCall emptyList()
            models.mapNotNull { el ->
                val o = el.jsonObjectOrNull ?: return@mapNotNull null
                val name = o["name"]?.jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
                val id = name.removePrefix("models/")
                ModelInfo(
                    id = id,
                    displayName = o["displayName"]?.jsonPrimitiveOrNull?.contentOrNull ?: id,
                )
            }.filter { !it.id.contains("embedding") && !it.id.contains("aqa") }
        }
    }

    override suspend fun validateConnection(): ValidationResult {
        val t0 = System.currentTimeMillis()
        return try {
            val models = listModels()
            ValidationResult(true, System.currentTimeMillis() - t0, "Connected. ${models.size} models available.", models.size)
        } catch (e: AiHttpException) {
            ValidationResult(false, System.currentTimeMillis() - t0, Sse.mapHttpError(e.code, e.body ?: "").message)
        } catch (e: Exception) {
            ValidationResult(false, System.currentTimeMillis() - t0, "Network error: ${e.message ?: "unreachable"}")
        }
    }
}
