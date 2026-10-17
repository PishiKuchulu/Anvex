package com.anvex.app.project

import android.content.Context
import com.anvex.core.engine.ProjectFileSystem

/**
 * Phase 2.4 project knowledge: instructions and text files shared by every chat in a project.
 * Instructions live in SharedPreferences (no database migration). Files live in the project
 * workspace under knowledge/ and are injected, size-bounded, into each chat's system prompt.
 */
object ProjectKnowledge {

    const val KNOWLEDGE_DIR = "knowledge/"
    private const val PREFS = "anvex_project_knowledge"
    private const val MAX_INSTRUCTIONS_CHARS = 8_000
    private const val MAX_CONTEXT_CHARS = 12_000

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun instructions(context: Context, projectId: String): String =
        prefs(context).getString("instructions/$projectId", "").orEmpty()

    fun saveInstructions(context: Context, projectId: String, text: String) {
        prefs(context).edit().putString("instructions/$projectId", text.take(MAX_INSTRUCTIONS_CHARS)).apply()
    }

    /** Instructions plus the text of each knowledge file, bounded by [MAX_CONTEXT_CHARS]. */
    fun contextBlock(fs: ProjectFileSystem, instructions: String): String = buildString {
        if (instructions.isNotBlank()) {
            append("Project instructions:\n").append(instructions.trim()).append("\n")
        }
        var budget = MAX_CONTEXT_CHARS
        fs.walk().filter { it.startsWith(KNOWLEDGE_DIR) }.sorted().forEach { path ->
            if (budget <= 0) return@forEach
            val text = fs.readFile(path)?.take(budget) ?: return@forEach
            budget -= text.length
            append("\nProject file ${path.removePrefix(KNOWLEDGE_DIR)}:\n").append(text).append("\n")
        }
    }.trim()
}
