package com.anvex.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.anvex.app.i18n.tr

/**
 * v4.6: the long-press message menu the chat was missing — Reply, Copy,
 * Share, and Select text (a dialog with a real selection container, so a
 * partial range can be selected and copied with the system handles).
 * Shown by both the project chat and the direct chat on bubble long-press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionSheet(
    content: String,
    onReply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var selecting by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 8.dp).padding(bottom = 24.dp)) {
            MessageActionRow(Icons.AutoMirrored.Filled.Reply, tr("Reply")) {
                onReply()
            }
            MessageActionRow(Icons.Filled.ContentCopy, tr("Copy")) {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Anvex message", content))
                onDismiss()
            }
            MessageActionRow(Icons.Filled.Share, tr("Share")) {
                runCatching {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, content)
                    }
                    context.startActivity(
                        Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                onDismiss()
            }
            MessageActionRow(Icons.Filled.SelectAll, tr("Select text")) {
                selecting = true
            }
        }
    }
    if (selecting) {
        SelectMessageTextDialog(
            content = content,
            onDismiss = { selecting = false; onDismiss() },
        )
    }
}

@Composable
private fun MessageActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Full message inside a SelectionContainer: long-press/drag selects a range, system toolbar copies it. */
@Composable
private fun SelectMessageTextDialog(content: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Select text")) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SelectionContainer {
                    BidiText(content, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Anvex message", content))
                onDismiss()
            }) { Text(tr("Copy")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
        },
    )
}

/**
 * The wire format of a reply: the quoted message as a Markdown blockquote
 * above the user's text, so the model sees exactly what is being answered
 * (ChatMessage has no reply-to field; the quote IS the context).
 */
fun quotedReplyText(quoted: String, reply: String): String {
    val quote = quoted.trim().take(600)
    if (quote.isBlank()) return reply
    val block = quote.lines().joinToString("\n") { "> $it" }
    return if (reply.isBlank()) block else "$block\n\n$reply"
}
