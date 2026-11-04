package com.anvex.app.ui.components

import androidx.compose.material.icons.filled.PlayArrow
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.anvex.app.i18n.tr

/**
 * Block model for the subset of Markdown the chat renders: headings, paragraphs,
 * bullet/numbered items, block quotes, fenced code, horizontal rules, and
 * tables (added 2026-11-03 — tables used to render as raw `| a | b |` text).
 * Images are shown as plain text on purpose.
 */
internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class ListItem(val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    object Rule : MdBlock
}

/** Splits a `| a | b |` row into trimmed cells (edge pipes dropped). */
private fun splitTableRow(line: String): List<String> {
    val t = line.trim()
    val inner = t.removePrefix("|").removeSuffix("|")
    return inner.split("|").map { it.trim() }
}

/** A separator row under a table header: only `|`, `-`, `:` and spaces, with at least one `-`. */
private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    return t.contains('-') && t.all { it == '|' || it == '-' || it == ':' || it == ' ' }
}

private val HEADING_RE = Regex("""^(#{1,6})\s+(.*)$""")
private val ITEM_RE = Regex("""^(?:([-*+])|(\d+)[.)])\s+(.*)$""")

/** Pure parser: no Compose types, so it can be unit-tested on the JVM. */
internal fun parseMarkdownBlocks(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split("\n")
    val blocks = mutableListOf<MdBlock>()
    val paragraph = mutableListOf<String>()

    fun flush() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.joinToString("\n").trimEnd())
            paragraph.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val trimmed = lines[i].trim()
        if (trimmed.startsWith("```")) {
            flush()
            val language = trimmed.removePrefix("```").trim()
            val code = mutableListOf<String>()
            i++
            // An unclosed fence runs to the end of the message (streaming-safe).
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                code += lines[i]
                i++
            }
            blocks += MdBlock.Code(language, code.joinToString("\n"))
            i++ // skip the closing fence
            continue
        }
        // Table: a `| … |` header line immediately followed by a
        // separator row. Consumes the header, the separator, and every
        // following `| … |` line as a body row.
        if (trimmed.startsWith("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            flush()
            val headers = splitTableRow(trimmed)
            i += 2 // header + separator
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trim().startsWith("|")) {
                rows += splitTableRow(lines[i])
                i++
            }
            blocks += MdBlock.Table(headers, rows)
            continue
        }
        val heading = HEADING_RE.matchEntire(trimmed)
        val item = ITEM_RE.matchEntire(trimmed)
        when {
            trimmed.isEmpty() -> flush()
            heading != null -> {
                flush()
                blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            }
            trimmed.length >= 3 && (trimmed.all { it == '-' } || trimmed.all { it == '*' } || trimmed.all { it == '_' }) -> {
                flush()
                blocks += MdBlock.Rule
            }
            item != null -> {
                flush()
                val marker = if (item.groupValues[1].isNotEmpty()) "•" else "${item.groupValues[2]}."
                blocks += MdBlock.ListItem(marker, item.groupValues[3])
            }
            trimmed.startsWith(">") -> {
                flush()
                blocks += MdBlock.Quote(trimmed.removePrefix(">").trimStart())
            }
            else -> paragraph += trimmed
        }
        i++
    }
    flush()
    return blocks
}

/**
 * Inline styles: **bold**, *italic*, `code`, [label](url). Links carry a
 * "URL" string annotation and ARE clickable (2026-11-03 audit: underlined
 * primary-colored text that never opened was a fake affordance) — the
 * renderer opens only http/https/mailto targets, in the system browser.
 */
internal fun inlineAnnotated(text: String, codeBg: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append('`'); i++
                }
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append("**"); i += 2
                }
            }
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1 && !text[i + 1].isWhitespace()) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append('*'); i++
                }
            }
            text[i] == '[' -> {
                val close = text.indexOf("](", i + 1)
                val end = if (close > 0) text.indexOf(')', close + 2) else -1
                if (close > i && end > close) {
                    val url = text.substring(close + 2, end).trim()
                    pushStringAnnotation(tag = "URL", annotation = url)
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        append(text.substring(i + 1, close))
                    }
                    pop()
                    i = end + 1
                } else {
                    append('['); i++
                }
            }
            else -> {
                append(text[i]); i++
            }
        }
    }
}

/**
 * Renders model output as Markdown. Persian/Arabic blocks are laid out RTL,
 * code blocks are always LTR, and every code block has a copy button.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Unspecified,
    onRunPython: ((String) -> Unit)? = null,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    // Callers that pass no color (the deliverable viewer, DirectChat) used
    // to render through ClickableText with Color.Unspecified, which does
    // NOT fall back to the theme content color — dark text on the dark
    // theme, effectively invisible (user report, 2026-11-04). Default to
    // the theme's onSurface instead.
    val base = MaterialTheme.typography.bodyMedium.copy(
        color = if (textColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else textColor,
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> InlineText(block.text, base)
                is MdBlock.Heading -> {
                    // 2026-11-03 audit: heading sizes were hard-coded sp
                    // values ignoring the theme's type ramp — derive them
                    // from the active typography instead.
                    val headingStyle = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    InlineText(
                        block.text,
                        base.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = headingStyle.fontSize,
                            lineHeight = headingStyle.lineHeight,
                        ),
                    )
                }
                is MdBlock.Table -> MarkdownTable(block, base)
                is MdBlock.ListItem -> {
                    val rtl = isRtlContent(block.text)
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    ) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(block.marker, style = base)
                            Spacer(Modifier.width(8.dp))
                            InlineText(block.text, base, Modifier.weight(1f))
                        }
                    }
                }
                is MdBlock.Quote -> InlineText(block.text, base.copy(fontStyle = FontStyle.Italic))
                is MdBlock.Code -> CodeBlock(block.language, block.code, onRunPython)
                MdBlock.Rule -> Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

/** Minimal table: header row + body rows, horizontally scrollable so wide
 * tables never crush the chat bubble. Cells reuse the inline renderer. */
@Composable
private fun MarkdownTable(table: MdBlock.Table, base: TextStyle) {
    val columnCount = maxOf(table.headers.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columnCount == 0) return
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .horizontalScroll(rememberScrollState())
                .padding(4.dp),
        ) {
            Row {
                for (c in 0 until columnCount) {
                    InlineText(
                        table.headers.getOrElse(c) { "" },
                        base.copy(fontWeight = FontWeight.Bold),
                        Modifier.widthIn(min = 72.dp, max = 220.dp).padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
            table.rows.forEach { row ->
                Row {
                    for (c in 0 until columnCount) {
                        InlineText(
                            row.getOrElse(c) { "" },
                            base,
                            Modifier.widthIn(min = 72.dp, max = 220.dp).padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, codeBg, linkColor) { inlineAnnotated(text, codeBg, linkColor) }
    val rtl = isRtlContent(text)
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    CompositionLocalProvider(
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        @Suppress("DEPRECATION") // ClickableText: the pre-LinkAnnotation API this codebase's Compose version supports.
        androidx.compose.foundation.text.ClickableText(
            text = annotated,
            modifier = modifier,
            style = directionalTextStyle(style, rtl),
            onClick = { offset ->
                // Only web/mail targets ever leave the app; anything else
                // a model emits (javascript:, file:, …) is ignored.
                val url = annotated.getStringAnnotations("URL", offset, offset)
                    .firstOrNull()?.item?.trim().orEmpty()
                if (url.startsWith("http://", true) || url.startsWith("https://", true) ||
                    url.startsWith("mailto:", true)
                ) {
                    runCatching { uriHandler.openUri(url) }
                }
            },
        )
    }
}

@Composable
private fun CodeBlock(language: String, code: String, onRun: ((String) -> Unit)?) {
    val context = LocalContext.current
    val copyLabel = tr("code")
    // 2026-11-03 audit: the two action descriptions were hard-coded
    // English, and copying gave no feedback at all. Descriptions are
    // localized now, and a successful copy swaps the icon to a check
    // (with a localized "Copied" description) for a moment.
    val copyDesc = tr("Copy code")
    val copiedDesc = tr("Copied")
    val runDesc = tr("Run Python")
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1600)
            copied = false
        }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText(copyLabel, code))
                        copied = true
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = if (copied) copiedDesc else copyDesc,
                    )
                }
                if (onRun != null && language.trim().lowercase() in setOf("python", "py")) {
                    IconButton(onClick = { onRun(code) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = runDesc)
                    }
                }
            }
            Text(
                code,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
            )
        }
    }
}
