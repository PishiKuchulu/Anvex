package com.anvex.core.ai

/**
 * Wire capabilities for Gemini model families. Unknown models deliberately
 * receive no thinkingConfig rather than an optimistic request that may 400.
 * This keeps compatibility conservative while known preview/variant names
 * continue to work.
 */
internal object GeminiCapabilities {
    fun supportsThinkingBudget(model: String): Boolean {
        val id = normalize(model)
        return id.startsWith("gemini-2.5-") || id.startsWith("gemini-3-") || id == "gemini-3"
    }

    fun supportsThinkingLevel(model: String): Boolean {
        val id = normalize(model)
        return id.startsWith("gemini-3-") || id == "gemini-3"
    }

    private fun normalize(model: String): String = model.trim().lowercase().removePrefix("models/")
}
