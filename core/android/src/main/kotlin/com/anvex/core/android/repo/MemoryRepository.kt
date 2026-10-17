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
 * the Room schema is frozen at v12 by the audit contract, and memory is small (<= 200 items).
 * Memory never leaves the device except inside the system prompt sent to the user's own provider.
 */
class MemoryRepository(context: Context) {
    private val file = File(context.filesDir, "memory/memories.json").apply { parentFile?.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(MemoryItem.serializer())
    private val mutex = Mutex()
    private val _items = MutableStateFlow(load())

    val items: StateFlow<List<MemoryItem>> = _items.asStateFlow()

    private fun load(): List<MemoryItem> = runCatching {
        if (file.isFile) json.decodeFromString(serializer, file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private fun persist(list: List<MemoryItem>) {
        val tmp = File(file.parentFile, "memories.json.tmp")
        tmp.writeText(json.encodeToString(serializer, list))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
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
