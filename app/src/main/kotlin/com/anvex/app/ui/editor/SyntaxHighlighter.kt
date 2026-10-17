package com.anvex.app.ui.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Languages highlighted by the editor (brief §7). */
enum class CodeLanguage {
    JS, TS, JSX, TSX, HTML, CSS, JSON, MARKDOWN, PLAIN;

    companion object {
        fun fromPath(path: String): CodeLanguage = when (path.substringAfterLast('.').lowercase()) {
            "js", "mjs", "cjs" -> JS
            "ts" -> TS
            "jsx" -> JSX
            "tsx" -> TSX
            "html", "htm", "svg", "xml" -> HTML
            "css" -> CSS
            "json" -> JSON
            "md", "markdown" -> MARKDOWN
            else -> PLAIN
        }
    }
}

data class HighlightColors(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val tag: Color,
    val attribute: Color,
    val key: Color,
)

/** Files larger than this are edited without highlighting (performance, brief §24). */
private const val HIGHLIGHT_LIMIT = 120_000

@Composable
fun rememberHighlightColors(): HighlightColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        HighlightColors(
            keyword = Color(0xFFC792EA),
            string = Color(0xFF9ECE6A),
            comment = Color(0xFF6B7394),
            number = Color(0xFFFF9E64),
            tag = Color(0xFFF7768E),
            attribute = Color(0xFFFFC46B),
            key = Color(0xFF7AA2F7),
        )
    }
}

/**
 * Regex-based syntax highlighter exposed as a [VisualTransformation] so the
 * underlying text value is untouched (identity offset mapping — editing,
 * selection, undo/redo all operate on raw text).
 */
class SyntaxTransformation(
    private val language: CodeLanguage,
    private val colors: HighlightColors,
    private val searchRanges: List<IntRange> = emptyList(),
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        if (text.length > HIGHLIGHT_LIMIT && searchRanges.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        return TransformedText(highlight(text.text, language, colors, searchRanges), OffsetMapping.Identity)
    }
}

fun highlight(
    text: String,
    language: CodeLanguage,
    colors: HighlightColors,
    searchRanges: List<IntRange> = emptyList(),
): AnnotatedString {
    if (text.length > HIGHLIGHT_LIMIT && searchRanges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        if (text.length <= HIGHLIGHT_LIMIT) {
            val pattern = patternFor(language)
            if (pattern != null) {
                for (m in pattern.findAll(text)) {
                    val style = styleFor(m, colors)
                    if (style != null) {
                        addStyle(style, m.range.first, m.range.last + 1)
                    }
                }
            }
        }
        for (r in searchRanges) {
            addStyle(SpanStyle(background = Color(0x66FFC46B)), r.first, (r.last + 1).coerceAtMost(length))
        }
    }
}

private fun patternFor(language: CodeLanguage): Regex? = when (language) {
    CodeLanguage.JS, CodeLanguage.JSX -> jsPattern
    CodeLanguage.TS, CodeLanguage.TSX -> tsPattern
    CodeLanguage.HTML -> htmlPattern
    CodeLanguage.CSS -> cssPattern
    CodeLanguage.JSON -> jsonPattern
    CodeLanguage.MARKDOWN -> mdPattern
    CodeLanguage.PLAIN -> null
}

private fun styleFor(m: MatchResult, c: HighlightColors): SpanStyle? {
    val g = m.groups
    return when {
        g.get("comment") != null -> SpanStyle(color = c.comment, fontStyle = FontStyle.Italic)
        g.get("str") != null -> SpanStyle(color = c.string)
        g.get("num") != null -> SpanStyle(color = c.number)
        g.get("kw") != null -> SpanStyle(color = c.keyword)
        g.get("tag") != null -> SpanStyle(color = c.tag)
        g.get("attr") != null -> SpanStyle(color = c.attribute)
        g.get("key") != null -> SpanStyle(color = c.key, fontWeight = FontWeight.SemiBold)
        g.get("bold") != null -> SpanStyle(fontWeight = FontWeight.Bold)
        g.get("heading") != null -> SpanStyle(fontWeight = FontWeight.Bold, color = c.key)
        else -> null
    }
}

private const val KW = """\b(?:const|let|var|function|return|if|else|for|while|import|from|export|default|class|extends|new|async|await|try|catch|finally|throw|typeof|instanceof|switch|case|break|continue|do|null|undefined|true|false|this|super)\b"""
private const val TSKW = """\b(?:interface|type|enum|implements|readonly|as|public|private|protected|string|number|boolean|any|void|never|unknown|namespace|declare|satisfies|keyof|infer)\b"""
private const val STR = """"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'|`(?:[^`\\]|\\.)*`"""
private const val NUM = """\b\d+(?:\.\d+)?\b"""

private val jsPattern = Regex(
    """(?<comment>//[^\n]*|/\*(?s:.*?)\*/)|(?<str>$STR)|(?<num>$NUM)|(?<kw>$KW)"""
)
private val tsPattern = Regex(
    """(?<comment>//[^\n]*|/\*(?s:.*?)\*/)|(?<str>$STR)|(?<num>$NUM)|(?<kw>$KW|$TSKW)"""
)
private val htmlPattern = Regex(
    """(?<comment><!--(?s:.*?)-->)|(?<str>$STR)|(?<tag></?[a-zA-Z][\w:-]*)|(?<attr>\b[a-zA-Z-]+(?==))"""
)
private val cssPattern = Regex(
    """(?<comment>/\*(?s:.*?)\*/)|(?<str>$STR)|(?<num>#[0-9a-fA-F]{3,8}\b|\b\d+(?:\.\d+)?(?:px|em|rem|%|vh|vw|s|ms|fr|deg)?\b)|(?<prop>[\w-]+(?=\s*:))"""
)
private val jsonPattern = Regex(
    """(?<key>"(?:[^"\\]|\\.)*"(?=\s*:))|(?<str>"(?:[^"\\]|\\.)*")|(?<num>-\b\d+(?:\.\d+)?\b)|(?<kw>\b(?:true|false|null)\b)"""
)
private val mdPattern = Regex(
    """(?<heading>^#{1,6} .*$)|(?<bold>\*\*[^*\n]+\*\*)|(?<str>`[^`\n]+`)""",
    setOf(RegexOption.MULTILINE)
)
