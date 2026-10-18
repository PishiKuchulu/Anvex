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

    fun observeForProject(projectId: String): Flow<List<ChatMessage>> =
        dao.observeForProject(projectId).map { list -> list.mapNotNull { it.toDomain() } }

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
            runCatching { toDomain(it) }.getOrNull()
        }
        return first to dao.countForProject(projectId)
    }

    /** v4.4.6: newest message timestamp of a conversation (0 when empty). */
    suspend fun lastMessageTime(projectId: String): Long =
        dao.lastMessageTime(projectId) ?: 0L

    suspend fun usageSummary(): List<ChatMessage> = dao.allAssistantUsage().mapNotNull { it.toDomain() }

    private fun MessageEntity.toDomain(): ChatMessage? = try {
        ChatMessage(
            id = id,
            projectId = projectId,
            role = MessageRole.valueOf(role),
            content = content,
            createdAt = createdAt,
            model = model,
            providerId = providerId,
            usage = usageJson?.let { json.decodeFromString<Usage>(it) },
            artifact = artifactJson?.let { json.decodeFromString<ArtifactSummary>(it) },
            error = error,
            attachments = attachmentsJson?.let { runCatching { json.decodeFromString<List<AttachmentMeta>>(it) }.getOrDefault(emptyList()) } ?: emptyList(),
            thinkingLevel = thinkingLevel,
            reasoning = reasoning,
        )
    } catch (_: Exception) {
        null
    }
}
