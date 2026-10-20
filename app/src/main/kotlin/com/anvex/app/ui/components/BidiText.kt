package com.anvex.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection

/**
 * Direction-aware text for user/AI content.
 *
 * Compose correctly shapes mixed RTL/LTR glyphs, but an LTR application can
 * still left-align a Persian/Arabic paragraph. We detect the first strong
 * directional character and set both paragraph direction and alignment.
 * Code/paths remain LTR unless the content actually starts with a strong RTL
 * character.
 */
fun isRtlText(text: String): Boolean {
    // v4.0 fix: iterate Unicode code points, not UTF-16 chars. The packaged v3.3.9
    // implementation returned LEFT_TO_RIGHT for the *surrogate halves* of emoji
    // (Character.getDirectionality(char) on a surrogate returns LTR), so a Persian
    // paragraph starting with an emoji was misdetected as LTR.
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE -> return false
        }
        index += Character.charCount(codePoint)
    }
    return false
}

fun directionalTextStyle(base: TextStyle, text: String): TextStyle {
    val rtl = isRtlText(text)
    return base.copy(
        textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr,
        // v4.6 (RTL fix): ABSOLUTE alignment. The old Start/End mapping flipped
        // under the RTL LayoutDirection that BidiText itself installs: in an RTL
        // layout, End is the LEFT edge — so every Persian paragraph rendered
        // left-aligned instead of right-aligned (the user's report). Absolute
        // Right/Left means the same thing in both layout directions.
        textAlign = if (rtl) TextAlign.Right else TextAlign.Left,
    )
}

@Composable
fun BidiText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE,
) {
    val rtl = isRtlText(text)
    CompositionLocalProvider(
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        Text(
            text = text,
            modifier = modifier,
            style = directionalTextStyle(style, text),
            maxLines = maxLines,
        )
    }
}
