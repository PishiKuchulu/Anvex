package com.anvex.core.android.session

import com.anvex.core.ai.ChatRequest
import com.anvex.core.ai.ProviderClient
import com.anvex.core.ai.StreamEvent
import com.anvex.core.ai.ValidationResult
import com.anvex.core.android.db.MutationDao
import com.anvex.core.android.db.MutationEntity
import com.anvex.core.android.repo.ChatRepository
import com.anvex.core.android.repo.MutationRepository
import com.anvex.core.engine.ProjectFileSystem
import com.anvex.core.model.MessageRole
import com.anvex.core.model.ModelInfo
import com.anvex.core.model.ProviderConfig
import com.anvex.core.model.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pack 02-AMEND (A1, A2, A4) — the two silent-failure regressions the
 * original Pack 02 brief under-specified, plus R7's user-visible half.
 *
 * A1: a content_filter completion with NO open action used to persist
 * as a normal, successful, unexplained message (error = null, no
 * note) — the provider's block was invisible to the user.
 *
 * A2: an ABSENT finish reason (Completed(null)) used to take the
 * clean-stop path, so a half-written file whose stream simply ended
 * (gateway/proxy closing cleanly, no final frame) was applied AS IF
 * COMPLETE. Absence of a reason is not a clean finish: the pending
 * action must be dropped with a note, and must not be treated as a
 * length cut either. The same holds for an unrecognised reason.
 *
 * A4: a thinking-only reply (reasoning delivered, zero answer tokens,
 * "stop") must not leave the user with a blank message and no
 * explanation.
 *
 * Fakes mirror ChatSessionFinishReasonTest's (same harness).
 */
class ChatSessionFinishReasonAmendTest {

    private class FakeMessageDao : com.anvex.core.android.db.MessageDao {
        val stored = LinkedHashMap<String, com.anvex.core.android.db.MessageEntity>()
        val assistantPersisted = CountDownLatch(1)

        override fun observeForProject(projectId: String): Flow<List<com.anvex.core.android.db.MessageEntity>> = emptyFlow()
        override suspend fun lastForProject(projectId: String) = stored.values.lastOrNull()
        override suspend fun allMessages(): List<com.anvex.core.android.db.MessageEntity> = stored.values.toList()
        override suspend fun allAssistantUsage(): List<com.anvex.core.android.db.MessageEntity> = emptyList()
        override suspend fun delete(id: String) { stored.remove(id) }
        override suspend fun upsert(message: com.anvex.core.android.db.MessageEntity) {
            stored[message.id] = message
            if (message.role == MessageRole.ASSISTANT.name) assistantPersisted.countDown()
        }
        override suspend fun deleteForProject(projectId: String) {}
        override suspend fun deleteAll() {}
        override suspend fun projectIdsMatching(query: String): List<String> = emptyList()
        override suspend fun directConversationIds(): List<String> = emptyList()
        override suspend fun firstForProject(projectId: String) = stored.values.firstOrNull()
        override suspend fun countForProject(projectId: String): Int = 0
        override suspend fun lastMessageTime(projectId: String): Long? = null
        override fun observeRecentForProject(projectId: String, limit: Int): Flow<List<com.anvex.core.android.db.MessageEntity>> = emptyFlow()
        override suspend fun recentForProject(projectId: String, limit: Int): List<com.anvex.core.android.db.MessageEntity> =
            stored.values.filter { it.projectId == projectId }
                .sortedWith(compareBy({ it.createdAt }, { it.id })).takeLast(limit)
        override suspend fun olderForProject(projectId: String, beforeCreatedAt: Long, beforeId: String, limit: Int): List<com.anvex.core.android.db.MessageEntity> =
            stored.values.filter {
                it.projectId == projectId &&
                    (it.createdAt < beforeCreatedAt || (it.createdAt == beforeCreatedAt && it.id < beforeId))
            }.sortedWith(compareByDescending<com.anvex.core.android.db.MessageEntity> { it.createdAt }.thenByDescending { it.id }).take(limit)
        override suspend fun directConversationSummaries(): List<com.anvex.core.android.db.DirectConversationRow> = emptyList()
        override suspend fun byIds(ids: List<String>): List<com.anvex.core.android.db.MessageEntity> = ids.mapNotNull { stored[it] }
        override suspend fun usageRows(): List<com.anvex.core.android.db.UsageRow> = emptyList()
    }

    private class FakeMutationDao : MutationDao {
        val stored = ArrayList<MutationEntity>()
        override suspend fun forProject(projectId: String, limit: Int) = stored.takeLast(limit)
        override fun observeForProject(projectId: String, limit: Int): Flow<List<MutationEntity>> = emptyFlow()
        override fun observeRecent(projectId: String, limit: Int): Flow<List<MutationEntity>> = emptyFlow()
        override suspend fun insertAll(mutations: List<MutationEntity>) { stored.addAll(mutations) }
        override suspend fun trim(projectId: String, keep: Int) {}
        override suspend fun deleteForProject(projectId: String) {}
    }

    /** Streams the reply char-by-char, then completes with [reason] (null = provider reported none). */
    private class ReasonClient(
        private val fullText: String,
        private val reason: String?,
    ) : ProviderClient {
        override val kind = ProviderKind.CUSTOM_OPENAI
        override fun streamChat(request: ChatRequest): Flow<StreamEvent> = flowOf(
            *fullText.map { StreamEvent.Token(it.toString()) }.toTypedArray(),
            StreamEvent.Completed(reason),
        )
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override suspend fun validateConnection() = ValidationResult(true, 0, "ok")
    }

    /** Emits reasoning only — no answer tokens — then completes "stop". */
    private class ReasoningOnlyClient : ProviderClient {
        override val kind = ProviderKind.GEMINI
        override fun streamChat(request: ChatRequest): Flow<StreamEvent> = flowOf(
            StreamEvent.Reasoning("let me think about this"),
            StreamEvent.Reasoning(" and reason a bit more"),
            StreamEvent.Completed("stop"),
        )
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override suspend fun validateConnection() = ValidationResult(true, 0, "ok")
    }

    private fun memoryFs(): ProjectFileSystem = object : ProjectFileSystem {
        private val files = LinkedHashMap<String, String>()
        override fun mkdirs(relativePath: String) {}
        override fun exists(relativePath: String) = files.containsKey(relativePath)
        override fun isDirectory(relativePath: String) = false
        override fun writeFile(relativePath: String, content: String) { files[relativePath] = content }
        override fun writeFileBytes(relativePath: String, bytes: ByteArray) { files[relativePath] = String(bytes) }
        override fun readFile(relativePath: String) = files[relativePath]
        override fun readFileBytes(relativePath: String) = files[relativePath]?.toByteArray()
        override fun appendFile(relativePath: String, content: String) { files[relativePath] = (files[relativePath] ?: "") + content }
        override fun delete(relativePath: String, recursive: Boolean) = files.remove(relativePath) != null
        override fun list(relativePath: String) = emptyList<ProjectFileSystem.FileEntry>()
        override fun move(from: String, to: String): Boolean {
            val c = files.remove(from) ?: return false; files[to] = c; return true
        }
        override fun copy(from: String, to: String): Boolean {
            val c = files[from] ?: return false; files[to] = c; return true
        }
        override fun walk() = files.keys.toList()
    }

    private fun newSession(fs: ProjectFileSystem, dao: FakeMessageDao, client: ProviderClient): ChatSession =
        ChatSession(
            projectId = "p1",
            fs = fs,
            chatRepo = ChatRepository(dao),
            mutationRepo = MutationRepository(FakeMutationDao()),
            provider = ProviderConfig(id = "prov", name = "Test", kind = ProviderKind.CUSTOM_OPENAI, model = "m"),
            client = client,
        )

    // ------------------------------------------------------------------ A1

    @Test
    fun `A1 content_filter with no open action surfaces the block and marks the turn failed`() {
        val fs = memoryFs()
        val dao = FakeMessageDao()
        val session = newSession(fs, dao, ReasonClient("Here is the answer.", "content_filter"))
        session.send("answer me")
        assertTrue(dao.assistantPersisted.await(15, TimeUnit.SECONDS))

        val assistants = dao.stored.values.filter { it.role == MessageRole.ASSISTANT.name }
        assertEquals(1, assistants.size)
        val message = assistants.single()
        assertNotNull(
            "a blocked turn must be marked not-successful (error set)",
            message.error,
        )
        assertTrue(
            "the persisted message must explain the block to the user; was: ${message.content}",
            message.content.contains("blocked", ignoreCase = true) &&
                message.content.contains("content filter", ignoreCase = true),
        )
        session.close()
    }

    // ------------------------------------------------------------------ A2

    @Test
    fun `A2 absent finish reason drops the pending file with a note instead of applying it`() {
        val fs = memoryFs()
        val dao = FakeMessageDao()
        // Complete file body, closing tags omitted, and the stream ends
        // WITHOUT any finish reason — e.g. a gateway that closes the
        // connection cleanly without a final frame. Absence of a reason
        // is not a clean finish.
        val reply = "Here is the file.\n" +
            "<boltArtifact id=\"a\" title=\"A\">\n" +
            "<boltAction type=\"file\" filePath=\"hello.txt\">\n" +
            "line one\n" +
            "line two\n"
        val session = newSession(fs, dao, ReasonClient(reply, null))
        session.send("write it")
        assertTrue(dao.assistantPersisted.await(15, TimeUnit.SECONDS))

        assertNull(
            "a stream that ended with no finish reason must NOT apply its pending file",
            fs.readFile("hello.txt"),
        )
        val assistants = dao.stored.values.filter { it.role == MessageRole.ASSISTANT.name }
        assertEquals(1, assistants.size)
        assertTrue(
            "the discard must be named in the reply",
            assistants.single().content.contains("hello.txt"),
        )
        session.close()
    }

    @Test
    fun `A2 unrecognized finish reason drops the pending file with a note`() {
        val fs = memoryFs()
        val dao = FakeMessageDao()
        val reply = "Here is the file.\n" +
            "<boltArtifact id=\"a\" title=\"A\">\n" +
            "<boltAction type=\"file\" filePath=\"hello.txt\">\n" +
            "line one\n" +
            "line two\n"
        val session = newSession(fs, dao, ReasonClient(reply, "weird_reason"))
        session.send("write it")
        assertTrue(dao.assistantPersisted.await(15, TimeUnit.SECONDS))

        assertNull(
            "an unrecognized finish reason is not a clean finish either",
            fs.readFile("hello.txt"),
        )
        val assistants = dao.stored.values.filter { it.role == MessageRole.ASSISTANT.name }
        assertEquals(1, assistants.size)
        assertTrue(
            "the discard must be named in the reply",
            assistants.single().content.contains("hello.txt"),
        )
        session.close()
    }

    // ------------------------------------------------------------------ A4

    @Test
    fun `A4 thinking-only reply leaves an explained message and the delivered reasoning`() {
        val fs = memoryFs()
        val dao = FakeMessageDao()
        val session = newSession(fs, dao, ReasoningOnlyClient())
        session.send("think hard")
        assertTrue(dao.assistantPersisted.await(15, TimeUnit.SECONDS))

        val assistants = dao.stored.values.filter { it.role == MessageRole.ASSISTANT.name }
        assertEquals(1, assistants.size)
        val message = assistants.single()
        assertTrue(
            "a thinking-only reply must not persist a blank message",
            message.content.isNotBlank(),
        )
        assertTrue(
            "the persisted message must explain the empty answer; was: ${message.content}",
            message.content.contains("empty response", ignoreCase = true) ||
                message.content.contains("no answer", ignoreCase = true),
        )
        assertTrue(
            "the delivered reasoning must be persisted with the message",
            (message.reasoning ?: "").contains("let me think about this"),
        )
        session.close()
    }
}
