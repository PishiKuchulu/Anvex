package com.anvex.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anvex.app.ui.theme.LocalBoltExtras

/**
 * v4.2 "Nebula" building blocks. Everything reads its colors from [LocalBoltExtras] and the
 * Material color scheme, so the same components stay coherent in every theme style.
 */

/** Ambient canvas: vertical gradient plus two soft radial glows (violet top-left, cyan lower-right). */
@Composable
fun NebulaBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val extras = LocalBoltExtras.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(extras.canvasBrush)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(extras.accentStart.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.05f, size.height * 0.02f),
                        radius = size.width * 0.9f,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(extras.accentEnd.copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(size.width * 0.98f, size.height * 0.85f),
                        radius = size.width * 0.8f,
                    ),
                )
            },
        content = content,
    )
}

/** Translucent bordered surface — the signature card of the Nebula family. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    val extras = LocalBoltExtras.current
    val shape = RoundedCornerShape(22.dp)
    val base = modifier
        .clip(shape)
        .background(extras.glass)
        .border(BorderStroke(1.dp, extras.glassBorder), shape)
    Box(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base).padding(contentPadding),
    ) { content() }
}

/** Primary call-to-action: pill filled with the accent gradient. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val extras = LocalBoltExtras.current
    val shape = RoundedCornerShape(100.dp)
    val fill = if (enabled) Modifier.background(extras.accentBrush, shape)
    else Modifier.background(MaterialTheme.colorScheme.surfaceVariant, shape)
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .then(fill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // v4.4.5 (UX): the label color follows the gradient's brightest end.
        // White on the bright mint/cyan end was ~2:1 contrast on dark themes.
        val tint = if (enabled) {
            if (extras.accentEnd.luminance() > 0.35f) Color(0xFF101418) else Color.White
        } else MaterialTheme.colorScheme.onSurfaceVariant
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = tint, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Small tinted pill used for model names, tags and quick actions. */
@Composable
fun NebulaChip(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val extras = LocalBoltExtras.current
    val shape = RoundedCornerShape(100.dp)
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else extras.glass
    val stroke = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else extras.glassBorder
    val fg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val base = modifier.clip(shape).background(bg).border(BorderStroke(1.dp, stroke), shape)
    Row(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Round icon badge filled with the accent gradient (logo, avatars, empty states). */
@Composable
fun GradientBadge(icon: ImageVector, modifier: Modifier = Modifier, size: Int = 44) {
    val extras = LocalBoltExtras.current
    Box(
        modifier = modifier.size(size.dp).clip(CircleShape).background(extras.accentBrush),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.5f).dp))
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GradientBadge(icon, size = 64)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

/** Transparent top bar that lets the Nebula canvas show through. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NebulaTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            }
        },
        actions = { actions() },
        // v4.4.5 (UX): explicit content colors — a transparent container
        // leaves contentColorFor() Unspecified (black-text fallback).
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}
