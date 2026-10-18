package com.anvex.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.anvex.app.ui.theme.AnvexDimens

@Composable
fun AnvexSectionHeader(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = AnvexDimens.page, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        action?.invoke()
    }
}

@Composable
fun AnvexCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val nebula = com.anvex.app.ui.theme.LocalThemeStyle.current == com.anvex.core.android.repo.UiThemeStyle.NEBULA
    val extras = com.anvex.app.ui.theme.LocalBoltExtras.current
    val shape = RoundedCornerShape(if (nebula) 22.dp else AnvexDimens.radiusLarge)
    val colors = CardDefaults.cardColors(
        containerColor = if (nebula) extras.glass else MaterialTheme.colorScheme.surface,
        // v4.4.5 (UX): explicit — translucent glass makes contentColorFor()
        // read luminance 1.0 (alpha ignored) and picks BLACK on dark canvas.
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    val border = BorderStroke(1.dp, if (nebula) extras.glassBorder else MaterialTheme.colorScheme.outlineVariant)
    if (onClick == null) {
        Card(modifier = modifier, shape = shape, colors = colors, border = border, content = { content() })
    } else {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, border = border, content = { content() })
    }
}

@Composable
fun AnvexIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        modifier = modifier.size(44.dp),
        shape = RoundedCornerShape(AnvexDimens.radiusMedium),
        color = containerColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun AnvexStatusBadge(
    label: String,
    state: AnvexStatus = AnvexStatus.INFO,
) {
    val (icon, color) = when (state) {
        AnvexStatus.SUCCESS -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.tertiary
        AnvexStatus.WARNING -> Icons.Filled.Schedule to MaterialTheme.colorScheme.secondary
        AnvexStatus.ERROR -> Icons.Filled.Error to MaterialTheme.colorScheme.error
        AnvexStatus.INFO -> Icons.Filled.Info to MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

enum class AnvexStatus { SUCCESS, WARNING, ERROR, INFO }
