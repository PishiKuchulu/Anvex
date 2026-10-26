package com.anvex.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anvex.app.i18n.tr
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated "thinking" indicator recreating the user's goo-loader SVG
 * (Svg_light / Svg_dark) in Compose Canvas — the SVG's CSS animations and
 * gooey blur filter have no Compose equivalent, so the motion is reproduced
 * directly and the weld filter is approximated by the circles overlapping:
 *
 * - faint boundary circle, ink at 14% alpha, radius 30/64;
 * - central core, radius 9.5/64, breathing scale 0.82 <-> 1.05 over 3.2s
 *   ease-in-out (the SVG's goo-breathe keyframes);
 * - four satellite circles orbiting the center, each with the SVG's own
 *   radius, orbit distance, duration and direction (goo-swing):
 *   (r 7, d 11, 2.8s, +1), (r 5.5, d 14.5, 3.6s, -1),
 *   (r 4.6, d 9, 2.2s, +1), (r 4, d 16, 4.4s, -1).
 *
 * Ink follows the theme the way the two SVG files do: near-black #131316
 * on a light theme, near-white #f5f5f7 on a dark theme.
 */
@Composable
fun GooThinkingIcon(
    modifier: Modifier = Modifier,
    iconSize: Dp = 32.dp,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val ink = if (dark) Color(0xFFF5F5F7) else Color(0xFF131316)
    val transition = rememberInfiniteTransition(label = "gooThinking")
    val breathe by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = androidx.compose.animation.core.EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "gooBreathe",
    )
    // One angle per arm; durations/directions match the SVG's --dur/--dir.
    // (Unrolled: animateFloat is @Composable and cannot live inside a map lambda.)
    val swing0 by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart),
        label = "gooSwing0",
    )
    val swing1 by transition.animateFloat(
        initialValue = 0f, targetValue = -360f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
        label = "gooSwing1",
    )
    val swing2 by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "gooSwing2",
    )
    val swing3 by transition.animateFloat(
        initialValue = 0f, targetValue = -360f,
        animationSpec = infiniteRepeatable(tween(4400, easing = LinearEasing), RepeatMode.Restart),
        label = "gooSwing3",
    )
    val armAngles = listOf(swing0, swing1, swing2, swing3)
    // Satellite (radius, orbit distance) in 64-viewBox units, as in the SVG.
    val arms = listOf(7f to 11f, 5.5f to 14.5f, 4.6f to 9f, 4f to 16f)
    Canvas(
        modifier
            .size(iconSize)
            .semantics { contentDescription = tr("Stop") },
    ) {
        // this.size is the DrawScope pixel size (the Dp param is iconSize).
        val u = this.size.minDimension / 64f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        drawCircle(
            color = ink.copy(alpha = 0.14f),
            radius = 30f * u,
            center = center,
            style = Stroke(width = 1f * u),
        )
        drawCircle(color = ink, radius = 9.5f * u * breathe, center = center)
        arms.forEachIndexed { index, (r, d) ->
            // SVG arms start straight above the center (-90deg) and rotate.
            val angle = Math.toRadians((armAngles[index] - 90f).toDouble())
            val pos = Offset(
                x = center.x + (d * u * cos(angle)).toFloat(),
                y = center.y + (d * u * sin(angle)).toFloat(),
            )
            drawCircle(color = ink, radius = r * u, center = pos)
        }
    }
}
