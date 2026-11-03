package com.anvex.core.android.runtime.pi

import android.content.Context
import com.anvex.core.model.ThinkingLevel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
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

    /**
     * Audit 02: turns for the same chat were not single-flighted — two
     * overlapping runTurn calls (double-send, retry racing a slow turn)
     * interleaved events into ONE reducer/stream and raced sessionFile
     * saves. Turns for one chatKey now serialize on a per-chat mutex.
     */
    private val turnMutexes = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** Minimum spacing between streamed snapshots (audit P-17). */
    private val snapshotMinIntervalMs = 50L

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
        turnMutexes.computeIfAbsent(chatKey) { kotlinx.coroutines.sync.Mutex() }
            .withLock { runTurnLocked(chatKey = chatKey, projectRoot = projectRoot, prompt = prompt, providerName = providerName, modelId = modelId, thinkingLevel = thinkingLevel, apiKey = apiKey, modelsJson = modelsJson, approvalPrompt = approvalPrompt, extraExtensions = extraExtensions, onSnapshot = onSnapshot) }
    }

    private suspend fun runTurnLocked(
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
        var lastSnapshotMs = 0L
        var snapshotPending = false
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
                // Audit P-17: every token delta used to materialize the
                // FULL transcript string and push a snapshot through to
                // the UI. Coalesce: at most one snapshot per interval;
                // deltas inside a window only mark a pending flush,
                // delivered once after the run ends.
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastSnapshotMs >= snapshotMinIntervalMs) {
                    lastSnapshotMs = now
                    snapshotPending = false
                    onSnapshot(Snapshot(reducer.streamText, reducer.statusLine))
                } else {
                    snapshotPending = true
                }
            },
        )
        if (snapshotPending) onSnapshot(Snapshot(reducer.streamText, reducer.statusLine))
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
        // Audit 02: only the session MAPPING was removed — the transcript
        // file itself (the full conversation text) stayed on disk forever,
        // unowned and undiscoverable, after its project was deleted.
        // Delete it too, but only when it resolves inside the app's own
        // files dir (the stored path is data, not a trusted pointer).
        val sessionPath = sessionFileFor(chatKey)
        synchronized(lock) {
            val store = readStore()
            store.remove(chatKey)
            writeStore(store)
        }
        if (sessionPath != null) {
            runCatching {
                val filesRoot = storeFile.parentFile?.parentFile?.canonicalFile
                val target = File(sessionPath).canonicalFile
                if (filesRoot != null && target.toPath().startsWith(filesRoot.toPath())) {
                    target.delete()
                }
            }
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
            // Audit 02: renameTo's result was ignored — where the rename
            // fails, session mappings silently stopped persisting (every
            // chat restarted its Pi session each launch). Fall back to a
            // direct write of the same payload.
            if (!tmp.renameTo(storeFile)) {
                runCatching { storeFile.writeText(store.toString()) }
                tmp.delete()
            }
        }
    }
}
