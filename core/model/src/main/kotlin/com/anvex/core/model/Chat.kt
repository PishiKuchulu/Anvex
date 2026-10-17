package com.anvex.core.model

import kotlinx.serialization.Serializable

enum class MessageRole { USER, ASSISTANT, SYSTEM }

/** Status lifecycle ported from bolt.diy ActionRunner (+ PARTIAL for tiered commands). */
enum class ActionStatus { PENDING, RUNNING, COMPLETE, PARTIAL, ABORTED, FAILED }

@Serializable
data class ChatMessage(
    val id: String,
    val projectId: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Model identifier used for assistant messages. */
    val model: String? = null,
    /** Provider id used for assistant messages. */
    val providerId: String? = null,
    /** Token usage when reported by the provider. */
    val usage: Usage? = null,
    /** Summary of the artifact carried by this message (persisted). */
    val artifact: ArtifactSummary? = null,
    val error: String? = null,
    val attachments: List<AttachmentMeta> = emptyList(),
    /** Reasoning mode selected when this assistant response was generated. */
    val thinkingLevel: String? = null,
    /** v4.4: extended-thinking / reasoning trace streamed by the model before the answer (architect feature 1). */
    val reasoning: String? = null,
)

@Serializable
data class AttachmentMeta(
    val id: String,
    val uri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long = 0L,
    val kind: AttachmentKind = AttachmentKind.FILE,
)

@Serializable
enum class AttachmentKind { FILE, IMAGE }

@Serializable
data class Usage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
)

@Serializable
data class ArtifactSummary(
    val id: String,
    val title: String,
    /** Ordered list of executed action records. */
    val actions: List<ActionRecord> = emptyList(),
)

@Serializable
data class ActionRecord(
    val actionId: String,
    val type: String,
    val filePath: String? = null,
    val command: String? = null,
    val status: String = ActionStatus.COMPLETE.name,
    /** Human-readable outcome for non-file actions (e.g. translated commands). */
    val note: String? = null,
    val bytes: Long = 0,
)

/** Which layer produced a file mutation (v1.1 §4 unified mutation transaction). */
@Serializable
enum class MutationSource { AI_FILE_ACTION, AI_SHELL_COMMAND, AI_START_COMMAND, USER_EDIT, IMPORT, TEMPLATE }

/** File mutation operations supported by the engine journal. */
@Serializable
enum class MutationOp { CREATE, MODIFY, DELETE, RENAME }

/**
 * Unified file-mutation transaction (v1.1 P0-2 fix). Every mutation path in the
 * engine records `before` = content BEFORE the mutation and `after` = content
 * AFTER the mutation, so diffs and revert always have accurate data.
 */
@Serializable
data class FileMutation(
    val path: String,
    val operation: MutationOp,
    val before: String?,
    val after: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val source: MutationSource = MutationSource.AI_FILE_ACTION,
    /** For RENAME: destination path (path = source). */
    val renamedTo: String? = null,
    val messageId: String? = null,
)
