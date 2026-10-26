package com.anvex.core.android.runtime.pi

import android.content.Context
import com.anvex.core.model.ThinkingLevel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Chat-surface face of the Pi engine: runs one chat turn per call and
 * keeps Pi sessions continuous per chat. The mapping chatKey ->
 * Pi session file is persisted as JSON under filesDir/pi-chat, so a
 * conversation resumes its Pi session across turns and app restarts
 * (Pi itself stores the full transcript in that file).
 *
 * The bridge persists NO chat messages — the caller (ChatViewModel)
 * owns ChatMessage persistence, exactly like the native engine path.
 */
class PiChatBridge(
    context: Context,
    private val engine: PiEngine,
) {

    private val storeFile = File(context.filesDir, "pi-chat/sessions.json")
    private val lock = Any()

    data class TurnOutcome(
        val success: Boolean,
        val finalText: String = "",
        val error: String? = null,
        val sessionFile: String? = null,
    )

    data class Snapshot(val streamText: String, val statusLine: String?)

    suspend fun runTurn(
        chatKey: String,
        projectRoot: File,
        prompt: String,
        providerName: String?,
        modelId: String?,
        thinkingLevel: ThinkingLevel = ThinkingLevel.NORMAL,
        apiKey: String?,
        modelsJson: JSONObject?,
        approvalPrompt: PiEngine.ApprovalPrompt,
        extraExtensions: List<String> = emptyList(),
        onSnapshot: (Snapshot) -> Unit = {},
    ): TurnOutcome {
        val reducer = PiTranscriptReducer()
        val result = engine.run(
            projectRoot = projectRoot,
            config = PiEngine.RunConfig(
                providerName = providerName,
                modelId = modelId,
                thinkingLevel = thinkingLevel.toPiLevel(),
                apiKey = apiKey,
                modelsJson = modelsJson,
                sessionFile = sessionFileFor(chatKey),
                extraExtensions = extraExtensions,
                sessionId = "chat-$chatKey",
                projectId = chatKey,
            ),
            prompt = prompt,
            approvalPrompt = approvalPrompt,
            onEvent = { record ->
                reducer.onEvent(record)
                onSnapshot(Snapshot(reducer.streamText, reducer.statusLine))
            },
        )
        if (result.sessionFile != null) saveSessionFile(chatKey, result.sessionFile)
        return when {
            !result.success -> TurnOutcome(false, error = result.error ?: "Pi run failed", sessionFile = result.sessionFile)
            reducer.errorText != null -> TurnOutcome(false, finalText = reducer.finalText, error = reducer.errorText, sessionFile = result.sessionFile)
            else -> TurnOutcome(true, finalText = reducer.finalText, sessionFile = result.sessionFile)
        }
    }

    fun sessionFileFor(chatKey: String): String? = synchronized(lock) {
        readStore().optString(chatKey).ifBlank { null }
    }

    /** Drops the Pi session for a chat (e.g. conversation deleted): the next turn starts fresh. */
    fun forget(chatKey: String) {
        synchronized(lock) {
            val store = readStore()
            store.remove(chatKey)
            writeStore(store)
        }
    }

    private suspend fun saveSessionFile(chatKey: String, sessionFile: String) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val store = readStore()
                store.put(chatKey, sessionFile)
                writeStore(store)
            }
        }
    }

    private fun readStore(): JSONObject {
        if (!storeFile.isFile) return JSONObject()
        return runCatching { JSONObject(storeFile.readText()) }.getOrDefault(JSONObject())
    }

    private fun writeStore(store: JSONObject) {
        runCatching {
            storeFile.parentFile?.mkdirs()
            val tmp = File(storeFile.parentFile, "sessions.json.tmp")
            tmp.writeText(store.toString())
            tmp.renameTo(storeFile)
        }
    }
}
