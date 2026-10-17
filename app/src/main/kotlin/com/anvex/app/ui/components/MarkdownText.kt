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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anvex.app.i18n.tr

/**
 * Block model for the subset of Markdown the chat renders: headings, paragraphs,
 * bullet/numbered items, block quotes, fenced code, and horizontal rules.
 * Tables and images are shown as plain text on purpose (Phase 1 scope).
 */
internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class ListItem(val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    object Rule : MdBlock
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
 * Inline styles: **bold**, *italic*, `code`, [label](url). Links are shown
 * underlined but not clickable (no URL is opened from model output).
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
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        append(text.substring(i + 1, close))
                    }
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

private fun headingSize(level: Int): TextUnit = when (level) {
    1 -> 22.sp
    2 -> 19.sp
    3 -> 17.sp
    else -> 16.sp
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
    val base = MaterialTheme.typography.bodyMedium.copy(color = textColor)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> InlineText(block.text, base)
                is MdBlock.Heading -> InlineText(
                    block.text,
                    base.copy(fontWeight = FontWeight.Bold, fontSize = headingSize(block.level)),
                )
                is MdBlock.ListItem -> {
                    val rtl = isRtlText(block.text)
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

@Composable
private fun InlineText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, codeBg, linkColor) { inlineAnnotated(text, codeBg, linkColor) }
    val rtl = isRtlText(text)
    CompositionLocalProvider(
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        Text(
            text = annotated,
            modifier = modifier,
            style = directionalTextStyle(style, text),
        )
    }
}

@Composable
private fun CodeBlock(language: String, code: String, onRun: ((String) -> Unit)?) {
    val context = LocalContext.current
    val copyLabel = tr("code")
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
                    },
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy code") }
                if (onRun != null && language.trim().lowercase() in setOf("python", "py")) {
                    IconButton(onClick = { onRun(code) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Run Python")
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
