package com.anvex.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.anvex.core.android.repo.UiThemeStyle

/**
 * Anvex v4.0 design language (GOAL 4).
 *
 * PROFESSIONAL (default) — Linear/Vercel-inspired: near-black neutral surfaces, one
 * restrained indigo accent, hairline outlines, 8pt spacing rhythm, JetBrains Mono for
 * code and metadata labels. Dark-first: fresh installs default to dark.
 *
 * GLASS — second theme selectable in Settings: Glassmorphism / expressive Material 3
 * with violet-tinted deep surfaces, larger radii and softer tonal containers.
 *
 * Dynamic Material You remains opt-in (Android 12+) and maps to the GLASS family so
 * expressive system palettes keep their character.
 */

// ------------------------------------------------------------------ professional

private val ProfPrimaryDark = Color(0xFF969EF2)
private val ProfBgDark = Color(0xFF08090B)
private val ProfSurfaceDark = Color(0xFF0F1113)
private val ProfContainerDark = Color(0xFF171A1D)

private val ProfessionalDarkScheme = darkColorScheme(
    primary = ProfPrimaryDark,
    onPrimary = Color(0xFF1A1D56),
    primaryContainer = Color(0xFF2B2F66),
    onPrimaryContainer = Color(0xFFDFE1FF),
    secondary = Color(0xFF9BA6C4),
    onSecondary = Color(0xFF141B2E),
    secondaryContainer = Color(0xFF252B3D),
    onSecondaryContainer = Color(0xFFD5DBF0),
    tertiary = Color(0xFF7AD7C5),
    onTertiary = Color(0xFF0A3B32),
    tertiaryContainer = Color(0xFF16423B),
    onTertiaryContainer = Color(0xFFA9F0E1),
    background = ProfBgDark,
    onBackground = Color(0xFFE8EAF0),
    surface = ProfSurfaceDark,
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = ProfContainerDark,
    onSurfaceVariant = Color(0xFF9BA0AA),
    outline = Color(0xFF30343B),
    outlineVariant = Color(0xFF212429),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF4F171B),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val ProfessionalLightScheme = lightColorScheme(
    primary = Color(0xFF4C56B8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E2FF),
    onPrimaryContainer = Color(0xFF13164A),
    secondary = Color(0xFF3D4660),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE0F2),
    onSecondaryContainer = Color(0xFF101426),
    tertiary = Color(0xFF1A6E62),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB4F1E3),
    onTertiaryContainer = Color(0xFF00201C),
    background = Color(0xFFFAFAFC),
    onBackground = Color(0xFF17181C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17181C),
    surfaceVariant = Color(0xFFEDEEF1),
    onSurfaceVariant = Color(0xFF494E58),
    outline = Color(0xFF787E8A),
    outlineVariant = Color(0xFFD9DBE1),
)

// ------------------------------------------------------------------ glass

private val GlassDarkScheme = darkColorScheme(
    primary = Color(0xFFC9BFFF),
    onPrimary = Color(0xFF2A1D6B),
    primaryContainer = Color(0xFF45349B),
    onPrimaryContainer = Color(0xFFE7E0FF),
    secondary = Color(0xFFB7C4E8),
    onSecondary = Color(0xFF1F2A45),
    secondaryContainer = Color(0xFF33405F),
    onSecondaryContainer = Color(0xFFDAE2FF),
    tertiary = Color(0xFF83E5F5),
    onTertiary = Color(0xFF003741),
    tertiaryContainer = Color(0xFF0F4C58),
    onTertiaryContainer = Color(0xFFB2EBF8),
    background = Color(0xFF0C0A16),
    onBackground = Color(0xFFEAE6F7),
    surface = Color(0xFF151221),
    onSurface = Color(0xFFEAE6F7),
    surfaceVariant = Color(0xFF1F1B30),
    onSurfaceVariant = Color(0xFFB2ABC9),
    outline = Color(0xFF4A4458),
    outlineVariant = Color(0xFF2C283D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF4F171B),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val GlassLightScheme = lightColorScheme(
    primary = Color(0xFF5B4BC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E0FF),
    onPrimaryContainer = Color(0xFF1B1155),
    secondary = Color(0xFF46508A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE1FF),
    onSecondaryContainer = Color(0xFF0F1A38),
    tertiary = Color(0xFF1A6382),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC1E8FF),
    onTertiaryContainer = Color(0xFF001E2E),
    background = Color(0xFFFAF7FF),
    onBackground = Color(0xFF1C1B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B22),
    surfaceVariant = Color(0xFFEDE9F6),
    onSurfaceVariant = Color(0xFF484554),
    outline = Color(0xFF797489),
    outlineVariant = Color(0xFFC9C4D8),
)

/** Shape tokens per style — professional stays compact, glass goes expressive. */
private val ProfessionalShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

private val GlassShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// ------------------------------------------------------------------ aurora (v4.4 — untitled.zip web-prototype port)

/**
 * AURORA — native port of the untitled.zip web prototype's design language:
 * the #070b14→#050811 dark ladder, cyan primary (#22D3EE), slate neutrals and the
 * cyan→indigo signature gradient, with the web badge/glow recipe available as extras.
 */
private val AuroraDarkScheme = darkColorScheme(
    primary = Color(0xFF22D3EE),          // cyan-400 #22d3ee — the web prototype primary
    onPrimary = Color(0xFF00323F),
    primaryContainer = Color(0xFF083344), // cyan-950
    onPrimaryContainer = Color(0xFFC9F5FF),
    secondary = Color(0xFF818CF8),        // indigo-400 — gradient partner
    onSecondary = Color(0xFF1E1B4B),
    secondaryContainer = Color(0xFF1E1B4B), // indigo-950
    onSecondaryContainer = Color(0xFFE0E7FF),
    tertiary = Color(0xFF34D399),         // emerald-400 — success accent
    onTertiary = Color(0xFF003826),
    tertiaryContainer = Color(0xFF022C22),
    onTertiaryContainer = Color(0xFFA7F3D0),
    background = Color(0xFF070B14),       // app-root #070b14
    onBackground = Color(0xFFF8FAFC),     // slate-50
    surface = Color(0xFF0B101E),          // surface-1 #0b101e
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF0F172A),   // panel #0f172a
    onSurfaceVariant = Color(0xFF94A3B8), // slate-400
    outline = Color(0xFF1E293B),          // slate-800 — THE web divider color
    outlineVariant = Color(0xFF334155),   // slate-700 — input borders
    error = Color(0xFFFB7185),            // rose-400
    onError = Color(0xFF4C0519),
    errorContainer = Color(0xFF4C0519),
    onErrorContainer = Color(0xFFFFE4E6),
)

private val AuroraLightScheme = lightColorScheme(
    primary = Color(0xFF0E7490),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFFAFE),
    onPrimaryContainer = Color(0xFF083344),
    secondary = Color(0xFF4338CA),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E7FF),
    onSecondaryContainer = Color(0xFF1E1B4B),
    tertiary = Color(0xFF047857),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFA7F3D0),
    onTertiaryContainer = Color(0xFF022C22),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
)

private val AuroraShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),   // rounded-md small buttons
    small = RoundedCornerShape(8.dp),        // rounded-lg inputs
    medium = RoundedCornerShape(12.dp),      // rounded-xl cards/tiles
    large = RoundedCornerShape(16.dp),       // rounded-2xl hero cards/modals
    extraLarge = RoundedCornerShape(28.dp),
)

private val AuroraExtrasDark = AnvexExtras(
    accentStart = Color(0xFF06B6D4), accentEnd = Color(0xFF4F46E5),  // cyan-500→indigo-600
    canvasTop = Color(0xFF070B14), canvasBottom = Color(0xFF050811), // #070b14→#050811 ladder
    userBubbleStart = Color(0xFF0891B2), userBubbleEnd = Color(0xFF4F46E5), // cyan-600→indigo-600
    glass = Color(0xCC0B101E), glassBorder = Color(0x1FE2E8F0),      // slate-900/80 + slate-200/12
)

private val AuroraExtrasLight = AnvexExtras(
    accentStart = Color(0xFF0891B2), accentEnd = Color(0xFF4F46E5),
    canvasTop = Color(0xFFF8FAFC), canvasBottom = Color(0xFFEDF2F7),
    userBubbleStart = Color(0xFF0E7490), userBubbleEnd = Color(0xFF4338CA),
    glass = Color(0xB3FFFFFF), glassBorder = Color(0x1F0F172A),
)

// ------------------------------------------------------------------ nebula (v4.2 default)

private val NebulaDarkScheme = darkColorScheme(
    primary = Color(0xFFB39DFF),
    onPrimary = Color(0xFF25105F),
    primaryContainer = Color(0xFF34237A),
    onPrimaryContainer = Color(0xFFE9E2FF),
    secondary = Color(0xFF67D4F5),
    onSecondary = Color(0xFF00323F),
    secondaryContainer = Color(0xFF0F3F52),
    onSecondaryContainer = Color(0xFFC3EEFF),
    tertiary = Color(0xFF5BE3B0),
    onTertiary = Color(0xFF003826),
    tertiaryContainer = Color(0xFF0B4A37),
    onTertiaryContainer = Color(0xFFA8F5D5),
    background = Color.Transparent, // the canvas is drawn by NebulaBackground at the app root
    onBackground = Color(0xFFECEBFF),
    surface = Color(0xFF0E0E1B),
    onSurface = Color(0xFFECEBFF),
    surfaceVariant = Color(0xFF181829),
    onSurfaceVariant = Color(0xFFA9A8C8),
    outline = Color(0xFF3B3B63),
    outlineVariant = Color(0xFF24243F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF4F171B),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val NebulaLightScheme = lightColorScheme(
    primary = Color(0xFF6A47E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E0FF),
    onPrimaryContainer = Color(0xFF1E0A6B),
    secondary = Color(0xFF0B7CA0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC6EDFF),
    onSecondaryContainer = Color(0xFF001F2B),
    tertiary = Color(0xFF0F7A57),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB4F4D8),
    onTertiaryContainer = Color(0xFF002115),
    background = Color.Transparent,
    onBackground = Color(0xFF17162A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17162A),
    surfaceVariant = Color(0xFFEBE8F8),
    onSurfaceVariant = Color(0xFF4A4863),
    outline = Color(0xFF7A78A0),
    outlineVariant = Color(0xFFD5D2EA),
)

private val NebulaShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Extra design tokens that Material 3 has no slot for: the signature gradient, the ambient
 * canvas, chat-bubble fills and the translucent "glass" card treatment.
 */
@Immutable
data class AnvexExtras(
    val accentStart: Color,
    val accentEnd: Color,
    val canvasTop: Color,
    val canvasBottom: Color,
    val userBubbleStart: Color,
    val userBubbleEnd: Color,
    val glass: Color,
    val glassBorder: Color,
) {
    val accentBrush: Brush get() = Brush.linearGradient(listOf(accentStart, accentEnd))
    val userBubbleBrush: Brush get() = Brush.linearGradient(listOf(userBubbleStart, userBubbleEnd))
    val canvasBrush: Brush get() = Brush.verticalGradient(listOf(canvasTop, canvasBottom))
}

private val NebulaExtrasDark = AnvexExtras(
    accentStart = Color(0xFF8B5CF6), accentEnd = Color(0xFF22D3EE),
    canvasTop = Color(0xFF14102B), canvasBottom = Color(0xFF07070F),
    userBubbleStart = Color(0xFF5B3FD6), userBubbleEnd = Color(0xFF2E6FE0),
    glass = Color(0x1AFFFFFF), glassBorder = Color(0x2EFFFFFF),
)

private val NebulaExtrasLight = AnvexExtras(
    accentStart = Color(0xFF7C4DFF), accentEnd = Color(0xFF00B8D9),
    canvasTop = Color(0xFFEDE8FF), canvasBottom = Color(0xFFF6F5FF),
    userBubbleStart = Color(0xFF6A47E5), userBubbleEnd = Color(0xFF3B7BEA),
    glass = Color(0xB3FFFFFF), glassBorder = Color(0x33000000),
)

private fun extrasFor(style: UiThemeStyle, dark: Boolean, scheme: androidx.compose.material3.ColorScheme): AnvexExtras =
    when (style) {
        UiThemeStyle.NEBULA -> if (dark) NebulaExtrasDark else NebulaExtrasLight
        UiThemeStyle.AURORA -> if (dark) AuroraExtrasDark else AuroraExtrasLight
        else -> AnvexExtras(
            accentStart = scheme.primary, accentEnd = scheme.tertiary,
            canvasTop = scheme.background, canvasBottom = scheme.background,
            userBubbleStart = scheme.primaryContainer, userBubbleEnd = scheme.primaryContainer,
            glass = scheme.surfaceVariant, glassBorder = scheme.outlineVariant,
        )
    }

/** Exposed so surfaces can adapt (e.g. hairline borders vs tonal glass). */
val LocalThemeStyle = staticCompositionLocalOf { UiThemeStyle.NEBULA }
val LocalBoltExtras = staticCompositionLocalOf { NebulaExtrasDark }

@Composable
fun AnvexTheme(
    dark: Boolean? = null,
    dynamicColor: Boolean = false,
    themeStyle: UiThemeStyle? = null,
    content: @Composable () -> Unit,
) {
    val useDark = dark ?: isSystemInDarkTheme()
    // v4.4.4 (Command Deck): PROFESSIONAL is the default style — the Obsidian IDE direction.
    val style = themeStyle ?: UiThemeStyle.PROFESSIONAL
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        // Dynamic Material You is opt-in only; it replaces the designed palette.
        useDynamic -> {
            val context = LocalContext.current
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        style == UiThemeStyle.NEBULA -> if (useDark) NebulaDarkScheme else NebulaLightScheme
        style == UiThemeStyle.GLASS -> if (useDark) GlassDarkScheme else GlassLightScheme
        style == UiThemeStyle.AURORA -> if (useDark) AuroraDarkScheme else AuroraLightScheme
        else -> if (useDark) ProfessionalDarkScheme else ProfessionalLightScheme
    }
    val shapes = when {
        style == UiThemeStyle.NEBULA -> NebulaShapes
        style == UiThemeStyle.GLASS || useDynamic -> GlassShapes
        style == UiThemeStyle.AURORA -> AuroraShapes
        else -> ProfessionalShapes
    }
    val extras = extrasFor(if (useDynamic) UiThemeStyle.GLASS else style, useDark, colorScheme)

    androidx.compose.runtime.CompositionLocalProvider(
        LocalThemeStyle provides style,
        LocalBoltExtras provides extras,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AnvexTypography,
            shapes = shapes,
            content = content,
        )
    }
}
