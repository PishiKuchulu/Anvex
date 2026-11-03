package com.anvex.core.android.repo

import android.content.Context
import com.anvex.core.engine.memory.MemoryDirective
import com.anvex.core.engine.memory.MemoryEngine
import com.anvex.core.engine.memory.MemoryPromptBuilder
import com.anvex.core.engine.memory.MemorySafety
import com.anvex.core.model.MemoryCategory
import com.anvex.core.model.MemoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Long-term memory store. Kept as one app-private JSON file (atomic replace) on purpose:
 * it stays out of the Room database entirely, and memory is small (<= 200 items).
 * Memory never leaves the device except inside the system prompt sent to the user's own provider.
 */
class MemoryRepository(context: Context) {
    private val file = File(context.filesDir, "memory/memories.json").apply { parentFile?.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(MemoryItem.serializer())
    private val mutex = Mutex()
    // Audit 02: the store used to be read and decoded eagerly in the
    // constructor — AppGraph builds this on the main thread at startup,
    // so a large memories.json blocked first frame. First access loads.
    private val _items: MutableStateFlow<List<MemoryItem>> by lazy { MutableStateFlow(load()) }

    val items: StateFlow<List<MemoryItem>> get() = _items.asStateFlow()

    private fun load(): List<MemoryItem> = runCatching {
        if (file.isFile) json.decodeFromString(serializer, file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private fun persist(list: List<MemoryItem>) {
        val text = json.encodeToString(serializer, list)
        runCatching {
            val tmp = File(file.parentFile, "memories.json.tmp")
            tmp.writeText(text)
            // Review B6: the old fallback deleted the live file and retried
            // the rename — if the retry also failed, all long-term memory
            // was gone. Rename is atomic on the same filesystem.
            // Audit 02: the rename RESULT was ignored — where rename fails,
            // every persist silently did nothing and the change was lost
            // on process death. Fall back to a direct write WITHOUT
            // deleting first (B6's hazard was the delete, not the write);
            // if even that fails, the old file stays intact and the
            // in-memory state still advances, as before.
            if (!tmp.renameTo(file)) {
                runCatching { file.writeText(text) }
                tmp.delete()
            }
        }
        _items.value = list
    }

    suspend fun promptBlock(): String = MemoryPromptBuilder.render(_items.value)

    suspend fun apply(directives: List<MemoryDirective>): MemoryEngine.Outcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            val outcome = MemoryEngine.apply(_items.value, directives, System.currentTimeMillis()) { UUID.randomUUID().toString() }
            if (outcome.changed > 0) persist(outcome.items)
            outcome
        }
    }

    /** Manual add from the Memory screen. Returns false when rejected (empty / looks like a secret). */
    suspend fun addManual(text: String, category: String = MemoryCategory.GENERAL): Boolean {
        val clean = MemorySafety.clean(text) ?: return false
        if (!MemorySafety.isStorable(clean)) return false
        return apply(listOf(MemoryDirective(MemoryDirective.Op.ADD, clean, category = category))).added > 0
    }

    suspend fun edit(id: String, text: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val clean = MemorySafety.clean(text) ?: return@withLock false
            if (!MemorySafety.isStorable(clean)) return@withLock false
            val now = System.currentTimeMillis()
            var hit = false
            val list = _items.value.map { if (it.id == id) { hit = true; it.copy(text = clean, updatedAt = now) } else it }
            if (hit) persist(list)
            hit
        }
    }

    suspend fun setPinned(id: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock { persist(_items.value.map { if (it.id == id) it.copy(pinned = pinned) else it }) }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { persist(_items.value.filterNot { it.id == id }) }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { mutex.withLock { persist(emptyList()) } }

    fun exportJson(): String = json.encodeToString(serializer, _items.value)
}
