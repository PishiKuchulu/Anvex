package com.anvex.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * core:model serialization contract tests (audit F-07: this module had no
 * tests). These models persist as JSON (Room JSON columns, DataStore), so the
 * contract that matters is:
 *
 *  1. round-trips are lossless for fully-populated values;
 *  2. JSON written by OLDER app versions still decodes — every field added
 *     since must carry a default (the Usage.costUsd / AttachmentMeta
 *     .workspacePath precedent);
 *  3. persisted enum names stay stable — they are stored as strings in user
 *     databases, so a rename is a data-corruption change, not a refactor.
 *
 * The Json configuration mirrors production (ChatRepository):
 * ignoreUnknownKeys + encodeDefaults.
 */
class ModelSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `attachment meta round-trips with workspace path`() {
        val meta = AttachmentMeta(
            id = "att-1",
            uri = "content://media/external/file/42",
            name = "AUDIT_REPORT.MD",
            mimeType = "text/markdown",
            sizeBytes = 12_345L,
            kind = AttachmentKind.FILE,
            workspacePath = ".anvex/attachments/0123456789abcdef-AUDIT_REPORT.MD",
        )
        val decoded = json.decodeFromString<AttachmentMeta>(json.encodeToString(meta))
        assertEquals(meta, decoded)
    }

    @Test
    fun `legacy attachment meta without workspacePath decodes with empty path`() {
        // Exact shape written by builds before 4.15.6 (no workspacePath key).
        val legacy = """{"id":"att-0","uri":"content://x/1","name":"notes.txt",""" +
            """"mimeType":"text/plain","sizeBytes":7,"kind":"FILE"}"""
        val decoded = json.decodeFromString<AttachmentMeta>(legacy)
        assertEquals("", decoded.workspacePath)
        assertEquals("notes.txt", decoded.name)
        assertEquals(7L, decoded.sizeBytes)
    }

    @Test
    fun `chat message round-trips the full nested graph`() {
        val message = ChatMessage(
            id = "m-1",
            projectId = "p-1",
            role = MessageRole.ASSISTANT,
            content = "Done.",
            createdAt = 1_700_000_000_000L,
            model = "some-model",
            providerId = "prov-1",
            usage = Usage(promptTokens = 10, completionTokens = 20, totalTokens = 30, costUsd = 0.0042),
            artifact = ArtifactSummary(
                id = "art-1",
                title = "Edits",
                actions = listOf(
                    ActionRecord(actionId = "a-1", type = "file", filePath = "src/Main.kt", status = "COMPLETE", bytes = 128),
                    ActionRecord(actionId = "a-2", type = "shell", command = "ls", status = "FAILED", note = "denied"),
                ),
            ),
            error = null,
            attachments = listOf(
                AttachmentMeta(id = "att-1", uri = "content://x/9", name = "a.png", mimeType = "image/png", kind = AttachmentKind.IMAGE),
            ),
            thinkingLevel = "HIGH",
            reasoning = "trace",
        )
        val decoded = json.decodeFromString<ChatMessage>(json.encodeToString(message))
        assertEquals(message, decoded)
    }

    @Test
    fun `legacy chat message without reasoning fields decodes with nulls`() {
        // Shape written before v13 (no reasoning) and before thinkingLevel existed.
        val legacy = """{"id":"m-0","projectId":"p-0","role":"USER","content":"hi",""" +
            """"createdAt":1699999999000,"attachments":[]}"""
        val decoded = json.decodeFromString<ChatMessage>(legacy)
        assertNull(decoded.reasoning)
        assertNull(decoded.thinkingLevel)
        assertNull(decoded.usage)
        assertEquals(MessageRole.USER, decoded.role)
    }

    @Test
    fun `usage legacy json without costUsd decodes with null cost`() {
        val legacy = """{"promptTokens":5,"completionTokens":6,"totalTokens":11}"""
        val decoded = json.decodeFromString<Usage>(legacy)
        assertNull(decoded.costUsd)
        assertEquals(11L, decoded.totalTokens)
    }

    @Test
    fun `file mutation round-trips rename and non-revertible states`() {
        val mutation = FileMutation(
            path = "old/Name.kt",
            operation = MutationOp.RENAME,
            before = null,
            after = null,
            timestamp = 1_700_000_000_123L,
            source = MutationSource.USER_EDIT,
            renamedTo = "new/Name.kt",
            messageId = "m-9",
            revertible = false,
        )
        val decoded = json.decodeFromString<FileMutation>(json.encodeToString(mutation))
        assertEquals(mutation, decoded)
    }

    @Test
    fun `provider config and project and memory item round-trip`() {
        val provider = ProviderConfig(
            id = "prov-1",
            name = "Custom endpoint",
            kind = ProviderKind.OPENAI,
            apiKeyEnc = "enc-value",
            baseUrl = "https://example.invalid/v1",
            model = "some-model",
            extraHeaders = mapOf("X-Test" to "1"),
            temperature = 0.7,
            maxTokens = 4096,
            isDefault = true,
            createdAt = 1_700_000_000_000L,
        )
        assertEquals(provider, json.decodeFromString<ProviderConfig>(json.encodeToString(provider)))

        val project = Project(
            id = "p-1",
            name = "Demo",
            type = ProjectType.REACT,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_100_000L,
            description = "d",
            sizeBytes = 99L,
            workspaceUri = "content://tree/primary%3AProjects",
            workspaceOwnedByApp = true,
        )
        assertEquals(project, json.decodeFromString<Project>(json.encodeToString(project)))

        val memory = MemoryItem(
            id = "mem-1",
            text = "Prefers dark themes",
            pinned = true,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            source = MemoryItem.SOURCE_USER,
        )
        assertEquals(memory, json.decodeFromString<MemoryItem>(json.encodeToString(memory)))

        val model = ModelInfo(id = "m", contextWindow = 128_000L)
        assertEquals(model, json.decodeFromString<ModelInfo>(json.encodeToString(model)))
    }

    @Test
    fun `persisted enum names are stable`() {
        // These names are stored verbatim in user databases and JSON columns.
        assertEquals(listOf("USER", "ASSISTANT", "SYSTEM"), MessageRole.entries.map { it.name })
        assertTrue(ActionStatus.entries.map { it.name }.containsAll(listOf("PENDING", "RUNNING", "COMPLETE", "PARTIAL", "ABORTED", "FAILED")))
        assertEquals(listOf("CREATE", "MODIFY", "DELETE", "RENAME"), MutationOp.entries.map { it.name })
        assertEquals(listOf("FILE", "IMAGE"), AttachmentKind.entries.map { it.name })
        // Legacy provider kinds must keep decoding: OLLAMA rows exist in old installs.
        assertEquals("OLLAMA", ProviderKind.valueOf("OLLAMA").name)
    }
}
