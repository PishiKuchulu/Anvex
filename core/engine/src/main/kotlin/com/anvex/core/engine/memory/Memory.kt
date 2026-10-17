package com.anvex.core.engine.memory

import com.anvex.core.model.MemoryCategory
import com.anvex.core.model.MemoryItem

/** What the model asked to do with memory, parsed from `<boltMemory>` tags in its reply. */
data class MemoryDirective(
    val op: Op,
    val text: String = "",
    val id: String = "",
    val category: String = MemoryCategory.GENERAL,
) {
    enum class Op { ADD, UPDATE, FORGET }
}

/**
 * Memory tag protocol (no extra API call needed):
 *
 * ```
 * <boltMemory op="add" category="preference">Prefers Persian replies</boltMemory>
 * <boltMemory op="update" id="a1b2c3">Now prefers concise answers</boltMemory>
 * <boltMemory op="forget" id="a1b2c3"/>
 * ```
 */
object MemoryTags {
    const val TAG = "<boltMemory"
    private const val CLOSE = "</boltMemory>"
    private val TAG_REGEX = Regex("<boltMemory\\b([^>]*?)(?:/>|>(.*?)</boltMemory>)", RegexOption.DOT_MATCHES_ALL)
    private val ATTR_REGEX = Regex("(\\w+)\\s*=\\s*\"([^\"]*)\"")

    fun parse(text: String): List<MemoryDirective> = TAG_REGEX.findAll(text).mapNotNull { m ->
        val attrs = ATTR_REGEX.findAll(m.groupValues[1]).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val op = when (attrs["op"]?.lowercase()) {
            "add", "save", "remember" -> MemoryDirective.Op.ADD
            "update", "replace" -> MemoryDirective.Op.UPDATE
            "forget", "delete", "remove" -> MemoryDirective.Op.FORGET
            else -> return@mapNotNull null
        }
        MemoryDirective(op, m.groupValues[2].trim(), attrs["id"].orEmpty().trim(), MemoryCategory.normalize(attrs["category"]))
    }.toList()

    /** Removes complete tags — used for the persisted assistant message. */
    fun strip(text: String): String = TAG_REGEX.replace(text, "").trim()

    /** Removes complete tags AND a still-streaming partial tag so it never flashes in the UI. */
    fun stripForDisplay(text: String): String {
        var out = TAG_REGEX.replace(text, "")
        val open = out.lastIndexOf(TAG)
        if (open >= 0 && out.indexOf(CLOSE, open) < 0) out = out.substring(0, open)
        else {
            for (k in minOf(TAG.length - 1, out.length) downTo 1) {
                if (out.endsWith(TAG.substring(0, k))) { out = out.substring(0, out.length - k); break }
            }
        }
        return out.trimEnd()
    }
}

/** Guards what may enter long-term memory. */
object MemorySafety {
    const val MAX_TEXT = 300
    private val secretLike = listOf(
        Regex("(?i)(api[_-]?key|secret|token|passw(?:or)?d|private[_-]?key)\\s*[:=]?\\s*[A-Za-z0-9_./+\\-]{10,}"),
        Regex("\\b(?:sk|pk|rk)-[A-Za-z0-9_\\-]{16,}"),
        Regex("\\b(?:ghp|gho|github_pat|xox[bpas]|AIza)[A-Za-z0-9_\\-]{16,}"),
        Regex("\\b(?:\\d[ -]?){13,19}\\b"),
    )

    fun clean(raw: String): String? {
        val t = raw.replace(Regex("\\s+"), " ").trim().take(MAX_TEXT)
        return t.takeIf { it.length >= 3 }
    }

    fun isStorable(text: String): Boolean = secretLike.none { it.containsMatchIn(text) }
}

object MemoryEngine {
    const val MAX_ITEMS = 200

    data class Outcome(
        val items: List<MemoryItem>,
        val added: Int = 0,
        val updated: Int = 0,
        val forgotten: Int = 0,
        val rejected: Int = 0,
    ) {
        val changed: Int get() = added + updated + forgotten
    }

    fun apply(
        existing: List<MemoryItem>,
        directives: List<MemoryDirective>,
        now: Long,
        newId: () -> String,
    ): Outcome {
        val items = existing.toMutableList()
        var added = 0; var updated = 0; var forgotten = 0; var rejected = 0
        for (d in directives) {
            when (d.op) {
                MemoryDirective.Op.ADD -> {
                    val text = MemorySafety.clean(d.text)
                    if (text == null || !MemorySafety.isStorable(text)) { rejected++; continue }
                    val key = normalize(text)
                    if (items.any { normalize(it.text) == key }) continue
                    items += MemoryItem(newId(), text, d.category, false, now, now)
                    added++
                }
                MemoryDirective.Op.UPDATE -> {
                    val idx = indexOf(items, d.id)
                    val text = MemorySafety.clean(d.text)
                    if (idx < 0 || text == null || !MemorySafety.isStorable(text)) { rejected++; continue }
                    items[idx] = items[idx].copy(text = text, category = d.category, updatedAt = now)
                    updated++
                }
                MemoryDirective.Op.FORGET -> {
                    val idx = indexOf(items, d.id)
                    if (idx >= 0) { items.removeAt(idx); forgotten++ } else rejected++
                }
            }
        }
        while (items.size > MAX_ITEMS) {
            val victim = items.filter { !it.pinned }.minByOrNull { it.updatedAt } ?: break
            items.remove(victim)
        }
        return Outcome(items, added, updated, forgotten, rejected)
    }

    private fun indexOf(items: List<MemoryItem>, id: String): Int {
        if (id.length < 4) return -1
        return items.indexOfFirst { it.id == id || it.id.startsWith(id) }
    }

    private fun normalize(text: String): String = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}

object MemoryPromptBuilder {
    const val MAX_CHARS = 3_500

    fun shortId(id: String): String = id.take(6)

    /** Renders memories for the system prompt; pinned first, then most recently updated. */
    fun render(items: List<MemoryItem>, maxChars: Int = MAX_CHARS): String {
        if (items.isEmpty()) return ""
        val body = StringBuilder()
        for (m in items.sortedWith(compareByDescending<MemoryItem> { it.pinned }.thenByDescending { it.updatedAt })) {
            val line = "- (${shortId(m.id)}) [${m.category}] ${m.text}\n"
            if (body.length + line.length > maxChars) break
            body.append(line)
        }
        return body.toString().trimEnd()
    }
}
