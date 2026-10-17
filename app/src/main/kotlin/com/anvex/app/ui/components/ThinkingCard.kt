package com.anvex.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.anvex.app.i18n.tr

/**
 * v4.4 (architect feature 1): collapsible extended-thinking card shown above
 * assistant messages and during streaming — ported natively from the untitled.zip
 * web prototype's ThinkingCard (Brain icon, live dot, "Thought for Xs", mono body).
 *
 * Honest by design: it only ever displays reasoning the provider actually
 * streamed; the elapsed time is measured locally while [streaming] is true.
 */
@Composable
fun ThinkingCard(
    reasoning: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    if (reasoning.isBlank()) return
    var expanded by remember { mutableStateOf(streaming) }
    if (streaming) LaunchedEffect(Unit) { expanded = true }

    // v4.4.4 (smoothness): key the timer on the streaming session, NOT on the
    // per-token-growing reasoning text — otherwise the timer resets every token.
    val started = remember(streaming) { System.currentTimeMillis() }
    var elapsedSec by remember { mutableStateOf(0) }
    LaunchedEffect(streaming) {
        while (streaming) {
            elapsedSec = ((System.currentTimeMillis() - started) / 1000).toInt()
            kotlinx.coroutines.delay(1000)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = if (expanded) 8.dp else 0.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Outlined.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    if (streaming) {
                        tr("Thinking") + if (elapsedSec > 0) " · ${elapsedSec}s" else ""
                    } else {
                        tr("Thought process")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).clickable { expanded = !expanded },
                )
                if (streaming) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                    )
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) tr("Hide reasoning") else tr("Show reasoning"),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 6.dp, end = 12.dp, bottom = 8.dp)) {
                    Text(
                        tr("REASONING TRACE"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    ) {
                        Text(
                            reasoning,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            // v4.4.4 (smoothness): no inner scroll — this card lives
                            // inside the chat LazyColumn; nested scroll fights it.
                            // Long traces are capped instead.
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            maxLines = 40,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
