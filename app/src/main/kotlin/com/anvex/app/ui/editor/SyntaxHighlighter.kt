package com.anvex.app.ui.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
    JS, TS, JSX, TSX, HTML, CSS, JSON, MARKDOWN, KOTLIN, JAVA, PYTHON, PLAIN;

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
            // Kotlin is this app's home language (audit 2026-11-03: it
            // fell back to PLAIN while JS/TS were highlighted).
            "kt", "kts" -> KOTLIN
            // Round 2 (CE2): Java and Python were the last PLAIN holdouts.
            "java" -> JAVA
            "py", "pyw" -> PYTHON
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
    val searchBackground: Color,
)

/**
 * Files larger than this are edited without highlighting. P-15 (audit
 * 2026-11-03): the cutoff was 120k chars, and BasicTextField re-runs
 * the whole regex pass per keystroke — typing latency grew with file
 * size right up to the cutoff. 60k keeps highlighting for real-world
 * source files while bounding the per-keystroke pass.
 */
internal const val HIGHLIGHT_LIMIT = 60_000

@Composable
fun rememberHighlightColors(): HighlightColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        // UI #3 (audit 2026-11-03): the palette used to be hard-coded
        // Tokyo-Night regardless of theme — its light-green strings and
        // mid-gray comments wash out on light backgrounds. Palettes are
        // now chosen by the active scheme's luminance: the dark set
        // keeps the Tokyo-Night hues; the light set uses darker,
        // saturated hues that hold contrast on light surfaces.
        if (scheme.background.luminance() < 0.5f) {
            HighlightColors(
                keyword = Color(0xFFC792EA),
                string = Color(0xFF9ECE6A),
                comment = Color(0xFF6B7394),
                number = Color(0xFFFF9E64),
                tag = Color(0xFFF7768E),
                attribute = Color(0xFFFFC46B),
                key = Color(0xFF7AA2F7),
                searchBackground = Color(0x66FFC46B),
            )
        } else {
            HighlightColors(
                keyword = Color(0xFF7C3AED),
                string = Color(0xFF15803D),
                comment = Color(0xFF64748B),
                number = Color(0xFFC2410C),
                tag = Color(0xFFBE123C),
                attribute = Color(0xFFB45309),
                key = Color(0xFF1D4ED8),
                searchBackground = Color(0x66F59E0B),
            )
        }
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
            addStyle(SpanStyle(background = colors.searchBackground), r.first, (r.last + 1).coerceAtMost(length))
        }
    }
}

/** Span style for a Tree-sitter highlight kind, from the same palettes as the regex highlighter. */
fun tsSpanStyle(kind: com.anvex.core.engine.codeintel.HighlightKind, colors: HighlightColors): SpanStyle =
    when (kind) {
        com.anvex.core.engine.codeintel.HighlightKind.KEYWORD -> SpanStyle(color = colors.keyword)
        com.anvex.core.engine.codeintel.HighlightKind.STRING -> SpanStyle(color = colors.string)
        com.anvex.core.engine.codeintel.HighlightKind.COMMENT ->
            SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
        com.anvex.core.engine.codeintel.HighlightKind.NUMBER -> SpanStyle(color = colors.number)
        com.anvex.core.engine.codeintel.HighlightKind.TYPE ->
            SpanStyle(color = colors.key, fontWeight = FontWeight.SemiBold)
        com.anvex.core.engine.codeintel.HighlightKind.FUNCTION -> SpanStyle(color = colors.attribute)
        com.anvex.core.engine.codeintel.HighlightKind.PROPERTY -> SpanStyle(color = colors.key)
        com.anvex.core.engine.codeintel.HighlightKind.CONSTANT -> SpanStyle(color = colors.number)
    }

/** Builds the styled text from Tree-sitter spans (char offsets into [text]). */
fun highlightWithTs(
    text: String,
    spans: List<com.anvex.core.engine.codeintel.HighlightSpan>,
    colors: HighlightColors,
    searchRanges: List<IntRange> = emptyList(),
): AnnotatedString = buildAnnotatedString {
    append(text)
    for (span in spans) {
        if (span.start in 0 until span.end && span.end <= text.length) {
            addStyle(tsSpanStyle(span.kind, colors), span.start, span.end)
        }
    }
    for (r in searchRanges) {
        addStyle(SpanStyle(background = colors.searchBackground), r.first, (r.last + 1).coerceAtMost(length))
    }
}

/**
 * The editor's unified transformation (F2a, P4): Tree-sitter spans when
 * they belong to the exact current text, the regex highlighter
 * otherwise, and — when [projection] is non-null — folded regions are
 * projected out of the visible text with the projection's offset
 * mapping (styles are carried over, clipped at fold boundaries).
 */
class EditorTransformation(
    private val language: CodeLanguage,
    private val colors: HighlightColors,
    private val searchRanges: List<IntRange> = emptyList(),
    private val tsSpans: List<com.anvex.core.engine.codeintel.HighlightSpan> = emptyList(),
    private val tsText: String? = null,
    private val projection: com.anvex.core.engine.codeintel.FoldProjection? = null,
) : VisualTransformation {

    private var cacheKey: String? = null
    private var cacheValue: TransformedText? = null

    override fun filter(text: AnnotatedString): TransformedText {
        if (cacheKey == text.text) cacheValue?.let { return it }
        val original = text.text
        val styled = if (tsText == original && tsSpans.isNotEmpty()) {
            highlightWithTs(original, tsSpans, colors, searchRanges)
        } else {
            highlight(original, language, colors, searchRanges)
        }
        val proj = projection
        val result = if (proj == null) {
            TransformedText(styled, OffsetMapping.Identity)
        } else {
            val visible = buildAnnotatedString {
                append(proj.visibleText)
                for (item in styled.spanStyles) {
                    val start = proj.originalToVisible(item.start)
                    val end = proj.originalToVisible(item.end)
                    if (end > start) addStyle(item.item, start, end)
                }
            }
            TransformedText(visible, object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = proj.originalToVisible(offset)
                override fun transformedToOriginal(offset: Int): Int = proj.visibleToOriginal(offset)
            })
        }
        cacheKey = original
        cacheValue = result
        return result
    }
}

private fun patternFor(language: CodeLanguage): Regex? = when (language) {
    CodeLanguage.JS, CodeLanguage.JSX -> jsPattern
    CodeLanguage.TS, CodeLanguage.TSX -> tsPattern
    CodeLanguage.HTML -> htmlPattern
    CodeLanguage.CSS -> cssPattern
    CodeLanguage.JSON -> jsonPattern
    CodeLanguage.MARKDOWN -> mdPattern
    CodeLanguage.KOTLIN -> ktPattern
    CodeLanguage.JAVA -> javaPattern
    CodeLanguage.PYTHON -> pyPattern
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

private const val KT_KW = """\b(?:fun|val|var|class|object|interface|data|sealed|enum|package|import|return|if|else|when|for|while|do|try|catch|finally|throw|in|is|as|break|continue|this|super|null|true|false|suspend|inline|reified|internal|private|protected|public|open|final|abstract|override|lateinit|by|companion|init|constructor|vararg|out|tailrec|operator|infix|external|expect|actual|typealias|annotation)\b"""

// Raw strings are matched first so a """ block is one string span, not
// three quote-delimited fragments. (The shared STR is NOT reused here:
// it is written to be interpolated inside quotes, as jsPattern does.)
private val ktPattern = Regex(
    "(?<comment>//[^\\n]*|/\\*(?s:.*?)\\*/)|" +
        "(?<str>\"\"\"(?s:.*?)\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')|" +
        "(?<num>\\b0[xX][0-9a-fA-F_]+\\b|\\b\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?\\d+)?[fFL]?\\b)|" +
        "(?<kw>$KT_KW)"
)

private const val JAVA_KW = """\b(?:abstract|assert|boolean|break|byte|case|catch|char|class|continue|default|do|double|else|enum|extends|final|finally|float|for|if|implements|import|instanceof|int|long|native|new|package|private|protected|public|record|return|short|static|super|switch|synchronized|this|throw|throws|transient|try|void|volatile|while|true|false|null|var|sealed|permits|yield)\b"""

// Java: annotations ride the `attr` group (the style table's accent slot).
// Text blocks (""") come before plain strings, as in the Kotlin pattern.
private val javaPattern = Regex(
    "(?<comment>//[^\\n]*|/\\*(?s:.*?)\\*/)|" +
        "(?<str>\"\"\"(?s:.*?)\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*')|" +
        "(?<attr>@\\w+)|" +
        "(?<num>\\b0[xX][0-9a-fA-F_]+\\b|\\b0[bB][01_]+\\b|\\b\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?[\\d_]+)?[fFdDlL]?\\b)|" +
        "(?<kw>$JAVA_KW)"
)

private const val PY_KW = """\b(?:False|None|True|and|as|assert|async|await|break|case|class|continue|def|del|elif|else|except|finally|for|from|global|if|import|in|is|lambda|match|nonlocal|not|or|pass|raise|return|try|while|with|yield)\b"""

// Python: `#` comments, decorators on `attr`, string prefixes (f/r/b/u)
// with triple-quoted forms first so a docstring stays one span.
private val pyPattern = Regex(
    "(?<comment>#[^\\n]*)|" +
        "(?<str>[fFrRbBuU]{0,2}\"\"\"(?s:.*?)\"\"\"|[fFrRbBuU]{0,2}'''(?s:.*?)'''|[fFrRbBuU]{0,2}\"(?:\\\\.|[^\"\\\\\\n])*\"|[fFrRbBuU]{0,2}'(?:\\\\.|[^'\\\\\\n])*')|" +
        "(?<attr>@[\\w.]+)|" +
        "(?<num>\\b0[xX][0-9a-fA-F_]+\\b|\\b0[oO][0-7_]+\\b|\\b0[bB][01_]+\\b|\\b\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?[\\d_]+)?[jJ]?\\b)|" +
        "(?<kw>$PY_KW)"
)
