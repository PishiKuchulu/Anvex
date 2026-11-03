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

/** Master cycle for [GooThinkingIcon]: LCM of the swing periods and the breathe cycle. */
private const val GOO_MASTER_MS = 2_217_600

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
    // tr() is @Composable, so it cannot be called inside the semantics
    // lambda below (a plain SemanticsPropertyReceiver scope) — resolve it here.
    val stopLabel = tr("Stop")
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val ink = if (dark) Color(0xFFF5F5F7) else Color(0xFF131316)
    // 2026-11-03 audit: five infinite transitions ran for the whole
    // stream with no reduced-motion check. When the user has reduced
    // motion on, every driven value below freezes at its rest position
    // (breathe 1f, swings 0 = satellites straight above the core), so
    // the icon renders one static frame with the same Stop semantics.
    // (Value-freezing rather than an early return: the hook order must
    // stay identical across recompositions.)
    val reducedMotion = com.anvex.app.ui.theme.rememberReducedMotion()
    // P-25 (audit round 2): ONE infinite animation drives everything.
    // The five concurrent animateFloat children (breathe + four swings)
    // are replaced by a single master phase over the least common
    // multiple of all five periods (2800/3600/2200/4400 ms swings,
    // 6400 ms full breathe cycle = 2 217 600 ms), so every arm angle
    // and the breathe scale are pure functions of one value and the
    // loop restarts seamlessly. The look is unchanged: same periods,
    // same directions, same EaseInOut breathe curve.
    val transition = rememberInfiniteTransition(label = "gooThinking")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = GOO_MASTER_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "gooPhase",
    )
    val tMs = phase * GOO_MASTER_MS
    fun swing(durMs: Float, dir: Float): Float = ((tMs / durMs) % 1f) * 360f * dir
    // Breathe: 3200 ms EaseInOut out, 3200 ms back (6400 ms full cycle).
    val breathePos = (tMs % 6400f) / 3200f
    val breatheFrac = if (breathePos < 1f) breathePos else 2f - breathePos
    val breatheAnimated = 0.82f +
        (1.05f - 0.82f) * androidx.compose.animation.core.EaseInOut.transform(breatheFrac)
    // Reduced motion: freeze at rest (swings 0 = satellites straight
    // above the core, breathe 1f).
    val armAngles = if (reducedMotion) {
        listOf(0f, 0f, 0f, 0f)
    } else {
        listOf(swing(2800f, 1f), swing(3600f, -1f), swing(2200f, 1f), swing(4400f, -1f))
    }
    val breatheEff = if (reducedMotion) 1f else breatheAnimated
    // Satellite (radius, orbit distance) in 64-viewBox units, as in the SVG.
    val arms = listOf(7f to 11f, 5.5f to 14.5f, 4.6f to 9f, 4f to 16f)
    Canvas(
        modifier
            .size(iconSize)
            .semantics { contentDescription = stopLabel },
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
        drawCircle(color = ink, radius = 9.5f * u * breatheEff, center = center)
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
