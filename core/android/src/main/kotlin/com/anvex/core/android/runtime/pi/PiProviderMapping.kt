package com.anvex.core.android.runtime.pi

import com.anvex.core.ai.ProviderRuntimeConfig
import com.anvex.core.model.ProviderKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps an Anvex provider onto a Pi `models.json` provider entry.
 *
 * The API key is NEVER placed in the JSON: the entry references
 * `$ANVEX_PI_API_KEY` and the driver receives the key over the local
 * control socket, keeping it in the :node process's memory only.
 *
 * Supported wire families (Pi api ids):
 *  - OpenAI-compatible (OpenAI, Custom OpenAI, OpenRouter, DeepSeek,
 *    Mistral, Groq) -> "openai-completions"
 *  - Anthropic / Custom Anthropic -> "anthropic-messages"
 *  - Gemini -> "google-generative-ai"
 * Everything else (account-login providers, retired local servers)
 * returns null — the Pi engine reports the provider as unsupported
 * instead of guessing a protocol.
 */
object PiProviderMapping {

    const val API_KEY_ENV = "ANVEX_PI_API_KEY"

    data class PiProvider(
        val providerName: String,
        val modelId: String,
        val modelsJson: JSONObject,
    )

    fun apiFor(kind: ProviderKind): String? = when (kind) {
        ProviderKind.OPENAI, ProviderKind.CUSTOM_OPENAI, ProviderKind.OPENROUTER,
        ProviderKind.DEEPSEEK, ProviderKind.MISTRAL, ProviderKind.GROQ,
        -> "openai-completions"
        ProviderKind.ANTHROPIC, ProviderKind.CUSTOM_ANTHROPIC -> "anthropic-messages"
        ProviderKind.GEMINI -> "google-generative-ai"
        else -> null
    }

    /** Stable Pi provider name derived from the Anvex provider id. */
    fun providerName(providerId: String): String =
        "anvex-" + providerId.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "default" }

    fun forRuntime(runtime: ProviderRuntimeConfig): PiProvider? {
        val config = runtime.config
        val api = apiFor(config.kind) ?: return null
        if (config.model.isBlank()) return null
        val name = providerName(config.id)
        val model = JSONObject()
            .put("id", config.model)
            .put("name", config.model)
            .put("reasoning", false)
            .put("input", JSONArray(listOf("text")))
            .put(
                "cost",
                JSONObject().put("input", 0).put("output", 0).put("cacheRead", 0).put("cacheWrite", 0),
            )
            .put("contextWindow", 128_000)
            .put("maxTokens", config.maxTokens ?: 8_192)
        val provider = JSONObject()
            .put("baseUrl", config.baseUrl)
            .put("api", api)
            .put("apiKey", "\$$API_KEY_ENV")
            .put("models", JSONArray(listOf(model)))
        val root = JSONObject().put("providers", JSONObject().put(name, provider))
        return PiProvider(providerName = name, modelId = config.model, modelsJson = root)
    }
}
