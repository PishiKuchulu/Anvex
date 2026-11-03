package com.anvex.app.ui.screens.git

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.anvex.app.i18n.tr
import com.anvex.app.ui.theme.AnvexDimens
import com.anvex.app.ui.theme.AnvexMono
import com.anvex.core.android.AppGraph
import com.anvex.core.android.runtime.git.ConflictSegment
import com.anvex.core.android.runtime.git.ConflictTextParser
import com.anvex.core.android.runtime.git.GitConflictStages
import com.anvex.core.android.runtime.git.GitMirror
import com.anvex.core.android.runtime.git.GitRepository
import kotlinx.coroutines.launch
import java.io.File

/**
 * Merge conflict resolver (F1, P3). Two modes over the same stages:
 *  - Marker mode: the working file carries `<<<<<<<` blocks — each block
 *    gets an explicit pick (ours / theirs / base / both orders); saving is
 *    blocked until EVERY block has a pick, so no default ever ships
 *    silently.
 *  - Stage mode: no markers (binary-ish or delete/modify) — the three
 *    stage contents are shown and the merged text is edited directly,
 *    with one-tap whole-stage choices.
 * Saving writes the file and stages it via libgit2, which clears the
 * conflict; on a mirrored (SAF) project the resolved file is also written
 * back to the project immediately.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ConflictResolverScreen(navController: NavHostController, projectId: String, file: String) {
    val graph = AppGraph.get()
    val scope = rememberCoroutineScope()

    var repo by remember { mutableStateOf<GitRepository?>(null) }
    var root by remember { mutableStateOf<GitRoot?>(null) }
    var stages by remember { mutableStateOf<GitConflictStages?>(null) }
    var workText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    var editorText by remember { mutableStateOf("") }
    val picks = remember { mutableStateMapOf<Int, ConflictTextParser.Pick>() }

    val segments = remember(workText) {
        workText?.takeIf { ConflictTextParser.hasConflicts(it) }?.let { ConflictTextParser.parse(it) }
    }
    val conflictCount = segments?.count { it is ConflictSegment.Conflict } ?: 0

    LaunchedEffect(projectId, file) {
        busy = true
        val resolved = resolveGitRoot(graph, projectId)
        root = resolved
        if (resolved != null) {
            val r = GitRepository(resolved.dir.absolutePath)
            repo = r
            stages = r.conflictStages(file).getOrNull()
            workText = runCatching { File(resolved.dir, file).readText() }.getOrNull()
            editorText = stages?.ours ?: stages?.theirs ?: workText ?: ""
        }
        busy = false
    }

    fun save(content: String) {
        val r = repo ?: return
        scope.launch {
            busy = true
            val result = r.resolveFile(file, content)
            notice = result.fold(
                onSuccess = {
                    val rt = root
                    if (rt != null && rt.mirrored) {
                        val fs = graph.androidRuntime.fileSystem(projectId)
                        if (fs != null) GitMirror.syncOutFile(fs, rt.dir, file)
                    }
                    navController.popBackStack()
                    "Resolved"
                },
                onFailure = { it.message ?: "Resolve failed" },
            )
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.substringAfterLast('/')) },
                navigationIcon = {
                    IconButton({ navController.popBackStack() }) {
                        Icon(Icons.Default.Close, contentDescription = tr("Close"))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(AnvexDimens.page),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(file, fontFamily = AnvexMono, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (segments != null) {
                // ------- marker mode -------
                var conflictIndex = -1
                segments.forEach { segment ->
                    when (segment) {
                        is ConflictSegment.Text -> {
                            if (segment.lines.any { it.isNotBlank() }) {
                                Surface(tonalElevation = 1.dp, Modifier.fillMaxWidth()) {
                                    Text(
                                        segment.lines.joinToString("\n"),
                                        Modifier.padding(10.dp),
                                        fontFamily = AnvexMono,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        is ConflictSegment.Conflict -> {
                            conflictIndex++
                            val index = conflictIndex
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("${tr("Conflict")} ${index + 1}", style = MaterialTheme.typography.titleSmall)
                                    StageBlock(tr("Ours") + " (${segment.oursLabel})", segment.ours, MaterialTheme.colorScheme.primary)
                                    if (segment.base != null) StageBlock(tr("Base"), segment.base, MaterialTheme.colorScheme.onSurfaceVariant)
                                    StageBlock(tr("Theirs") + " (${segment.theirsLabel})", segment.theirs, MaterialTheme.colorScheme.tertiary)
                                    PickRow(
                                        hasBase = segment.base != null,
                                        selected = picks[index],
                                        onPick = { picks[index] = it },
                                    )
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = { save(ConflictTextParser.render(segments, (0 until conflictCount).map { picks[it] ?: ConflictTextParser.Pick.OURS })) },
                    enabled = !busy && picks.size >= conflictCount,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("Save resolution")) }
                if (picks.size < conflictCount) {
                    Text(tr("Choose a side for every conflict to enable saving"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (!busy) {
                // ------- stage mode -------
                val st = stages
                if (st != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (st.ours != null) OutlinedButton(onClick = { editorText = st.ours }) { Text(tr("Use ours")) }
                        if (st.theirs != null) OutlinedButton(onClick = { editorText = st.theirs }) { Text(tr("Use theirs")) }
                        if (st.ancestor != null) OutlinedButton(onClick = { editorText = st.ancestor!! }) { Text(tr("Use base")) }
                    }
                }
                OutlinedTextField(
                    value = editorText,
                    onValueChange = { editorText = it.take(200_000) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                    label = { Text(tr("Merged result")) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = AnvexMono),
                )
                Button(onClick = { save(editorText) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Save resolution"))
                }
            }
        }
    }
}

@Composable
private fun StageBlock(title: String, lines: List<String>, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(title, style = MaterialTheme.typography.labelMedium, color = color)
        Surface(tonalElevation = 1.dp, Modifier.fillMaxWidth()) {
            Text(
                lines.joinToString("\n").ifBlank { "∅" },
                Modifier.padding(8.dp),
                fontFamily = AnvexMono,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PickRow(
    hasBase: Boolean,
    selected: ConflictTextParser.Pick?,
    onPick: (ConflictTextParser.Pick) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val options = buildList {
            add(ConflictTextParser.Pick.OURS to tr("Ours"))
            add(ConflictTextParser.Pick.THEIRS to tr("Theirs"))
            if (hasBase) add(ConflictTextParser.Pick.BASE to tr("Base"))
            add(ConflictTextParser.Pick.OURS_THEN_THEIRS to tr("Both (ours first)"))
            add(ConflictTextParser.Pick.THEIRS_THEN_OURS to tr("Both (theirs first)"))
        }
        options.forEach { (pick, label) ->
            FilterChip(
                selected = selected == pick,
                onClick = { onPick(pick) },
                label = { Text(label) },
            )
        }
    }
}
