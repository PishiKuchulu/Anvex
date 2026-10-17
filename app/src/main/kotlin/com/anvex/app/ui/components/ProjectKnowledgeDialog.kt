package com.anvex.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anvex.app.i18n.tr

/** Phase 2.4: instructions and text files shared by every chat in the project. */
@Composable
fun ProjectKnowledgeDialog(
    initial: String,
    files: List<String>,
    onSave: (String) -> Unit,
    onAddFile: () -> Unit,
    onRemoveFile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Project instructions & files")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("Instructions for every chat in this project")) },
                    minLines = 3,
                    maxLines = 8,
                )
                Spacer(Modifier.height(12.dp))
                Text("Text files (${files.size})", style = MaterialTheme.typography.labelLarge)
                files.forEach { name ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { onRemoveFile(name) }) {
                            Icon(Icons.Filled.Delete, "Remove $name")
                        }
                    }
                }
                TextButton(onClick = onAddFile) { Text(tr("Add text file")) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text); onDismiss() }) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
