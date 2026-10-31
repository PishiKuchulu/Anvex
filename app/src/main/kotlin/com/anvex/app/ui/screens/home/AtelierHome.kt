package com.anvex.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anvex.app.i18n.tr
import com.anvex.app.ui.components.GradientButton
import com.anvex.app.ui.theme.LocalBoltExtras
import com.anvex.app.vm.HomeViewModel
import com.anvex.core.model.Project
import com.anvex.core.model.ProjectType
import com.anvex.core.model.ProviderConfig
import com.anvex.core.model.TaskRunItem

/**
 * Atelier («کارگاه») Home, per design screen 01: a big composer with a
 * Project / Quick-chat switch on top, then the running-work card, the
 * recent-projects carousel and the shortcut tiles. All actions wire to
 * the same HomeViewModel / navigation destinations as the classic Home —
 * only the presentation is Atelier's.
 */
@Composable
fun AtelierHomeBody(
    padding: PaddingValues,
    projects: List<Project>,
    providers: List<ProviderConfig>,
    tasks: List<TaskRunItem>,
    events: String?,
    onOpenProject: (String) -> Unit,
    onSendProject: (String) -> Unit,
    onSendQuickChat: (String) -> Unit,
    onAgentCenter: () -> Unit,
    onExtensions: () -> Unit,
    onBuildFirst: () -> Unit,
    onTerminal: () -> Unit,
    onGithubFirst: () -> Unit,
    onProviders: () -> Unit,
) {
    val running = tasks.firstOrNull {
        it.state == "RUNNING" || it.state == "RETRYING" || it.state == "WAITING_APPROVAL"
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { AtelierComposer(providers = providers, onProviders = onProviders, onSendProject = onSendProject, onSendQuickChat = onSendQuickChat) }

        if (providers.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    onClick = onProviders,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(tr("Connect your AI provider"), style = MaterialTheme.typography.titleSmall)
                            Text(
                                "OpenAI, Anthropic, Gemini, OpenRouter and custom endpoints.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(tr("Set up"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        if (running != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Running"), style = MaterialTheme.typography.titleMedium)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        onClick = onAgentCenter,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(52.dp),
                                    strokeWidth = 3.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(running.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    running.detail ?: running.state,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (projects.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Recent projects"), style = MaterialTheme.typography.titleMedium)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(end = 4.dp),
                    ) {
                        items(projects.take(6), key = { it.id }) { project ->
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surface,
                                onClick = { onOpenProject(project.id) },
                                modifier = Modifier.width(158.dp),
                            ) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Code,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                    Text(
                                        project.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${project.type.displayName} · ${formatRelative(project.updatedAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = com.anvex.app.ui.theme.AnvexMono,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(tr("Start your first project"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Describe an idea, then review what the AI changes before you keep it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Shortcuts"), style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ShortcutTile(Modifier.weight(1f), Icons.Filled.Build, tr("Build APK"), projects.isNotEmpty(), onBuildFirst)
                    ShortcutTile(Modifier.weight(1f), Icons.Filled.Terminal, tr("Terminal"), true, onTerminal)
                    ShortcutTile(Modifier.weight(1f), Icons.Filled.Cloud, tr("GitHub"), projects.isNotEmpty(), onGithubFirst)
                    ShortcutTile(Modifier.weight(1f), Icons.Filled.Extension, "MCP", true, onExtensions)
                }
            }
        }

        events?.let { msg ->
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        msg,
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun AtelierComposer(
    providers: List<ProviderConfig>,
    onProviders: () -> Unit,
    onSendProject: (String) -> Unit,
    onSendQuickChat: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var quick by remember { mutableStateOf(false) }
    val extras = LocalBoltExtras.current
    val model = providers.firstOrNull { it.isDefault } ?: providers.firstOrNull()
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(4.dp),
            ) {
                ComposerMode(label = tr("Project"), selected = !quick) { quick = false }
                ComposerMode(label = tr("Quick chat"), selected = quick) { quick = true }
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 2,
                maxLines = 5,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                tr("Write the app or feature you want…"),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    onClick = onProviders,
                ) {
                    Text(
                        if (model == null) {
                            tr("Choose model")
                        } else if (model.model.isBlank()) {
                            model.name
                        } else {
                            "${model.name} · ${model.model}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(44.dp)
                        .alpha(if (text.isBlank()) 0.4f else 1f)
                        .clip(CircleShape)
                        .background(extras.accentBrush)
                        .clickable(enabled = text.isNotBlank()) {
                            val value = text
                            text = ""
                            if (quick) onSendQuickChat(value) else onSendProject(value)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ArrowUpward,
                        contentDescription = tr("Send"),
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.ComposerMode(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShortcutTile(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.alpha(if (enabled) 1f else 0.45f),
    ) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Atelier new-project sheet (design screen 02): a bottom sheet instead of
 * the classic dialog — name, template chips, folder, then Import ZIP /
 * Create side by side. Everything wires to the same NewProjectUi state
 * the dialog edits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtelierNewProjectSheet(
    ui: HomeViewModel.NewProjectUi,
    onUpdate: (HomeViewModel.NewProjectUi.() -> HomeViewModel.NewProjectUi) -> Unit,
    onCreate: () -> Unit,
    onChooseFolder: () -> Unit,
    onImportZip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(tr("New project"), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = ui.name,
                onValueChange = { onUpdate { copy(name = it) } },
                label = { Text(tr("Project name")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(tr("Template"), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectType.entries.filter { it != ProjectType.UNKNOWN }.take(3).forEach { type ->
                    FilterChip(
                        selected = ui.type == type,
                        onClick = { onUpdate { copy(type = type) } },
                        label = { Text(type.displayName) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectType.entries.filter { it != ProjectType.UNKNOWN }.drop(3).forEach { type ->
                    FilterChip(
                        selected = ui.type == type,
                        onClick = { onUpdate { copy(type = type) } },
                        label = { Text(type.displayName) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = ui.parentWorkspaceUri?.let {
                        android.net.Uri.parse(it).lastPathSegment?.substringAfterLast(":") ?: it
                    } ?: "App private storage",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(tr("Project folder")) },
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                IconButton(onClick = onChooseFolder) {
                    Icon(Icons.Filled.Folder, contentDescription = "Choose project folder")
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = onImportZip, modifier = Modifier.weight(1f)) {
                    Text(tr("Import ZIP"))
                }
                GradientButton(
                    text = tr("Create project"),
                    icon = Icons.Filled.AutoAwesome,
                    onClick = onCreate,
                    enabled = ui.name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
