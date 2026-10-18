package com.anvex.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.anvex.app.ui.screens.backup.BackupScreen
import com.anvex.app.ui.screens.build.BuildScreen
import com.anvex.app.ui.screens.chat.ChatScreen
import com.anvex.app.ui.screens.debug.DebugScreen
import com.anvex.app.ui.screens.developer.AgentCenterScreen
import com.anvex.app.ui.screens.developer.DeveloperCenterScreen
import com.anvex.app.ui.screens.editor.EditorScreen
import com.anvex.app.ui.screens.github.GitHubScreen
import com.anvex.app.ui.screens.harness.HarnessScreen
import com.anvex.app.ui.screens.history.HistoryScreen
import com.anvex.app.ui.screens.memory.MemoryScreen
import com.anvex.app.ui.screens.home.HomeScreen
import com.anvex.app.ui.screens.preview.PreviewScreen
import com.anvex.app.ui.screens.providers.ProviderEditScreen
import com.anvex.app.ui.screens.providers.ProvidersScreen
import com.anvex.app.ui.screens.settings.SettingsScreen
import com.anvex.app.ui.screens.terminal.TerminalScreen
import com.anvex.app.ui.screens.usage.UsageScreen
import kotlinx.coroutines.launch
import com.anvex.app.i18n.tr

object Routes {
    const val HOME = "home"
    const val MEMORY = "memory"
    const val SETTINGS = "settings"
    const val PROVIDERS = "providers"
    const val PROVIDER_EDIT = "provider/{id}?kind={kind}"
    const val DEBUG = "debug"
    const val CHAT = "chat/{projectId}"
    const val DIRECT_CHAT = "direct-chat"
    const val EDITOR = "editor/{projectId}?file={file}"
    const val PREVIEW = "preview/{projectId}"
    const val BUILD = "build/{projectId}"
    const val HISTORY = "history/{projectId}"
    const val USAGE = "usage"
    const val GITHUB = "github/{projectId}"
    const val BACKUP = "backup"
    const val DEVELOPER = "developer"
    const val EXTENSIONS = "extensions"
    const val AGENT_CENTER = "agent-center"
    const val TERMINAL = "terminal/{projectId}"
    const val HARNESS = "harness/{projectId}"

    fun providerEdit(id: String, kind: String? = null) =
        if (kind.isNullOrBlank()) "provider/$id" else "provider/$id?kind=$kind"
    fun chat(projectId: String) = "chat/$projectId"
    fun terminal(projectId: String) = "terminal/$projectId"
    fun editor(projectId: String, file: String? = null) =
        if (file == null) "editor/$projectId" else "editor/$projectId?file=${android.net.Uri.encode(file)}"
    fun preview(projectId: String) = "preview/$projectId"
    fun build(projectId: String) = "build/$projectId"
    fun history(projectId: String) = "history/$projectId"
    fun github(projectId: String) = "github/$projectId"
    fun harness(projectId: String) = "harness/$projectId"
}

@Composable
fun AnvexApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val topLevel = currentRoute == Routes.HOME ||
        currentRoute == Routes.DIRECT_CHAT ||
        currentRoute == Routes.AGENT_CENTER ||
        currentRoute == Routes.MEMORY ||
        currentRoute == Routes.SETTINGS

    val graph = com.anvex.core.android.AppGraph.get()
    val settings by graph.settings.settings.collectAsState(initial = null)
    val tasks by graph.reliability.observeTaskItems(20).collectAsState(initial = emptyList())
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    if (settings?.onboardingSeen == false) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(tr("Welcome to Anvex")) },
            text = {
                Text(
                    "Chat with any model you add, build projects, get real ZIP/PDF files, and let the assistant " +
                        "remember your preferences in the Memory tab. Start by adding a provider in Settings."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { scope.launch { graph.settings.setOnboardingSeen(true) } },
                ) { Text(tr("Get started")) }
            },
        )
    }

    // One NavHost owns the entire app graph. The scaffold chrome changes with the
    // destination, but the navigation tree itself is never replaced, preserving
    // back-stack and saved-state behavior across Home/Agent/Settings and project screens.
    com.anvex.app.ui.components.NebulaBackground {
    Scaffold(
        // v4.4.5 (UX): explicit contentColor — latent black-text hazard for
        // any future screen without its own Scaffold (see MemoryScreen fix).
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        bottomBar = {
            if (topLevel) {
                // v4.4.5 (UX): the floating Command Deck dock — same component
                // as the project tabs, replacing the chunky full-width bar.
                com.anvex.app.ui.components.AnvexDock(
                    listOf(
                        com.anvex.app.ui.components.DockItem(
                            label = tr("Home"),
                            icon = Icons.Filled.Home,
                            selected = currentRoute == Routes.HOME,
                            onClick = { navController.navigateTop(Routes.HOME) },
                        ),
                        com.anvex.app.ui.components.DockItem(
                            label = tr("Chat"),
                            icon = Icons.AutoMirrored.Filled.Chat,
                            selected = currentRoute == Routes.DIRECT_CHAT,
                            onClick = { navController.navigateTop(Routes.DIRECT_CHAT) },
                        ),
                        com.anvex.app.ui.components.DockItem(
                            label = tr("Agent"),
                            icon = Icons.Filled.AutoAwesome,
                            selected = currentRoute == Routes.AGENT_CENTER,
                            onClick = { navController.navigateTop(Routes.AGENT_CENTER) },
                        ),
                        com.anvex.app.ui.components.DockItem(
                            label = tr("Memory"),
                            icon = Icons.Filled.Psychology,
                            selected = currentRoute == Routes.MEMORY,
                            onClick = { navController.navigateTop(Routes.MEMORY) },
                        ),
                        com.anvex.app.ui.components.DockItem(
                            label = tr("Settings"),
                            icon = Icons.Filled.Settings,
                            selected = currentRoute == Routes.SETTINGS,
                            onClick = { navController.navigateTop(Routes.SETTINGS) },
                        ),
                    ),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            // Root-cause fix for the large empty bands at the top and bottom of every screen:
            // each screen has its own Scaffold, which applied the status/navigation-bar insets a
            // second time on top of this padding. Consuming them here makes inner Scaffolds
            // (TopAppBar, NavigationBar, imePadding) see them as already handled.
            AnvexNavHost(navController, Modifier.padding(padding).consumeWindowInsets(padding))
            FloatingAgentStatus(tasks)
        }
    }
    }
}

@Composable
private fun AnvexNavHost(navController: NavHostController, modifier: Modifier) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
        composable(Routes.HOME) { HomeScreen(navController) }
        composable(Routes.SETTINGS) { SettingsScreen(navController) }
        composable(Routes.MEMORY) { MemoryScreen(navController) }
        composable(Routes.USAGE) { UsageScreen(navController) }
        composable(Routes.BACKUP) { BackupScreen(navController) }
        composable(Routes.DEVELOPER) { DeveloperCenterScreen(navController) }
        composable(Routes.EXTENSIONS) { com.anvex.app.ui.screens.extensions.ExtensionsScreen(navController) }
        composable(Routes.AGENT_CENTER) { AgentCenterScreen(null) }
        // v4.4.6: project-less direct chat — top-level Chat tab.
        composable(Routes.DIRECT_CHAT) {
            com.anvex.app.ui.screens.chat.DirectChatScreen(navController)
        }
        composable(Routes.TERMINAL, arguments = listOf(navArgument("projectId") { type = NavType.StringType })) { entry ->
            val projectId = requireNotNull(entry.arguments?.getString("projectId")) { "Missing required navigation argument: projectId" }
            TerminalScreen(navController, projectId)
        }
        composable(Routes.GITHUB, arguments = listOf(navArgument("projectId") { type = NavType.StringType })) { entry ->
            val projectId = requireNotNull(entry.arguments?.getString("projectId")) { "Missing required navigation argument: projectId" }
            GitHubScreen(navController, projectId)
        }
        composable(Routes.PROVIDERS) { ProvidersScreen(navController) }
        composable(
            Routes.PROVIDER_EDIT,
            arguments = listOf(androidx.navigation.navArgument("kind") { defaultValue = "" }),
        ) { entry ->
            ProviderEditScreen(
                navController,
                entry.arguments?.getString("id") ?: "new",
                entry.arguments?.getString("kind").orEmpty(),
            )
        }
        composable(Routes.DEBUG) { DebugScreen(navController) }
        composable(
            Routes.CHAT,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { entry ->
            val projectId = requireNotNull(entry.arguments?.getString("projectId")) { "Missing required navigation argument: projectId" }
            ChatScreen(navController, projectId)
        }
        composable(
            Routes.EDITOR,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("file") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val projectId = requireNotNull(entry.arguments?.getString("projectId")) { "Missing required navigation argument: projectId" }
            EditorScreen(
                navController,
                projectId,
                entry.arguments?.getString("file"),
            )
        }
        composable(
            Routes.BUILD,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { entry ->
            val projectId = requireNotNull(entry.arguments?.getString("projectId")) { "Missing required navigation argument: projectId" }
            BuildScreen(navController, projectId)
        }
        composable(
            Routes.HISTORY,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { entry ->
            val projectId = requireNotNull(entry.arguments?.getString("projectId")) { "Missing required navigation argument: projectId" }
            HistoryScreen(navController, projectId)
        }
        composable(
            Routes.PREVIEW,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { entry ->
            val projectId = requireNotNull(entry.arguments?.getString("projectId")) { "Missing required navigation argument: projectId" }
            PreviewScreen(navController, projectId)
        }
        composable(
            Routes.HARNESS,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { entry ->
            val projectId = requireNotNull(entry.arguments?.getString("projectId")) { "Missing required navigation argument: projectId" }
            HarnessScreen(navController, projectId)
        }
    }
}

private fun NavHostController.navigateTop(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun BoxScope.FloatingAgentStatus(tasks: List<com.anvex.core.model.TaskRunItem>) {
    val active = tasks.count { it.state == "RUNNING" || it.state == "RETRYING" || it.state == "WAITING_APPROVAL" }
    if (active == 0) return
    Surface(
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 84.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text("Agent • $active active", style = MaterialTheme.typography.labelLarge)
        }
    }
}
