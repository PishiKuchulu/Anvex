package com.anvex.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavController
import com.anvex.app.ui.Routes
import com.anvex.app.i18n.tr

enum class ProjectTab { CHAT, EDITOR, PREVIEW, BUILD, HARNESS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScaffold(
    nav: NavController,
    projectId: String,
    title: String,
    currentTab: ProjectTab,
    actions: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val atelier = com.anvex.app.ui.theme.LocalThemeStyle.current ==
        com.anvex.core.android.repo.UiThemeStyle.ATELIER

    // The project top bar, extracted so the Atelier shell can stack the
    // design's mode switch directly underneath it.
    @Composable
    fun ProjectBar() {
            TopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    actions()
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Project actions")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(tr("Home")) },
                            leadingIcon = { Icon(Icons.Filled.Home, null) },
                            onClick = {
                                menuOpen = false
                                nav.navigate(Routes.HOME) {
                                    popUpTo(Routes.HOME) { inclusive = false }
                                    launchSingleTop = true
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Terminal")) },
                            leadingIcon = { Icon(Icons.Filled.Terminal, null) },
                            onClick = { menuOpen = false; nav.navigate(Routes.terminal(projectId)) },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Build APK")) },
                            leadingIcon = { Icon(Icons.Filled.Build, null) },
                            onClick = { menuOpen = false; nav.navigate(Routes.build(projectId)) },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("History")) },
                            leadingIcon = { Icon(Icons.Filled.History, null) },
                            onClick = { menuOpen = false; nav.navigate(Routes.history(projectId)) },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("GitHub sync")) },
                            leadingIcon = { Icon(Icons.Filled.Cloud, null) },
                            onClick = { menuOpen = false; nav.navigate(Routes.github(projectId)) },
                        )
                        if (atelier) {
                            // The Atelier mode switch has four modes; the
                            // harness stays one tap away in this menu.
                            DropdownMenuItem(
                                text = { Text(tr("Harness")) },
                                leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                                onClick = { menuOpen = false; nav.navigate(Routes.harness(projectId)) },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
    }

    NebulaBackground {
    Scaffold(
        // v4.4.5 (UX): explicit contentColor — contentColorFor(Transparent)
        // is Unspecified, whose text fallback is BLACK on the dark canvas.
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            if (atelier) {
                androidx.compose.foundation.layout.Column {
                    ProjectBar()
                    AtelierProjectModes(currentTab) { tab ->
                        when (tab) {
                            ProjectTab.CHAT -> nav.replaceTab(Routes.chat(projectId))
                            ProjectTab.EDITOR -> nav.replaceTab(Routes.editor(projectId))
                            ProjectTab.PREVIEW -> nav.replaceTab(Routes.preview(projectId))
                            ProjectTab.BUILD -> nav.replaceTab(Routes.build(projectId))
                            ProjectTab.HARNESS -> nav.replaceTab(Routes.harness(projectId))
                        }
                    }
                }
            } else {
                ProjectBar()
            }
        },
        bottomBar = {
            if (!atelier) {
            // v4.6 (keyboard fix): imePadding on the bottom bar makes this
            // Scaffold's content bottom padding grow by the keyboard height,
            // lifting the chat composer above the keyboard by layout
            // measurement. (Screen-level imePadding alone was not reaching
            // the composer on-device — the nested-scaffold inset chain,
            // with two consumeWindowInsets hops, swallowed it.)
            Box(Modifier.imePadding()) {
            // v4.4.5 (UX): the floating Command Deck dock replaces the
            // full-width M3 NavigationBar; project Chat is now "Agent".
            AnvexDock(
                listOf(
                    DockItem(
                        label = tr("Agent"),
                        icon = Icons.AutoMirrored.Filled.Chat,
                        selected = currentTab == ProjectTab.CHAT,
                        onClick = { nav.replaceTab(Routes.chat(projectId)) },
                    ),
                    DockItem(
                        label = tr("Editor"),
                        icon = Icons.Filled.Edit,
                        selected = currentTab == ProjectTab.EDITOR,
                        onClick = { nav.replaceTab(Routes.editor(projectId)) },
                    ),
                    DockItem(
                        label = tr("Preview"),
                        icon = Icons.Filled.PlayArrow,
                        selected = currentTab == ProjectTab.PREVIEW,
                        onClick = { nav.replaceTab(Routes.preview(projectId)) },
                    ),
                    // v4.0 (GOAL 2): the durable-agent harness workspace.
                    DockItem(
                        label = tr("Harness"),
                        icon = Icons.Filled.AutoAwesome,
                        selected = currentTab == ProjectTab.HARNESS,
                        onClick = { nav.replaceTab(Routes.harness(projectId)) },
                    ),
                    DockItem(
                        label = tr("Build"),
                        icon = Icons.Filled.Build,
                        selected = currentTab == ProjectTab.BUILD,
                        onClick = { nav.replaceTab(Routes.build(projectId)) },
                    ),
                ),
            )
            }
            }
        },
    ) { padding ->
        content(
            Modifier
                .padding(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                )
                // Tell descendants (imePadding in Chat/Terminal) that the bar area is already
                // accounted for, so the keyboard inset is not added on top of it.
                .consumeWindowInsets(padding),
        )
    }
    }
}

private fun NavController.replaceTab(route: String) {
    navigate(route) {
        launchSingleTop = true
        // v4.4.5 (UX): project tabs replace each other instead of stacking —
        // Back from any project tab must leave the project for Home,
        // not walk back through previously visited tabs.
        popUpTo(Routes.HOME)
    }
}

/**
 * Atelier project mode switch (design: گفتگو · کد · پیش‌نمایش · ساخت) — a
 * segmented pill track directly under the project header. The harness is
 * not one of the four modes; it lives in the header overflow menu.
 */
@Composable
private fun AtelierProjectModes(
    current: ProjectTab,
    onSelect: (ProjectTab) -> Unit,
) {
    val modes = listOf(
        ProjectTab.CHAT to "Chat",
        ProjectTab.EDITOR to "Code",
        ProjectTab.PREVIEW to "Preview",
        ProjectTab.BUILD to "Build",
    )
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
    ) {
        modes.forEach { (tab, label) ->
            val selected = tab == current
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.surface
                        else androidx.compose.ui.graphics.Color.Transparent,
                    )
                    .clickable { onSelect(tab) }
                    .padding(vertical = 8.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(
                    tr(label),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
