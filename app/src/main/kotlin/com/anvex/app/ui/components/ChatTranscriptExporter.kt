package com.anvex.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.anvex.core.model.ChatMessage
import com.anvex.core.model.MessageRole
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * v4.4.6: export a conversation as a Markdown file and share it.
 * Used by both the project Agent chat and the project-less direct Chat tab.
 */
object ChatTranscriptExporter {

    /** Renders [messages] as Markdown. Attachments are listed, not embedded. */
    fun toMarkdown(title: String, messages: List<ChatMessage>): String = buildString {
        append("# ").append(title.trim().ifBlank { "Chat" }).append("\n\n")
        append("_Exported ")
            .append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date()))
            .append("_\n\n---\n\n")
        messages.forEach { msg ->
            val who = when (msg.role) {
                MessageRole.USER -> "**User**"
                MessageRole.ASSISTANT -> "**Assistant**" + (msg.model?.let { " ($it)" } ?: "")
                MessageRole.SYSTEM -> "**System**"
            }
            append(who).append("\n\n")
            if (msg.attachments.isNotEmpty()) {
                append("_Attachments: ")
                    .append(msg.attachments.joinToString(", ") { it.name })
                    .append("_\n\n")
            }
            append(msg.content.trim()).append("\n\n---\n\n")
        }
    }

    /**
     * Writes the transcript into the app cache and returns a shareable
     * content Uri, or null when writing failed.
     */
    fun writeToCache(context: Context, title: String, messages: List<ChatMessage>): Uri? {
        if (messages.isEmpty()) return null
        return runCatching {
            val dir = File(context.cacheDir, "transcripts").apply { mkdirs() }
            val safe = title.trim().replace(Regex("[^A-Za-z0-9-_ ]"), "").trim()
                .take(40).ifBlank { "chat" }.replace(' ', '-')
            val file = File(dir, "$safe-${System.currentTimeMillis()}.md")
            file.writeText(toMarkdown(title, messages))
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    /** Fires the system share sheet for a transcript Uri. */
    fun share(context: Context, uri: Uri, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }

    /**
     * Shares a conversation, preferring the Markdown file route and falling
     * back to sharing the rendered text itself when the file route fails
     * (previously the file failure was swallowed and the share button
     * silently did nothing — the reported "share doesn't work" bug).
     */
    fun shareTranscript(context: Context, title: String, messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        val uri = writeToCache(context, title, messages)
        if (uri != null) {
            share(context, uri, title)
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, toMarkdown(title, messages))
        }
        context.startActivity(Intent.createChooser(intent, title))
    }
}
