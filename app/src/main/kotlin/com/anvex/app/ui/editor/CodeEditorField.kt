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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anvex.app.i18n.tr

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
    // F2a (P4): Tree-sitter spans for the exact text in [tsText], the
    // foldable ranges of the current parse, the user's folded set, and
    // the gutter toggle callback. All defaulted — without them the
    // field behaves exactly as before (regex highlighting, no folds).
    tsSpans: List<com.anvex.core.engine.codeintel.HighlightSpan> = emptyList(),
    tsText: String? = null,
    folds: List<com.anvex.core.engine.codeintel.FoldRange> = emptyList(),
    folded: Set<com.anvex.core.engine.codeintel.FoldRange> = emptySet(),
    onToggleFold: (com.anvex.core.engine.codeintel.FoldRange) -> Unit = {},
) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val colors = rememberHighlightColors()
    val projection = remember(value.text, folded) {
        com.anvex.core.engine.codeintel.FoldProjection.of(value.text, folded)
    }
    // Highlight cutoff: one shared constant with SyntaxTransformation —
    // the field used to gate on a second, larger limit in the view
    // model, so files between the two rendered highlighted anyway.
    val highlightEnabled = value.text.length <= HIGHLIGHT_LIMIT
    val transformation = if (highlightEnabled || tsText == value.text || projection != null) {
        remember(language, colors, searchRanges, tsSpans, tsText, projection) {
            EditorTransformation(language, colors, searchRanges, tsSpans, tsText, projection)
        }
    } else {
        androidx.compose.ui.text.input.VisualTransformation.None
    }

    // P-15 (audit 2026-11-03): the line count used to recount every
    // newline in the document per keystroke. The counter adjusts from
    // the edited region only (common prefix/suffix diff).
    val lineCounter = remember { LineCounter() }
    val lineCount = lineCounter.update(value.text)

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

    // tr() is @Composable — hoisted here; the semantics lambda below is not.
    val editorDescription = tr("Editor")
    Row(modifier = modifier.verticalScroll(verticalScroll)) {
        if (projection != null || folds.isNotEmpty()) {
            FoldGutter(
                projection = projection,
                lineCount = lineCount,
                folds = folds,
                folded = folded,
                fontSizeSp = fontSizeSp,
                style = style,
                onToggleFold = onToggleFold,
            )
        } else {
            LineNumberGutter(
                lineCount = lineCount,
                fontSizeSp = fontSizeSp,
                style = style,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = style,
            visualTransformation = transformation,
            readOnly = readOnly,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .horizontalScroll(horizontalScroll)
                // UI #3 (audit 2026-11-03): the editor surface itself
                // was undescribed — TalkBack landed on a bare field.
                .semantics { contentDescription = "$editorDescription · ${language.name}" },
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
    // Width follows the digit count (audit 2026-11-03): the fixed 44dp
    // gutter crowded/clipped once files passed ~4-digit line counts.
    val digits = lineCount.toString().length
    val gutterWidth = (digits * fontSizeSp * 0.62f + 16f).dp.coerceAtLeast(44.dp)
    androidx.compose.foundation.text.BasicText(
        text = numbers,
        style = style.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        ),
        modifier = Modifier
            .width(gutterWidth)
            .padding(end = 8.dp),
    )
}

/**
 * F2a (P4): the gutter when the current parse offers fold ranges. Shows
 * only visible line numbers (folded regions collapse to their first
 * line), a ▾/▸ marker on fold-start lines, and toggles the fold when a
 * fold-start line is tapped.
 */
@Composable
private fun FoldGutter(
    projection: com.anvex.core.engine.codeintel.FoldProjection?,
    lineCount: Int,
    folds: List<com.anvex.core.engine.codeintel.FoldRange>,
    folded: Set<com.anvex.core.engine.codeintel.FoldRange>,
    fontSizeSp: Int,
    style: TextStyle,
    onToggleFold: (com.anvex.core.engine.codeintel.FoldRange) -> Unit,
) {
    val visibleLines = projection?.visibleLines ?: (0 until lineCount).toList()
    val foldStartLines = remember(folds) { folds.map { it.startLine }.toSet() }
    val layoutState = remember {
        androidx.compose.runtime.mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null)
    }
    val numbers: AnnotatedString = remember(visibleLines, foldStartLines, folded) {
        buildAnnotatedString {
            for (line in visibleLines) {
                append((line + 1).toString())
                when {
                    folded.any { it.startLine == line } -> append(" ▸")
                    line in foldStartLines -> append(" ▾")
                }
                append('\n')
            }
        }
    }
    val digits = lineCount.toString().length
    val gutterWidth = (digits * fontSizeSp * 0.62f + fontSizeSp * 0.9f + 16f).dp.coerceAtLeast(52.dp)
    androidx.compose.foundation.text.BasicText(
        text = numbers,
        style = style.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        ),
        onTextLayout = { layoutState.value = it },
        modifier = Modifier
            .width(gutterWidth)
            .padding(end = 8.dp)
            .androidx.compose.ui.input.pointer.pointerInput(visibleLines, folds) {
                androidx.compose.foundation.gestures.detectTapGestures { offset ->
                    val result = layoutState.value ?: return@detectTapGestures
                    val index = result.getLineForVerticalPosition(offset.y)
                    if (index in visibleLines.indices) {
                        val originalLine = visibleLines[index]
                        val fold = folds.firstOrNull { it.startLine == originalLine }
                        if (fold != null) onToggleFold(fold)
                    }
                }
            },
    )
}

/**
 * Incremental line counter: keeps the running newline count and adjusts
 * it by the region that actually changed (longest common prefix/suffix
 * between the previous and new text), so a keystroke costs O(edit), not
 * O(document). A wholesale replacement still scans the changed middle —
 * never worse than the old full recount.
 */
private class LineCounter {
    private var previous: String = ""
    private var newlines: Int = 0

    fun update(text: String): Int {
        if (text == previous) return newlines + 1
        val minLen = minOf(previous.length, text.length)
        var prefix = 0
        while (prefix < minLen && previous[prefix] == text[prefix]) prefix++
        var suffix = 0
        while (suffix < (minLen - prefix) &&
            previous[previous.length - 1 - suffix] == text[text.length - 1 - suffix]
        ) suffix++
        val removed = previous.substring(prefix, previous.length - suffix)
        val added = text.substring(prefix, text.length - suffix)
        newlines += added.count { it == '\n' } - removed.count { it == '\n' }
        previous = text
        return newlines + 1
    }
}
