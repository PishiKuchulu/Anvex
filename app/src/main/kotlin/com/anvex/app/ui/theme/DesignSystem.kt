package com.anvex.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Anvex v4.0 design tokens (GOAL 4) — strict 8pt grid.
 *
 * Every spacing/size token is a multiple of 4 (half-step) or 8 (full step):
 * 4 · 8 · 12 · 16 · 24 · 32 · 40 · 48. Radii are intentionally compact for the
 * professional family; the GLASS theme overrides them through [AnvexTheme] shapes.
 */
object AnvexDimens {
    val hairline2 = 2.dp
    val page = 16.dp
    val pageLarge = 24.dp
    val compactPage = 8.dp
    val section = 24.dp
    val sectionLarge = 32.dp
    val itemGap = 8.dp
    val itemGapSmall = 4.dp
    val control = 48.dp
    val smallControl = 40.dp
    val iconButton = 48.dp
    val chipHeight = 40.dp
    val radiusSmall = 8.dp
    val radiusMedium = 12.dp
    val radiusLarge = 16.dp
    val radiusXLarge = 20.dp
    val radiusPill = 100.dp
    val hairline = 1.dp
}

object AnvexElevation {
    val card = 0.dp
    val raised = 2.dp
    val navigation = 3.dp
    val modal = 6.dp
}

/** Legacy alias kept for screens not yet migrated to per-style shapes. */
val AnvexShapes = Shapes(
    extraSmall = RoundedCornerShape(AnvexDimens.radiusSmall),
    small = RoundedCornerShape(AnvexDimens.radiusSmall),
    medium = RoundedCornerShape(AnvexDimens.radiusMedium),
    large = RoundedCornerShape(AnvexDimens.radiusLarge),
    extraLarge = RoundedCornerShape(AnvexDimens.radiusXLarge),
)

@Composable
fun boltContentColor() = MaterialTheme.colorScheme.onSurface
