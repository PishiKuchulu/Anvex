package com.anvex.app.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Real code editor field (brief §7): monospace, no soft wrap (horizontal
 * scroll), line-number gutter kept in sync through a SHARED vertical scroll
 * state on the parent, syntax highlighting via identity-mapped
 * [SyntaxTransformation].
 *
 * v1.1 §21: `readOnly` renders a select/scroll-only view (huge files), and
 * highlighting is skipped beyond the highlight cutoff to avoid jank.
 */
@Composable
fun CodeEditorField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    language: CodeLanguage,
    fontSizeSp: Int,
    modifier: Modifier = Modifier,
    searchRanges: List<IntRange> = emptyList(),
    readOnly: Boolean = false,
) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val colors = rememberHighlightColors()
    // Highlight cutoff (v1.1 §21): very large files render plain — still fast.
    val highlightEnabled = value.text.length <= com.anvex.app.vm.EditorViewModel.HIGHLIGHT_LIMIT
    val transformation = if (highlightEnabled) {
        remember(language, colors, searchRanges) { SyntaxTransformation(language, colors, searchRanges) }
    } else {
        androidx.compose.ui.text.input.VisualTransformation.None
    }

    val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }

    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * 1.4f).sp,
        color = MaterialTheme.colorScheme.onSurface,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Top,
            trim = LineHeightStyle.Trim.None,
        ),
    )

    Row(modifier = modifier.verticalScroll(verticalScroll)) {
        LineNumberGutter(
            lineCount = lineCount,
            fontSizeSp = fontSizeSp,
            style = style,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = style,
            visualTransformation = transformation,
            readOnly = readOnly,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .horizontalScroll(horizontalScroll),
        )
    }
}

@Composable
private fun LineNumberGutter(lineCount: Int, fontSizeSp: Int, style: TextStyle) {
    val numbers: AnnotatedString = remember(lineCount) {
        buildAnnotatedString {
            for (i in 1..lineCount) {
                append(i.toString())
                append('\n')
            }
        }
    }
    androidx.compose.foundation.text.BasicText(
        text = numbers,
        style = style.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        ),
        modifier = Modifier
            .width(44.dp)
            .padding(end = 8.dp),
    )
}
