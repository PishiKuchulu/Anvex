package com.anvex.core.android.repo

import com.anvex.core.android.db.MessageDao
import com.anvex.core.android.db.MessageEntity
import com.anvex.core.model.ArtifactSummary
import com.anvex.core.model.AttachmentMeta
import com.anvex.core.model.ChatMessage
import com.anvex.core.model.MessageRole
import com.anvex.core.model.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ChatRepository(private val dao: MessageDao) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Full-history observation. Kept for export-style consumers that need
     * every row (transcript export); UI and session consumers should use
     * [observeRecentForProject] / [historyWindow] so per-emission decoding
     * stays bounded (audit P-01).
     */
    fun observeForProject(projectId: String): Flow<List<ChatMessage>> =
        dao.observeForProject(projectId).map { list -> list.map { it.toDomain() } }

    /** Windowed UI observation: only the newest [limit] messages are decoded per emission. */
    fun observeRecentForProject(projectId: String, limit: Int = UI_WINDOW): Flow<List<ChatMessage>> =
        dao.observeRecentForProject(projectId, limit).map { list -> list.map { it.toDomain() }.asReversed() }

    /** Session history window: the newest [limit] messages, oldest-first. */
    suspend fun historyWindow(projectId: String, limit: Int = HISTORY_WINDOW): List<ChatMessage> =
        dao.recentForProject(projectId, limit).map { it.toDomain() }.asReversed()

    /** Load-older page: up to [limit] messages before the (createdAt, id) bound, oldest-first. */
    suspend fun loadOlder(projectId: String, beforeCreatedAt: Long, beforeId: String, limit: Int = UI_WINDOW): List<ChatMessage> =
        dao.olderForProject(projectId, beforeCreatedAt, beforeId, limit).map { it.toDomain() }.asReversed()

    suspend fun upsert(message: ChatMessage) {
        dao.upsert(
            MessageEntity(
                id = message.id,
                projectId = message.projectId,
                role = message.role.name,
                content = message.content,
                createdAt = message.createdAt,
                model = message.model,
                providerId = message.providerId,
                usageJson = message.usage?.let { json.encodeToString(it) },
                artifactJson = message.artifact?.let { json.encodeToString(it) },
                error = message.error,
                attachmentsJson = message.attachments.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it) },
                thinkingLevel = message.thinkingLevel,
                reasoning = message.reasoning?.takeIf { it.isNotBlank() },
            )
        )
    }

    suspend fun deleteForProject(projectId: String) = dao.deleteForProject(projectId)

    suspend fun delete(id: String) = dao.delete(id)

    /** Escapes LIKE wildcards so user input is matched literally. */
    suspend fun projectIdsMatching(query: String): List<String> {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return dao.projectIdsMatching(escaped)
    }

    suspend fun deleteAllHistory() = dao.deleteAll()

    /** v4.4.6: ids of project-less direct-chat conversations. */
    suspend fun directConversationIds(): List<String> = dao.directConversationIds()

    /** v4.4.6: first message + count for a direct-chat conversation list row. */
    suspend fun conversationSummary(projectId: String): Pair<ChatMessage?, Int> {
        val first = dao.firstForProject(projectId)?.let {
            runCatching { it.toDomain() }.getOrNull()
        }
        return first to dao.countForProject(projectId)
    }

    /** v4.4.6: newest message timestamp of a conversation (0 when empty). */
    suspend fun lastMessageTime(projectId: String): Long =
        dao.lastMessageTime(projectId) ?: 0L

    /** One row of the direct-chat conversation list, from the aggregate query. */
    data class ConversationSummary(
        val projectId: String,
        val firstMessage: ChatMessage?,
        val messageCount: Int,
        val lastTime: Long,
    )

    /**
     * All direct-chat conversation rows in TWO queries total (audit P-06):
     * one aggregate pass + one batch fetch of the first messages — replacing
     * the per-conversation summary/count/lastTime fan-out.
     */
    suspend fun directConversationSummaries(): List<ConversationSummary> {
        val rows = dao.directConversationSummaries()
        if (rows.isEmpty()) return emptyList()
        val firstIds = rows.mapNotNull { it.firstMessageId }
        val firstById = if (firstIds.isEmpty()) emptyMap() else dao.byIds(firstIds).associate { it.id to it.toDomain() }
        return rows.map { row ->
            ConversationSummary(
                projectId = row.projectId,
                firstMessage = row.firstMessageId?.let { firstById[it] },
                messageCount = row.messageCount,
                lastTime = row.lastTime ?: 0L,
            )
        }
    }

    suspend fun usageSummary(): List<ChatMessage> = dao.allAssistantUsage().map { it.toDomain() }

    /** One usage-bearing message, without its content (audit P-18). */
    data class UsageEntry(
        val createdAt: Long,
        val model: String?,
        val providerId: String?,
        val usage: Usage?,
    )

    /**
     * Bounded usage read for the Usage screen: loads only usage columns
     * (see [com.anvex.core.android.db.MessageDao.usageRows]) instead of
     * every assistant message's full content. New callers should prefer
     * this over [usageSummary].
     */
    suspend fun usageEntries(): List<UsageEntry> = dao.usageRows().map { row ->
        UsageEntry(
            createdAt = row.createdAt,
            model = row.model,
            providerId = row.providerId,
            usage = row.usageJson?.let { runCatching { json.decodeFromString<Usage>(it) }.getOrNull() },
        )
    }

    /**
     * Row → domain. A row whose stored payloads no longer parse (model-class
     * change in an upgrade, unknown role) used to VANISH from the thread
     * silently while still existing in the DB (audit 02). Now the corrupt
     * parts degrade individually — unparseable usage/artifact payloads are
     * dropped, the text survives — and only a row whose role itself is
     * unreadable becomes a visible placeholder instead of disappearing.
     */
    private fun MessageEntity.toDomain(): ChatMessage {
        val parsedRole = runCatching { MessageRole.valueOf(role) }.getOrNull()
        return ChatMessage(
            id = id,
            projectId = projectId,
            role = parsedRole ?: MessageRole.ASSISTANT,
            content = if (parsedRole == null) CORRUPT_PLACEHOLDER else content,
            createdAt = createdAt,
            model = model,
            providerId = providerId,
            usage = usageJson?.let { runCatching { json.decodeFromString<Usage>(it) }.getOrNull() },
            artifact = artifactJson?.let { runCatching { json.decodeFromString<ArtifactSummary>(it) }.getOrNull() },
            error = error,
            attachments = attachmentsJson?.let { runCatching { json.decodeFromString<List<AttachmentMeta>>(it) }.getOrDefault(emptyList()) } ?: emptyList(),
            thinkingLevel = thinkingLevel,
            reasoning = reasoning,
        )
    }

    companion object {
        /** Newest messages kept resident for chat UIs (audit P-01). */
        const val UI_WINDOW = 300

        /** Newest messages offered to a session's history provider. */
        const val HISTORY_WINDOW = 96

        const val CORRUPT_PLACEHOLDER = "This message could not be read (its stored data is damaged)."
    }
}
