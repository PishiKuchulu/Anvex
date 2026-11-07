package com.anvex.app.ui.screens.usage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anvex.core.android.AppGraph
import com.anvex.core.android.repo.ChatRepository
import com.anvex.app.i18n.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(nav: androidx.navigation.NavController) {
    val graph = AppGraph.get(); var entries by remember { mutableStateOf<List<ChatRepository.UsageEntry>>(emptyList()) }
    // P-18 (audit 2026-11-03): usageEntries() loads only the usage
    // columns — usageSummary() used to decode every assistant message's
    // full content just to render token counts.
    LaunchedEffect(Unit) { entries = graph.chat.usageEntries() }
    val totalPrompt = entries.sumOf { it.usage?.promptTokens ?: 0 }
    val totalCompletion = entries.sumOf { it.usage?.completionTokens ?: 0 }
    // 15.5: cost totals cover only messages whose provider had prices —
    // rows without prices contribute nothing, never a zero that pretends
    // to be free.
    val priced = entries.mapNotNull { it.usage?.costUsd }
    val totalCost = priced.sum()
    Scaffold(topBar = { TopAppBar(title = { Text(tr("AI usage")) }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back")) } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(tr("Total tokens"), style = MaterialTheme.typography.titleMedium); Text("${totalPrompt + totalCompletion}", style = MaterialTheme.typography.titleLarge); Text("${tr("Input")}: $totalPrompt • ${tr("Output")}: $totalCompletion", style = MaterialTheme.typography.bodySmall); if (priced.isNotEmpty()) Text("${tr("Estimated cost")}: $${String.format(java.util.Locale.US, "%.4f", totalCost)} (${priced.size} ${tr("priced responses")})", style = MaterialTheme.typography.bodySmall) } } }
            if (entries.isEmpty()) {
                item { Text(tr("No usage recorded yet. Token counts appear here after your first AI responses."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(entries.take(200), key = { it.createdAt }) { m -> ListItem(headlineContent = { Text(m.model ?: tr("Unknown model")) }, supportingContent = { Text("${m.providerId ?: tr("Unknown provider")} • ${m.usage?.totalTokens ?: 0} ${tr("tokens")}" + (m.usage?.costUsd?.let { " • $${String.format(java.util.Locale.US, "%.4f", it)}" } ?: "")) }) }
        }
    }
}
