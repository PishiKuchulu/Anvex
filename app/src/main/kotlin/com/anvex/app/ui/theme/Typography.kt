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
 * The UI font is Vazirmatn (bundled, see [VazirmatnFont]) in EVERY style —
 * it carries both Persian and Latin glyphs, so the app's primary language
 * renders with consistent weight and metrics on every device instead of
 * falling back to whatever Persian font the device happens to ship
 * (2026-11-03 audit, Layer 1: only Atelier had a Persian-capable font).
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

private val UiFont: FontFamily get() = VazirmatnFont
val CodeFont: FontFamily by lazy { AnvexMono }

/**
 * Vazirmatn (SIL OFL 1.1), bundled in assets/fonts — the one UI font for
 * every theme style (Persian + Latin coverage). Lazy: needs AppGraph.
 *
 * ORDER MATTERS: this delegate must be declared BEFORE [AnvexTypography]
 * and [AtelierTypography]. Top-level initializers run in file order, and
 * both type scales read this property (via [UiFont]/[AtelierFont]) while
 * they initialize — reading a `by lazy` delegate whose field is assigned
 * later in the same <clinit> is an NPE (the v4.14.9/v4.15.0 launch crash:
 * ExceptionInInitializerError in TypographyKt). Keep it above them.
 */
val VazirmatnFont: FontFamily by lazy {
    val assets = AppGraph.get().context.assets
    FontFamily(
        androidx.compose.ui.text.font.Font("fonts/Vazirmatn-Regular.ttf", assets, FontWeight.Normal),
        androidx.compose.ui.text.font.Font("fonts/Vazirmatn-Medium.ttf", assets, FontWeight.Medium),
        androidx.compose.ui.text.font.Font("fonts/Vazirmatn-Bold.ttf", assets, FontWeight.Bold),
        androidx.compose.ui.text.font.Font("fonts/Vazirmatn-ExtraBold.ttf", assets, FontWeight.ExtraBold),
    )
}

/** Material 3 type scale tuned for a compact IDE without sacrificing readability. */
val AnvexTypography = Typography(
    displayLarge = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 52.sp),
    displayMedium = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 44.sp),
    displaySmall = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineLarge = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
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

/** Kept name for the Atelier scale below; same bundled family as [VazirmatnFont]. */
val AtelierFont: FontFamily get() = VazirmatnFont

/**
 * Atelier type scale (design spec section 4): Display 26/800, Title 20/800,
 * Headline 16/800, Body 14/500, Caption 12/500 — mapped onto the Material
 * slots the existing screens already read.
 */
val AtelierTypography = Typography(
    displayLarge = TextStyle(fontFamily = AtelierFont, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 44.sp),
    displayMedium = TextStyle(fontFamily = AtelierFont, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 40.sp),
    displaySmall = TextStyle(fontFamily = AtelierFont, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 34.sp),
    headlineLarge = TextStyle(fontFamily = AtelierFont, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 32.sp),
    headlineMedium = TextStyle(fontFamily = AtelierFont, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = AtelierFont, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, lineHeight = 27.sp),
    titleLarge = TextStyle(fontFamily = AtelierFont, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontFamily = AtelierFont, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = AtelierFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = AtelierFont, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = AtelierFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = AtelierFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = AtelierFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = AtelierFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = AtelierFont, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
)
