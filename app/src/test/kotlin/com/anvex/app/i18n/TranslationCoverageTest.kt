package com.anvex.app.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F1 guard. `tr("English")` uses the English text itself as the key, so editing a UI string — or
 * adding one without a Persian entry — silently falls back to English and no compiler or lint
 * notices. This test reads the sources and fails the build instead.
 */
class TranslationCoverageTest {
    private val lit = """(?:[^"\\]|\\.)*"""
    private val entry = Regex("""^\s*"($lit)"\s+to\s+"($lit)",?\s*$""", RegexOption.MULTILINE)
    private val call = Regex("""\btr\(\s*"($lit)"\s*\)""")

    private fun sourceRoot(): File =
        listOf("src/main/kotlin", "app/src/main/kotlin").map(::File).first { it.isDirectory }

    private fun kotlinFiles(): List<File> = sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun faEntries(): List<Pair<String, String>> {
        val strings = kotlinFiles().first { it.name == "Strings.kt" && it.parentFile.name == "i18n" }
        return entry.findAll(strings.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList()
    }

    @Test
    fun `every static tr literal has a Persian entry`() {
        val known = faEntries().map { it.first }.toSet()
        val missing = kotlinFiles()
            .filterNot { it.parentFile.name == "i18n" }
            .flatMap { f -> call.findAll(f.readText()).map { it.groupValues[1] to f.name }.toList() }
            .filter { it.first !in known }
            .distinct()
        assertTrue(
            "Add these strings to i18n/Strings.kt (or they silently stay English in the Persian UI):\n" +
                missing.joinToString("\n") { "  \"${it.first}\"   (${it.second})" },
            missing.isEmpty(),
        )
    }

    @Test
    fun `translation keys are unique and values are not blank`() {
        val entries = faEntries()
        val dups = entries.groupBy { it.first }.filter { it.value.size > 1 }.keys
        assertTrue("Duplicate keys in FA (the last one silently wins): $dups", dups.isEmpty())
        val blank = entries.filter { it.second.isBlank() }.map { it.first }
        assertTrue("Blank Persian values: $blank", blank.isEmpty())
    }
}
