package com.anvex.core.ai

import com.anvex.core.model.ModelInfo
import com.anvex.core.model.ProviderKind
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The AIProvider abstraction (brief §9): adapter architecture with
 * streamChat / listModels / validateCredentials, factory-dispatched by
 * [ProviderKind]. No provider is hard-coded anywhere in the UI.
 */
interface ProviderClient {
    val kind: ProviderKind
    fun streamChat(request: ChatRequest): Flow<StreamEvent>
    suspend fun listModels(): List<ModelInfo>
    suspend fun validateConnection(): ValidationResult
}

object ProviderClientFactory {
    fun create(runtime: ProviderRuntimeConfig, http: OkHttpClient = defaultHttpClient()): ProviderClient =
        when {
            // v4.4.1: both OpenCode tiers (free + Console/legacy-Zen) are isolated in
            // OpenCodeClient so upstream-specific behavior (model discovery path,
            // free-tier gate reporting) can never leak into the shared adapters.
            runtime.config.kind == com.anvex.core.model.ProviderKind.OPENCODE_FREE ||
                runtime.config.kind == com.anvex.core.model.ProviderKind.OPENCODE_ZEN -> OpenCodeClient(runtime, http)
            // v4.4.4: ANTIGRAVITY and CLINE were retired from the product surface.
            // Their enum constants are kept (deprecated) so legacy Room rows keep
            // decoding; legacy rows now fall through to the generic
            // OpenAI-compatible client below.
            runtime.config.kind.isGeminiProtocol -> GeminiClient(runtime, http)
            runtime.config.kind.isAnthropicProtocol -> AnthropicClient(runtime, http)
            else -> OpenAiCompatibleClient(runtime, http)
        }
}

fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(300, TimeUnit.SECONDS) // SSE streams can idle between tokens
    .writeTimeout(60, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.MINUTES) // generous upper bound for long reasoning/streaming calls
    .retryOnConnectionFailure(false) // never replay a non-idempotent AI stream
    .build()

/**
 * Mask an API key for safe display/logging: never log keys (brief §12).
 * `sk-abcdefghijk...` -> `sk-abcd••••wxyz`
 */
fun maskApiKey(key: String): String = when {
    key.length <= 10 -> "•".repeat(key.length)
    else -> key.take(6) + "•".repeat(6) + key.takeLast(4)
}
