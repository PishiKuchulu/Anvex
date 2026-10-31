package com.anvex.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Atelier («کارگاه») dock: the design replaces the floating pill bar with a
 * flat bar attached to the bottom edge — surface-colored, one hairline on
 * top, four destinations, and the active one marked by a short accent bar
 * above its icon (the accent is reserved for "the primary thing right
 * now", per the design's color rule). Shown only while the Atelier style
 * is active; every other style keeps the floating [AnvexDock].
 */
@Composable
fun AtelierDock(items: List<DockItem>) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
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
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = item.enabled, onClick = item.onClick)
                            .padding(top = 2.dp, bottom = 2.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(18.dp)
                                .height(3.dp)
                                .background(
                                    if (item.selected) accent else Color.Transparent,
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                        Icon(
                            item.icon,
                            contentDescription = item.label,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .size(22.dp),
                            tint = if (item.selected) accent else dim,
                        )
                        Text(
                            item.label,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (item.selected) accent else dim,
                        )
                    }
                }
            }
        }
    }
}
