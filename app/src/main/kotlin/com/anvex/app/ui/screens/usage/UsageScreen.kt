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
import com.anvex.core.model.ChatMessage
import com.anvex.app.i18n.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(nav: androidx.navigation.NavController) {
    val graph = AppGraph.get(); var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    LaunchedEffect(Unit) { messages = graph.chat.usageSummary() }
    val totalPrompt = messages.sumOf { it.usage?.promptTokens ?: 0 }
    val totalCompletion = messages.sumOf { it.usage?.completionTokens ?: 0 }
    Scaffold(topBar = { TopAppBar(title = { Text(tr("AI usage")) }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(tr("Total tokens"), style = MaterialTheme.typography.titleMedium); Text("${totalPrompt + totalCompletion}"); Text("Input: $totalPrompt • Output: $totalCompletion", style = MaterialTheme.typography.bodySmall) } } }
            items(messages.take(200), key = { it.id }) { m -> ListItem(headlineContent = { Text(m.model ?: "Unknown model") }, supportingContent = { Text("${m.providerId ?: "unknown provider"} • ${m.usage?.totalTokens ?: 0} tokens") }) }
        }
    }
}
