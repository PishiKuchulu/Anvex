package com.anvex.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v4.4.5 (UX): the Command Deck dock — a floating, compact bottom bar that
 * replaces the chunky full-width Material3 NavigationBar. One shared component
 * drives both the main tabs and the project tabs so the whole app speaks the
 * same visual language. Selection is a dot + accent tint, not a pill.
 */
data class DockItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)

@Composable
fun AnvexDock(items: List<DockItem>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // v4.4.6: respect the system navigation bar — the old M3
            // NavigationBar did this internally; a custom dock must do it
            // explicitly or it lands on top of the nav buttons.
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { item ->
                    val accent = MaterialTheme.colorScheme.primary
                    val dim = MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .alpha(if (item.enabled) 1f else 0.45f)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(enabled = item.enabled, onClick = item.onClick)
                            .padding(vertical = 2.dp),
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = item.label,
                            modifier = Modifier.size(22.dp),
                            tint = if (item.selected) accent else dim,
                        )
                        Text(
                            item.label,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (item.selected) accent else dim,
                        )
                        // Selection marker: a 4dp dot instead of the M3 pill.
                        Box(
                            modifier = Modifier
                                .padding(top = 3.dp)
                                .size(4.dp)
                                .background(
                                    if (item.selected) accent else Color.Transparent,
                                    CircleShape,
                                ),
                        )
                    }
                }
            }
        }
    }
}
