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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pack 02, STEP 1 (R4–R6) — cross-module finish-reason behavior at the
 * ChatSession level.
 *
 * R4 (the headline): providers report a NORMAL end of stream in their
 * own vocabularies — OpenAI "stop", Anthropic "end_turn", Gemini "STOP".
 * A fake ProviderClient completing with any of those over a complete
 * file action that merely omits its closing tags must get the Pack 01
 * clean-stop treatment: file written in full + the recovery note.
 * At HEAD, "end_turn" and "STOP" are classified as truncations and the
 * file is dropped — the user-visible defect behind "healthy Gemini /
 * Claude replies end as [Output stopped early …]".
 *
 * R5 (guard): the canonical truncation words AND their raw provider
 * spellings ("length", "max_tokens", "MAX_TOKENS") must take the
 * salvage/continuation path — file never written as complete.
 *
 * R6 (T1, carried from the Pack 01 review): the catch(e: Exception)
 * branch inspects the pending action WITHOUT a final parse. Streaming
 * parses are throttled (80 ms), so after a mid-stream provider
 * exception the parser lags the accumulated text, takeOpenAction
 * returns null, and the pending file is discarded SILENTLY. The
 * cancellation branch already has the fix (final parse first); this
 * test pins the same behavior for the exception branch.
 *
 * Fakes mirror ChatSessionCleanStopRegressionTest's (same harness).
 */
class ChatSessionFinishReasonTest {

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

    /** Streams the reply char-by-char, then completes with [reason]. */
    private class ReasonClient(
        private val fullText: String,
        private val reason: String,
    ) : ProviderClient {
        override val kind = ProviderKind.CUSTOM_OPENAI
        override fun streamChat(request: ChatRequest): Flow<StreamEvent> = flowOf(
            *fullText.map { StreamEvent.Token(it.toString()) }.toTypedArray(),
            StreamEvent.Completed(reason),
        )
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override suspend fun validateConnection() = ValidationResult(true, 0, "ok")
    }

    /** Streams the reply char-by-char, then THROWS mid-stream (no Failed event). */
    private class ThrowingClient(private val fullText: String) : ProviderClient {
        override val kind = ProviderKind.CUSTOM_OPENAI
        override fun streamChat(request: ChatRequest): Flow<StreamEvent> = flow {
            for (c in fullText) emit(StreamEvent.Token(c.toString()))
            throw RuntimeException("provider blew up mid-stream")
        }
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

    /** Polls until [condition] holds or ~10 s elapse; returns the last reading. */
    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    // ------------------------------------------------------------------ R4

    @Test
    fun `R4 every provider word for a normal end takes the clean-stop path`() {
        val reply = "Here is the file.\n" +
            "<boltArtifact id=\"a\" title=\"A\">\n" +
            "<boltAction type=\"file\" filePath=\"hello.txt\">\n" +
            "line one\n" +
            "line two\n"
        for (reason in listOf("stop", "end_turn", "STOP")) {
            val fs = memoryFs()
            val dao = FakeMessageDao()
            val session = newSession(fs, dao, ReasonClient(reply, reason))
            session.send("write it")
            assertTrue(
                "assistant must persist for reason=$reason",
                dao.assistantPersisted.await(15, TimeUnit.SECONDS),
            )
            assertEquals(
                "file must be written in full for reason=$reason",
                "line one\nline two\n",
                fs.readFile("hello.txt"),
            )
            val assistants = dao.stored.values.filter { it.role == MessageRole.ASSISTANT.name }
            assertEquals(1, assistants.size)
            assertTrue(
                "clean-stop note must be present for reason=$reason",
                assistants.single().content.contains("applied as written"),
            )
            session.close()
        }
    }

    // ------------------------------------------------------------------ R5

    @Test
    fun `R5 every provider word for truncation takes the salvage path never the clean-stop path`() {
        // Line-complete prefix is 234 chars (>= the 200-char salvage
        // floor); the trailing partial line is never part of it.
        //
        // SCOPE NOTE (verified against the source while writing this
        // test): the salvage's observable, in-scope effect is the
        // journaled save of exactly this prefix. The continuation ROUND
        // the salvage queues is a separate, pre-existing story: the
        // round loop's tail breaks when the round produced no tool
        // observations (an unterminated action never dispatches, so it
        // produces none), which ends the turn before the queued append
        // prompt is ever sent. That loop-tail behavior is outside Pack
        // 02's scope and is tracked separately; this test pins the
        // classification outcome Pack 02 owns: length-class reasons
        // salvage, they never clean-stop-apply and never drop.
        val line = "x".repeat(38) + "\n"
        val prefix = line.repeat(6)
        val body = prefix + "partial tail"
        val reply = "Writing the file.\n" +
            "<boltArtifact id=\"a\" title=\"A\">\n" +
            "<boltAction type=\"file\" filePath=\"big.txt\">\n" +
            body
        for (reason in listOf("length", "max_tokens", "MAX_TOKENS")) {
            val fs = memoryFs()
            val dao = FakeMessageDao()
            val session = newSession(fs, dao, ReasonClient(reply, reason))
            session.send("write it")
            assertTrue(
                "assistant must persist for reason=$reason",
                dao.assistantPersisted.await(15, TimeUnit.SECONDS),
            )
            val settled = awaitCondition { fs.readFile("big.txt") == prefix }
            assertTrue("salvage prefix must be saved for reason=$reason", settled)
            assertEquals(
                "only the line-complete prefix may be saved for reason=$reason",
                prefix,
                fs.readFile("big.txt"),
            )
            val assistants = dao.stored.values.filter { it.role == MessageRole.ASSISTANT.name }
            assertEquals(1, assistants.size)
            assertFalse(
                "the clean-stop note must NOT appear for reason=$reason",
                assistants.single().content.contains("applied as written"),
            )
            session.close()
        }
    }

    // ------------------------------------------------------------------ R6

    @Test
    fun `R6 provider exception mid-stream names the discarded file instead of dropping it silently`() {
        val fs = memoryFs()
        val dao = FakeMessageDao()
        // The file action's tokens have ALL arrived, but the provider
        // throws before the next throttled parse (80 ms cadence) could
        // consume them: the parser's state lags roundText, which is
        // exactly the cancellation defect's shape.
        val reply = "Working on it.\n" +
            "<boltArtifact id=\"a\" title=\"A\">\n" +
            "<boltAction type=\"file\" filePath=\"partial.txt\">\n" +
            "first line\nsecond line so far"
        val session = newSession(fs, dao, ThrowingClient(reply))
        session.send("write it")
        assertTrue(dao.assistantPersisted.await(15, TimeUnit.SECONDS))

        assertNull("a failed turn never writes the partial file", fs.readFile("partial.txt"))
        val assistants = dao.stored.values.filter { it.role == MessageRole.ASSISTANT.name }
        assertEquals(1, assistants.size)
        assertTrue(
            "the exception note must name the discarded path",
            assistants.single().content.contains("partial.txt"),
        )
        session.close()
    }
}
