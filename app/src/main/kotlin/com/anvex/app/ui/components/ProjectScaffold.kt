package com.anvex.app.ui.components

import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.unit.dp
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

    NebulaBackground {
    Scaffold(
        // v4.4.5 (UX): explicit contentColor — contentColorFor(Transparent)
        // is Unspecified, whose text fallback is BLACK on the dark canvas.
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
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
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 0.dp,
            ) {
                NavigationBarItem(
                    selected = currentTab == ProjectTab.CHAT,
                    onClick = { nav.replaceTab(Routes.chat(projectId)) },
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                    label = { Text(tr("Chat")) },
                )
                NavigationBarItem(
                    selected = currentTab == ProjectTab.EDITOR,
                    onClick = { nav.replaceTab(Routes.editor(projectId)) },
                    icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    label = { Text(tr("Editor")) },
                )
                NavigationBarItem(
                    selected = currentTab == ProjectTab.PREVIEW,
                    onClick = { nav.replaceTab(Routes.preview(projectId)) },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    label = { Text(tr("Preview")) },
                )
                // v4.0 (GOAL 2): the durable-agent harness workspace.
                NavigationBarItem(
                    selected = currentTab == ProjectTab.HARNESS,
                    onClick = { nav.replaceTab(Routes.harness(projectId)) },
                    icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                    label = { Text(tr("Harness")) },
                )
                NavigationBarItem(
                    selected = currentTab == ProjectTab.BUILD,
                    onClick = { nav.replaceTab(Routes.build(projectId)) },
                    icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                    label = { Text(tr("Build")) },
                )
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
    }
}
