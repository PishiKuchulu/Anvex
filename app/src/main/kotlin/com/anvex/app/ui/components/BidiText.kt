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

/**
 * Direction of agent/user PROSE that may carry inline markdown. The raw
 * string is a bad detection source: a Persian paragraph whose first token
 * is a Latin filename or a `code` span reads as LTR and the whole
 * paragraph then wraps with the wrong base direction (user report,
 * 2026-11-04). Code spans are dropped entirely (their content is code,
 * never prose), emphasis/link syntax is unwrapped, and the first strong
 * character of what remains decides.
 */
fun isRtlContent(text: String): Boolean = isRtlText(stripInlineMarkdown(text))

private val CODE_SPAN_RE = Regex("`{1,3}[^`]*`{1,3}")
private val LINK_RE = Regex("\\[([^]]*)]\\([^)]*\\)")
private val EMPHASIS_RE = Regex("[*_~]+")

fun stripInlineMarkdown(text: String): String =
    text.replace(CODE_SPAN_RE, " ")
        .replace(LINK_RE, "$1")
        .replace(EMPHASIS_RE, " ")

fun directionalTextStyle(base: TextStyle, rtl: Boolean): TextStyle =
    base.copy(
        textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr,
        textAlign = if (rtl) TextAlign.Right else TextAlign.Left,
    )

fun directionalTextStyle(base: TextStyle, text: String): TextStyle =
    // v4.6 (RTL fix): the Boolean overload uses ABSOLUTE alignment. The old
    // Start/End mapping flipped under the RTL LayoutDirection that BidiText
    // itself installs: in an RTL layout, End is the LEFT edge — so every
    // Persian paragraph rendered left-aligned instead of right-aligned (the
    // user's report). Absolute Right/Left means the same in both directions.
    directionalTextStyle(base, isRtlText(text))

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
