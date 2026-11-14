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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pack 01-AMEND, item 1 — cross-module regression test for the parser
 * repair's flush() default flip.
 *
 * A provider reply whose file action is COMPLETE except that the final
 * </boltAction> (and </boltArtifact>) is omitted, ending with a clean
 * Completed(finishReason = "stop"), is a formatting omission, not a
 * truncation: the file must be written, exactly as it was before the
 * flush default changed to DROP. Against the post-repair HEAD this test
 * FAILS — the file is silently dropped.
 *
 * Fakes mirror ChatSessionTest's (same module, same harness contract).
 */
class ChatSessionCleanStopRegressionTest {

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

    /** Streams a scripted response as Token events, then Completed("stop"). */
    private class ScriptedClient(private val fullText: String) : ProviderClient {
        override val kind = ProviderKind.CUSTOM_OPENAI
        override fun streamChat(request: ChatRequest): Flow<StreamEvent> = flowOf(
            *fullText.map { StreamEvent.Token(it.toString()) }.toTypedArray(),
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

    @Test
    fun `clean stop with missing closing action tag still writes the file`() {
        val fs = memoryFs()
        val dao = FakeMessageDao()
        // The file action's body is complete; ONLY the closing tags are
        // missing, and the stream ends with a clean finish_reason="stop".
        val reply = "Here is the file.\n" +
            "<boltArtifact id=\"a\" title=\"A\">\n" +
            "<boltAction type=\"file\" filePath=\"hello.txt\">\n" +
            "line one\n" +
            "line two\n"
        val session = ChatSession(
            projectId = "p1",
            fs = fs,
            chatRepo = ChatRepository(dao),
            mutationRepo = MutationRepository(FakeMutationDao()),
            provider = ProviderConfig(id = "prov", name = "Test", kind = ProviderKind.CUSTOM_OPENAI, model = "m"),
            client = ScriptedClient(reply),
        )

        session.send("write it")
        assertTrue(dao.assistantPersisted.await(15, TimeUnit.SECONDS))

        assertNotNull("hello.txt must exist after a clean stop", fs.readFile("hello.txt"))
        assertEquals("line one\nline two\n", fs.readFile("hello.txt"))

        // C4: the recovery is user-visible — exactly one assistant message,
        // and it carries the note saying the pending action was applied.
        val assistants = dao.stored.values.filter { it.role == MessageRole.ASSISTANT.name }
        assertEquals(1, assistants.size)
        assertTrue(
            "persisted message must carry the recovery note",
            assistants.single().content.contains("applied as written"),
        )
        session.close()
    }

    /** Streams the scripted tokens, signals, then hangs until cancelled. */
    private class HangingClient(
        private val fullText: String,
        private val emitted: CountDownLatch,
    ) : ProviderClient {
        override val kind = ProviderKind.CUSTOM_OPENAI
        override fun streamChat(request: ChatRequest): Flow<StreamEvent> = flow {
            fullText.forEach { emit(StreamEvent.Token(it.toString())) }
            emitted.countDown()
            awaitCancellation()
        }
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override suspend fun validateConnection() = ValidationResult(true, 0, "ok")
    }

    @Test
    fun `cancellation drops the pending file action but says so`() {
        val fs = memoryFs()
        val dao = FakeMessageDao()
        val emitted = CountDownLatch(1)
        // A file action is open mid-body when the user cancels: the partial
        // file must NOT be written, and the drop must NOT be silent.
        val reply = "Working on it.\n" +
            "<boltArtifact id=\"a\" title=\"A\">\n" +
            "<boltAction type=\"file\" filePath=\"partial.txt\">\n" +
            "first line\nsecond line so far"
        val session = ChatSession(
            projectId = "p1",
            fs = fs,
            chatRepo = ChatRepository(dao),
            mutationRepo = MutationRepository(FakeMutationDao()),
            provider = ProviderConfig(id = "prov", name = "Test", kind = ProviderKind.CUSTOM_OPENAI, model = "m"),
            client = HangingClient(reply, emitted),
        )

        session.send("write it")
        assertTrue(emitted.await(15, TimeUnit.SECONDS))
        // Let the session's throttled parse consume the emitted tokens.
        Thread.sleep(750)
        session.cancel()
        assertTrue(dao.assistantPersisted.await(15, TimeUnit.SECONDS))

        assertNull("a cancelled turn never writes the partial file", fs.readFile("partial.txt"))
        // Exactly one assistant message (no duplicate stop message), and
        // its note names the discarded file's path.
        val assistants = dao.stored.values.filter { it.role == MessageRole.ASSISTANT.name }
        assertEquals(1, assistants.size)
        assertTrue(
            "the cancellation note must name the discarded path",
            assistants.single().content.contains("partial.txt"),
        )
        session.close()
    }
}
