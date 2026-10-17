package com.anvex.app.ui.screens.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anvex.core.model.FileNode
import com.anvex.app.i18n.tr

/**
 * Mobile file explorer (brief §20): tree with folders/files, per-node actions
 * (new file/folder, rename, delete, duplicate), flattened to visible rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerSheet(
    root: FileNode,
    activePath: String?,
    onOpenFile: (String) -> Unit,
    onCreateEntry: (dir: String, name: String, isFolder: Boolean) -> String?,
    onRename: (path: String, newName: String) -> String?,
    onDelete: (path: String) -> String?,
    onDuplicate: (path: String) -> String?,
    onDismiss: () -> Unit,
) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var newEntryDir by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var renamePath by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val flat = remember(root, expanded.toMap()) { flatten(root, expanded) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 6.dp),

                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    root.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { newEntryDir = "" to false }) {
                    Icon(Icons.Filled.NoteAdd, contentDescription = "New file")
                }
                IconButton(onClick = { newEntryDir = "" to true }) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
                }
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Text(
                "Project files",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
            LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
                items(flat, key = { it.node.path.ifBlank { "<root>" } }) { row ->
                    FileRow(
                        row = row,
                        active = row.node.path == activePath,
                        onOpen = onOpenFile,
                        onToggle = {
                            if (row.node.isDirectory) {
                                expanded[row.node.path] = !(expanded[row.node.path] ?: true)
                            }
                        },
                        onNewInDir = { isFolder -> newEntryDir = row.node.path to isFolder },
                        onRenameRequest = { renamePath = it },
                        onDelete = { error = onDelete(it) },
                        onDuplicate = { error = onDuplicate(it) },
                    )
                }
            }
        }
    }

    newEntryDir?.let { (dir, isFolder) ->
        CreateEntryDialog(
            isFolder = isFolder,
            onConfirm = { name ->
                val err = onCreateEntry(dir, name, isFolder)
                if (err == null) newEntryDir = null else error = err
            },
            onDismiss = { newEntryDir = null },
        )
    }

    renamePath?.let { path ->
        RenameEntryDialog(
            initial = path.substringAfterLast('/'),
            onConfirm = { name ->
                val err = onRename(path, name)
                if (err == null) renamePath = null else error = err
            },
            onDismiss = { renamePath = null },
        )
    }
}

private data class TreeRow(val node: FileNode, val depth: Int)

private fun flatten(root: FileNode, expanded: Map<String, Boolean>): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    fun walk(node: FileNode, depth: Int) {
        out.add(TreeRow(node, depth))
        if (node.isDirectory) {
            val isOpen = expanded[node.path] ?: true
            if (isOpen) node.children.forEach { walk(it, depth + 1) }
        }
    }
    root.children.forEach { walk(it, 0) }
    return out
}

@Composable
private fun FileRow(
    row: TreeRow,
    active: Boolean,
    onOpen: (String) -> Unit,
    onToggle: () -> Unit,
    onNewInDir: (Boolean) -> Unit,
    onRenameRequest: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDuplicate: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (row.node.isDirectory) onToggle()
                else onOpen(row.node.path)
            }
            .padding(start = (8 + row.depth * 20).dp, top = 2.dp, bottom = 2.dp),
    ) {
        if (row.node.isDirectory) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
        } else {
            Icon(
                Icons.Filled.Description,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Text(
            row.node.name,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Actions",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        if (row.node.isDirectory) {
            DropdownMenuItem(
                text = { Text(tr("New file here")) },
                onClick = { menuOpen = false; onNewInDir(false) },
            )
            DropdownMenuItem(
                text = { Text(tr("New folder here")) },
                onClick = { menuOpen = false; onNewInDir(true) },
            )
        }
        DropdownMenuItem(
            text = { Text(tr("Rename")) },
            onClick = { menuOpen = false; onRenameRequest(row.node.path) },
        )
        DropdownMenuItem(
            text = { Text(tr("Duplicate")) },
            onClick = { menuOpen = false; onDuplicate(row.node.path) },
        )
        DropdownMenuItem(
            text = { Text(tr("Delete")) },
            onClick = { menuOpen = false; onDelete(row.node.path) },
        )
    }
}

@Composable
private fun CreateEntryDialog(isFolder: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isFolder) "New folder" else "New file") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(if (isFolder) "Folder name" else "File name (e.g. App.jsx)") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(tr("Create")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun RenameEntryDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Rename")) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(tr("New name")) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(tr("Rename")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
