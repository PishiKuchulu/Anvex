package com.anvex.core.android.runtime.pi

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anvex.core.android.db.AnvexDatabase
import com.anvex.core.android.reliability.CommandApprovalService
import com.anvex.core.android.reliability.DurableApprovalRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v4.10 gate: chat turns through PiChatBridge keep a CONTINUOUS Pi
 * session per chat — the scripted provider reports how many user
 * messages it can see in its transcript, so turn 2 must report one more
 * than turn 1, and a different chatKey must start fresh.
 */
@RunWith(AndroidJUnit4::class)
class PiChatBridgeTest {

    @Test(timeout = 300_000)
    fun chatTurnsResumeTheSamePiSession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AnvexDatabase::class.java).build()
        try {
            val engine = PiEngine(context, CommandApprovalService(DurableApprovalRepository(db.approvalGrantDao())))
            val bridge = PiChatBridge(context, engine)
            val base = File(context.filesDir, "pi-chat-test").apply { deleteRecursively(); mkdirs() }
            val project = File(base, "project").apply { mkdirs() }
            val extFile = copyAsset(context, "pi-test/ext/scripted-provider-v2.ts", File(base, "ext/scripted-provider-v2.ts"))
            copyAsset(context, "pi-test/vendor/event-stream.js", File(base, "vendor/event-stream.js"))

            suspend fun turn(chatKey: String, prompt: String): PiChatBridge.TurnOutcome =
                bridge.runTurn(
                    chatKey = chatKey,
                    projectRoot = project,
                    prompt = prompt,
                    providerName = "poc",
                    modelId = "poc-scripted",
                    apiKey = null,
                    modelsJson = null,
                    approvalPrompt = PiEngine.ApprovalPrompt { true },
                    extraExtensions = listOf(extFile.absolutePath),
                )

            val first = turn("chat-a", "first message")
            assertTrue("turn 1 failed: ${first.error}", first.success)
            assertEquals("history-users=1", first.finalText)

            val second = turn("chat-a", "second message")
            assertTrue("turn 2 failed: ${second.error}", second.success)
            assertEquals("the Pi session must resume with full history", "history-users=2", second.finalText)
            assertEquals("same session file across turns", first.sessionFile, second.sessionFile)

            val otherChat = turn("chat-b", "unrelated conversation")
            assertTrue("other chat failed: ${otherChat.error}", otherChat.success)
            assertEquals("a different chat starts a fresh session", "history-users=1", otherChat.finalText)
            Unit
        } finally {
            db.close()
        }
    }

    private fun copyAsset(context: android.content.Context, name: String, dest: File): File {
        dest.parentFile?.mkdirs()
        context.assets.open(name).use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }
}
