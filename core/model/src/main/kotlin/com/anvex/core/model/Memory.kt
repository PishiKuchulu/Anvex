package com.anvex.core.model

import kotlinx.serialization.Serializable

/**
 * One long-term memory the assistant keeps about the user (preferences, profile facts,
 * recurring workflows). Memories are user-visible, editable and deletable in the Memory screen.
 */
@Serializable
data class MemoryItem(
    val id: String,
    val text: String,
    val category: String = MemoryCategory.GENERAL,
    val pinned: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    /** [SOURCE_ASSISTANT] when the model saved it, [SOURCE_USER] when typed in the Memory screen. */
    val source: String = SOURCE_ASSISTANT,
) {
    companion object {
        const val SOURCE_ASSISTANT = "assistant"
        const val SOURCE_USER = "user"
    }
}

object MemoryCategory {
    const val GENERAL = "general"
    const val PREFERENCE = "preference"
    const val PROFILE = "profile"
    const val PROJECT = "project"
    const val WORKFLOW = "workflow"

    val ALL = listOf(GENERAL, PREFERENCE, PROFILE, PROJECT, WORKFLOW)

    fun normalize(raw: String?): String {
        val v = raw?.trim()?.lowercase().orEmpty()
        return if (v in ALL) v else GENERAL
    }
}
