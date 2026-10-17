package com.anvex.core.engine.memory

import com.anvex.core.model.MemoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTest {
    private var counter = 0
    private fun id() = "id${counter++}abcdef"

    @Test fun parsesAddUpdateForgetTags() {
        val text = """Sure.<boltMemory op="add" category="preference">Prefers Persian replies</boltMemory>
            <boltMemory op="update" id="abc123">Concise answers</boltMemory><boltMemory op="forget" id="zzz999"/> done"""
        val d = MemoryTags.parse(text)
        assertEquals(3, d.size)
        assertEquals(MemoryDirective.Op.ADD, d[0].op)
        assertEquals("preference", d[0].category)
        assertEquals("Prefers Persian replies", d[0].text)
        assertEquals("abc123", d[1].id)
        assertEquals(MemoryDirective.Op.FORGET, d[2].op)
        assertFalse(MemoryTags.strip(text).contains("boltMemory"))
    }

    @Test fun displayStripHidesPartialTag() {
        assertEquals("Hello", MemoryTags.stripForDisplay("Hello <boltMemory op=\"add\">Pref"))
        assertEquals("Hello", MemoryTags.stripForDisplay("Hello <boltMe"))
        assertEquals("a < b", MemoryTags.stripForDisplay("a < b"))
    }

    @Test fun rejectsSecretsAndDeduplicates() {
        val d = listOf(
            MemoryDirective(MemoryDirective.Op.ADD, "My api_key = sk-abcdefghijklmnop1234"),
            MemoryDirective(MemoryDirective.Op.ADD, "Likes dark UI"),
            MemoryDirective(MemoryDirective.Op.ADD, "likes   DARK ui!"),
        )
        val out = MemoryEngine.apply(emptyList(), d, 1L, ::id)
        assertEquals(1, out.added)
        assertEquals(1, out.rejected)
        assertEquals(1, out.items.size)
    }

    @Test fun updateAndForgetByShortId() {
        val base = MemoryEngine.apply(emptyList(), listOf(MemoryDirective(MemoryDirective.Op.ADD, "Uses Kotlin")), 1L, ::id).items
        val sid = MemoryPromptBuilder.shortId(base[0].id)
        val upd = MemoryEngine.apply(base, listOf(MemoryDirective(MemoryDirective.Op.UPDATE, "Uses Kotlin and Compose", sid)), 2L, ::id)
        assertEquals("Uses Kotlin and Compose", upd.items[0].text)
        val del = MemoryEngine.apply(upd.items, listOf(MemoryDirective(MemoryDirective.Op.FORGET, id = sid)), 3L, ::id)
        assertTrue(del.items.isEmpty())
    }

    @Test fun promptRendersPinnedFirstAndRespectsBudget() {
        val items = listOf(
            MemoryItem("aaaaaa1", "old", createdAt = 1, updatedAt = 1),
            MemoryItem("bbbbbb2", "pinned one", pinned = true, createdAt = 0, updatedAt = 0),
        )
        val out = MemoryPromptBuilder.render(items)
        assertTrue(out.lines().first().contains("pinned one"))
        assertEquals("", MemoryPromptBuilder.render(emptyList()))
        assertTrue(MemoryPromptBuilder.render(items, maxChars = 10).length <= 10)
    }
}
