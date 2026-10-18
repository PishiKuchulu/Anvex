package com.anvex.core.android.session

import com.anvex.core.ai.AiError
import com.anvex.core.ai.ChatRequest
import com.anvex.core.ai.ProviderClient
import com.anvex.core.ai.ProviderRuntimeConfig
import com.anvex.core.ai.StreamEvent
import com.anvex.core.ai.ValidationResult
import com.anvex.core.android.db.MutationDao
import com.anvex.core.android.db.MutationEntity
import com.anvex.core.android.repo.ChatRepository
import com.anvex.core.android.repo.MutationRepository
import com.anvex.core.engine.ProjectFileSystem
import com.anvex.core.model.ActionStatus
import com.anvex.core.model.ChatMessage
import com.anvex.core.model.FileMutation
import com.anvex.core.model.MessageRole
import com.anvex.core.model.ModelInfo
import com.anvex.core.model.ProviderConfig
import com.anvex.core.model.ProviderKind
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * P0-1 proof tests (v1.1 §3): stream completion != action completion.
 * The assistant message must only be persisted after EVERY queued action has
 * actually completed, with the artifact summary reflecting real outcomes.
 */
class ChatSessionTest {

    // ------------------------------------------------------------- fakes

    /** Filesystem that records write timestamps and can be slowed down. */
    private class InstrumentedFs(private val base: ProjectFileSystem, private val delayMs: Long = 0) : ProjectFileSystem {
        val writeTimestamps = LinkedHashMap<String, Long>()
        val writeCount = AtomicLong(0)
        var failedWritePaths = mutableSetOf<String>()

        override fun mkdirs(relativePath: String) = base.mkdirs(relativePath)
        override fun exists(relativePath: String) = base.exists(relativePath)
        override fun isDirectory(relativePath: String) = base.isDirectory(relativePath)
        override fun writeFile(relativePath: String, content: String) {
            if (delayMs > 0) Thread.sleep(delayMs)
            base.writeFile(relativePath, content)
            writeTimestamps[relativePath] = System.nanoTime()
            writeCount.incrementAndGet()
        }
        override fun writeFileBytes(relativePath: String, bytes: ByteArray) = base.writeFileBytes(relativePath, bytes)
        override fun readFile(relativePath: String) = base.readFile(relativePath)
        override fun readFileBytes(relativePath: String) = base.readFileBytes(relativePath)
        override fun appendFile(relativePath: String, content: String) = base.appendFile(relativePath, content)
        override fun delete(relativePath: String, recursive: Boolean) = base.delete(relativePath, recursive)
        override fun list(relativePath: String) = base.list(relativePath)
        override fun move(from: String, to: String) = base.move(from, to)
        override fun copy(from: String, to: String) = base.copy(from, to)
        override fun walk() = base.walk()
    }

    private class FakeMessageDao : com.anvex.core.android.db.MessageDao {
        val stored = LinkedHashMap<String, com.anvex.core.android.db.MessageEntity>()
        val persistTimestamps = LinkedHashMap<String, Long>()
        val assistantPersisted = CountDownLatch(1)

        override fun observeForProject(projectId: String): Flow<List<com.anvex.core.android.db.MessageEntity>> = emptyFlow()
        override suspend fun lastForProject(projectId: String) = stored.values.lastOrNull()
        override suspend fun allMessages(): List<com.anvex.core.android.db.MessageEntity> = stored.values.toList()
        override suspend fun allAssistantUsage(): List<com.anvex.core.android.db.MessageEntity> = emptyList()
        override suspend fun delete(id: String) { stored.remove(id) }
        override suspend fun upsert(message: com.anvex.core.android.db.MessageEntity) {
            stored[message.id] = message
            persistTimestamps[message.id] = System.nanoTime()
            if (message.role == MessageRole.ASSISTANT.name) assistantPersisted.countDown()
        }
        override suspend fun deleteForProject(projectId: String) {}
        override suspend fun deleteAll() {}
        override suspend fun projectIdsMatching(query: String): List<String> = emptyList()
        override suspend fun directConversationIds(): List<String> = emptyList()
        override suspend fun firstForProject(projectId: String) = stored.values.firstOrNull()
        override suspend fun countForProject(projectId: String): Int = 0
        override suspend fun lastMessageTime(projectId: String): Long? = null
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

    /** Streams a scripted response as Token events. */
    private class ScriptedClient(private val fullText: String, private val failAtEnd: AiError? = null) : ProviderClient {
        override val kind = ProviderKind.CUSTOM_OPENAI
        override fun streamChat(request: ChatRequest): Flow<StreamEvent> = flowOf(
            *fullText.map { StreamEvent.Token(it.toString()) }.toTypedArray(),
            if (failAtEnd != null) StreamEvent.Failed(failAtEnd) else StreamEvent.Completed("stop"),
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

    private val responseWithActions = """
        Let's build it.
        <boltArtifact id="demo" title="Demo">
        <boltAction type="file" filePath="index.html">
        <html>ok</html>
        </boltAction>
        <boltAction type="file" filePath="app.js">
        console.log('hi');
        </boltAction>
        <boltAction type="shell">
        npm install
        </boltAction>
        </boltArtifact>
        Done!
    """.trimIndent()

    private fun newSession(
        fs: ProjectFileSystem,
        dao: FakeMessageDao,
        mutationDao: FakeMutationDao,
        client: ProviderClient,
    ): ChatSession = ChatSession(
        projectId = "p1",
        fs = fs,
        chatRepo = ChatRepository(dao),
        mutationRepo = MutationRepository(mutationDao),
        provider = ProviderConfig(id = "prov", name = "Test", kind = ProviderKind.CUSTOM_OPENAI, model = "m"),
        client = client,
    )

    // ------------------------------------------------------------- tests

    @Test
    fun `assistant message persists only after all file actions completed`() {
        // Writes take 120ms each; two file actions => >= 240ms of action work
        // AFTER the stream itself already finished streaming tokens.
        val fs = InstrumentedFs(memoryFs(), delayMs = 120)
        val dao = FakeMessageDao()
        val session = newSession(fs, dao, FakeMutationDao(), ScriptedClient(responseWithActions))

        session.send("build it")
        assertTrue(dao.assistantPersisted.await(15, TimeUnit.SECONDS))

        // The two file writes both actually happened…
        assertEquals(2, fs.writeCount.get())
        // …and each write completed BEFORE the message was persisted
        // (the core P0-1 invariant: stream done != actions done).
        val persistTs = dao.persistTimestamps[dao.stored.values.last { it.role == "ASSISTANT" }.id]!!
        fs.writeTimestamps.values.forEach { writeTs ->
            assertTrue("write ($writeTs) must precede persistence ($persistTs)", writeTs < persistTs)
        }

        // The persisted message carries the artifact with all three actions.
        val assistant = dao.stored.values.last { it.role == "ASSISTANT" }
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val artifact = assistant.artifactJson?.let { json.decodeFromString<com.anvex.core.model.ArtifactSummary>(it) }
        assertNotNull(artifact)
        assertEquals(3, artifact!!.actions.size)
        assertTrue(artifact.actions.any { it.type == "file" && it.filePath == "index.html" && it.status == ActionStatus.COMPLETE.name })
        assertTrue(artifact.actions.any { it.type == "file" && it.filePath == "app.js" && it.status == ActionStatus.COMPLETE.name })
        // npm install is honestly PARTIAL
        assertTrue(artifact.actions.any { it.type == "shell" && it.status == ActionStatus.PARTIAL.name })
        session.close()
    }

    @Test
    fun `mutations journal is persisted with the message`() {
        val fs = InstrumentedFs(memoryFs())
        val dao = FakeMessageDao()
        val mutationDao = FakeMutationDao()
        val session = newSession(fs, dao, mutationDao, ScriptedClient(responseWithActions))

        session.send("go")
        assertTrue(dao.assistantPersisted.await(15, TimeUnit.SECONDS))
        // recordAll runs right after the message upsert — poll for it.
        val deadline = System.currentTimeMillis() + 10_000
        while (mutationDao.stored.size < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        assertEquals(2, mutationDao.stored.size) // two file mutations journaled
        assertTrue(mutationDao.stored.all { it.projectId == "p1" })
        assertTrue(mutationDao.stored.any { it.path == "index.html" && it.operation == "CREATE" })
        session.close()
    }

    @Test
    fun `single send executes each closed action exactly once`() {
        val fs = InstrumentedFs(memoryFs())
        val dao = FakeMessageDao()
        val client = ScriptedClient(responseWithActions)
        val session = newSession(fs, dao, FakeMutationDao(), client)

        session.send("go")
        assertTrue(dao.assistantPersisted.await(15, TimeUnit.SECONDS))
        assertEquals(2, fs.writeCount.get()) // exactly one execution per file action
        session.close()
    }

    @Test
    fun `duplicate action deliveries are deduplicated by the pipeline`() {
        // The real P0-1 dedup proof: feed the production ActionPipeline the SAME
        // ActionCallbackData twice plus a sibling, and count executions.
        val executed = ArrayList<String>()
        val pipeline = ActionPipeline(
            timeoutMs = 5_000,
            execute = { data -> executed.add("${data.messageId}:${data.actionId}") },
            onAborted = { _, _ -> },
            onFailed = { _, _ -> },
        )
        val duplicate = com.anvex.core.engine.ActionCallbackData(
            "m1", "art", "0", com.anvex.core.engine.BoltAction.FileAction("x", "a.txt"),
        )
        val sibling = com.anvex.core.engine.ActionCallbackData(
            "m1", "art", "1", com.anvex.core.engine.BoltAction.FileAction("y", "b.txt"),
        )
        runBlocking {
            val channel = kotlinx.coroutines.channels.Channel<com.anvex.core.engine.ActionCallbackData>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val job = launch { pipeline.consume(channel) }
            channel.trySend(duplicate)
            channel.trySend(duplicate) // exact duplicate delivery
            channel.trySend(duplicate) // …and again
            channel.trySend(sibling)
            channel.close()
            job.join()
        }
        assertEquals(listOf("m1:0", "m1:1"), executed) // duplicate ran ONCE, sibling ran
    }

    @Test
    fun `timed out action aborts while siblings still execute`() = runBlocking {
        val executed = ArrayList<String>()
        val aborted = ArrayList<String>()
        val pipeline = ActionPipeline(
            timeoutMs = 150,
            execute = { data ->
                kotlinx.coroutines.delay(if (data.actionId == "0") 600 else 10) // delay = cancellable
                executed.add(data.actionId)
            },
            onAborted = { data, note -> aborted.add("${data.actionId}|${note.contains("timed out")}") },
            onFailed = { _, _ -> },
        )
        val channel = kotlinx.coroutines.channels.Channel<com.anvex.core.engine.ActionCallbackData>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val job = launch { pipeline.consume(channel) }
        channel.trySend(com.anvex.core.engine.ActionCallbackData("m", "a", "0", com.anvex.core.engine.BoltAction.ShellAction("slow")))
        channel.trySend(com.anvex.core.engine.ActionCallbackData("m", "a", "1", com.anvex.core.engine.BoltAction.ShellAction("fast")))
        channel.close()
        job.join()

        // The slow action never completed (timed out + reported), the sibling ran.
        assertEquals(listOf("1"), executed)
        assertEquals(1, aborted.size)
        assertTrue(aborted[0].startsWith("0|true"))
    }

    @Test
    fun `thrown exception becomes a failed action and the loop continues`() {
        val executed = ArrayList<String>()
        val failed = ArrayList<String>()
        val pipeline = ActionPipeline(
            timeoutMs = 5_000,
            execute = { data ->
                if (data.actionId == "0") throw IllegalStateException("disk full")
                executed.add(data.actionId)
            },
            onAborted = { _, _ -> },
            onFailed = { data, e -> failed.add("${data.actionId}:${e.message}") },
        )
        runBlocking {
            val channel = kotlinx.coroutines.channels.Channel<com.anvex.core.engine.ActionCallbackData>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val job = launch { pipeline.consume(channel) }
            channel.trySend(com.anvex.core.engine.ActionCallbackData("m", "a", "0", com.anvex.core.engine.BoltAction.ShellAction("boom")))
            channel.trySend(com.anvex.core.engine.ActionCallbackData("m", "a", "1", com.anvex.core.engine.BoltAction.ShellAction("ok")))
            channel.close()
            job.join()
        }
        assertEquals(listOf("0:disk full"), failed)
        assertEquals(listOf("1"), executed) // sibling still ran after the failure
    }

    @Test
    fun `external cancellation propagates out of the pipeline`() = runBlocking {
        val started = java.util.concurrent.atomic.AtomicInteger(0)
        val pipeline = ActionPipeline(
            timeoutMs = 60_000,
            execute = { kotlinx.coroutines.delay(60_000) }, // hangs until cancelled
            onAborted = { _, _ -> started.incrementAndGet() }, // must NOT fire for external cancel
            onFailed = { _, _ -> },
        )
        val channel = kotlinx.coroutines.channels.Channel<com.anvex.core.engine.ActionCallbackData>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val job = launch { pipeline.consume(channel) }
        channel.trySend(com.anvex.core.engine.ActionCallbackData("m", "a", "0", com.anvex.core.engine.BoltAction.ShellAction("hang")))
        kotlinx.coroutines.delay(100) // let the action start
        job.cancel(); job.join()
        // External cancellation must NOT be misreported as a per-action timeout.
        assertEquals(0, started.get())
        assertTrue(job.isCancelled)
    }

    @Test
    fun `failure from provider surfaces as error on the persisted message`() {
        val fs = InstrumentedFs(memoryFs())
        val dao = FakeMessageDao()
        val session = newSession(
            fs, dao, FakeMutationDao(),
            ScriptedClient("partial text <boltArtifact id=\"x\" title=\"X\"><boltAction type=\"shell\">npm install</boltAction></boltArtifact>",
                failAtEnd = AiError(AiError.Kind.AUTH, "bad key")),
        )

        session.send("go")
        assertTrue(dao.assistantPersisted.await(15, TimeUnit.SECONDS))

        val assistant = dao.stored.values.last { it.role == "ASSISTANT" }
        assertNotNull(assistant.error)
        assertTrue(assistant.error!!.contains("bad key"))
        session.close()
    }

    @Test
    fun `user history is requested once without duplicating the new user turn (B-1)`() {
        val fs = InstrumentedFs(memoryFs())
        val dao = FakeMessageDao()
        val capturedRequests = ArrayList<ChatRequest>()
        val client = object : ProviderClient {
            override val kind = ProviderKind.CUSTOM_OPENAI
            override fun streamChat(request: ChatRequest): Flow<StreamEvent> {
                capturedRequests.add(request)
                return flowOf(StreamEvent.Token("hi"), StreamEvent.Completed("stop"))
            }
            override suspend fun listModels(): List<ModelInfo> = emptyList()
            override suspend fun validateConnection() = ValidationResult(true, 0, "ok")
        }
        val session = newSession(fs, dao, FakeMutationDao(), client)
        // Pre-existing history (as the repo would load it):
        val prior = listOf(
            ChatMessage("u0", "p1", MessageRole.USER, "earlier question"),
            ChatMessage("a0", "p1", MessageRole.ASSISTANT, "earlier answer"),
        )
        session.historyProvider = { prior }

        session.send("new question")
        assertTrue(dao.assistantPersisted.await(15, TimeUnit.SECONDS))

        assertEquals(1, capturedRequests.size)
        val turns = capturedRequests.first().turns
        val userTurns = turns.filter { it.role == MessageRole.USER }
        // exactly one "new question" turn (the B-1 duplicate bug is gone)
        assertEquals(1, userTurns.count { it.content.startsWith("new question") })
        assertEquals("earlier question", userTurns.first().content)
        session.close()
    }

    @Test
    fun `cancellation finalizes with a revertible partial state`() {
        val fs = InstrumentedFs(memoryFs(), delayMs = 120)
        val dao = FakeMessageDao()
        val hangingStream = object : ProviderClient {
            override val kind = ProviderKind.CUSTOM_OPENAI
            override fun streamChat(request: ChatRequest): Flow<StreamEvent> = kotlinx.coroutines.flow.flow {
                emit(StreamEvent.Token(responseWithActions.take(120)))
                kotlinx.coroutines.delay(60_000) // simulates an in-flight stream
            }
            override suspend fun listModels(): List<ModelInfo> = emptyList()
            override suspend fun validateConnection() = ValidationResult(true, 0, "ok")
        }
        val session = newSession(fs, dao, FakeMutationDao(), hangingStream)
        session.send("go")

        runBlocking {
            withTimeout(5000) {
                session.state.first { it.streaming } // stream now hangs mid-flight
            }
            Thread.sleep(150) // let some content stream
            session.cancel()
            withTimeout(5000) {
                session.state.first { !it.streaming } // cancel() flips streaming off
            }
        }

        // The NonCancellable finalize persists the partial message even under
        // cancellation — await the persistence itself, not just the UI flag.
        assertTrue("assistant message must be persisted even when canceled", dao.assistantPersisted.await(10, TimeUnit.SECONDS))
        val storedAssistant = dao.stored.values.lastOrNull { it.role == "ASSISTANT" }
        assertNotNull(storedAssistant)
        assertTrue(storedAssistant!!.error != null)
        assertTrue(storedAssistant.error!!.contains("stopped", ignoreCase = true))
        session.close()
    }

    @Test
    fun `state exposes filesRevision bumps only after actions`() {
        val fs = InstrumentedFs(memoryFs(), delayMs = 60)
        val dao = FakeMessageDao()
        val session = newSession(fs, dao, FakeMutationDao(), ScriptedClient(responseWithActions))
        session.send("go")
        assertTrue(dao.assistantPersisted.await(15, TimeUnit.SECONDS))

        assertTrue(session.state.value.filesRevision > 0)
        assertTrue(dao.stored.values.any { it.role == "ASSISTANT" })
        session.close()
    }
}
