package com.anvex.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.anvex.core.android.AppGraph

/**
 * v4.0 (GOAL 4): JetBrains Mono is bundled offline (assets/harness/fonts, SIL OFL 1.1) and used for
 * every code-bearing surface: the editor, terminal, diff, build logs and path chips.
 * The UI font remains the platform sans (Roboto/Inter-like) for readability,
 * including Persian fallback glyphs.
 */
/** One font copy, shared with the web harness (served from assets/harness/fonts). Lazy: needs AppGraph. */
val AnvexMono: FontFamily by lazy {
    val assets = AppGraph.get().context.assets
    FontFamily(
        androidx.compose.ui.text.font.Font("harness/fonts/JetBrainsMono-Regular.ttf", assets, FontWeight.Normal),
        androidx.compose.ui.text.font.Font("harness/fonts/JetBrainsMono-Medium.ttf", assets, FontWeight.Medium),
        androidx.compose.ui.text.font.Font("harness/fonts/JetBrainsMono-Bold.ttf", assets, FontWeight.Bold),
    )
}

private val UiFont = FontFamily.SansSerif
val CodeFont: FontFamily by lazy { AnvexMono }

/** Material 3 type scale tuned for a compact IDE without sacrificing readability. */
val AnvexTypography = Typography(
    displaySmall = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = UiFont, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = UiFont, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = UiFont, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
)

/** Convenience style for monospace inline content (paths, commands, file names). */
val AnvexCodeStyle = TextStyle(fontFamily = AnvexMono, fontSize = 12.sp, lineHeight = 18.sp)
