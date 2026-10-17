package com.anvex.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Architect review item 3 — diagram artifact preview pages (mermaid + svg):
 * offline runtime wiring, safe source embedding, honest error UI, and the
 * inline-SVG sanitizer.
 */
class DiagramPreviewTest {

    private val evilMermaid = "graph TD\n A-->B</script><script>window.pwned=1</script>"

    @Test fun mermaidPageLoadsOnlyTheOfflineRuntime() {
        val html = PreviewHarness.generateMermaidPage("graph TD\n A-->B", "d.mmd")
        assertTrue(html.contains("src=\"${PreviewHarness.RUNTIME_ROUTE}${PreviewHarness.ASSET_MERMAID}\""))
        // Never a CDN: no external script/stylesheet references at all.
        assertFalse(html.contains("https://"))
        assertFalse(html.contains("http://"))
    }

    @Test fun mermaidPageEmbedsSourceAsEscapedLiteral() {
        val html = PreviewHarness.generateMermaidPage(evilMermaid, "x.mmd")
        // The literal </script> inside the diagram source must never terminate
        // the harness script element — jsStringLiteral escapes it.
        assertFalse(html.contains(evilMermaid))
        assertTrue(html.contains("\\u003C/script\\u003E"))
    }

    @Test fun mermaidPageUsesTheDarkThemeVariables() {
        val html = PreviewHarness.generateMermaidPage("graph TD\n A-->B", "d.mmd")
        listOf("#0b101e", "#06b6d4", "#f8fafc", "#38bdf8", "darkMode: true").forEach {
            assertTrue("missing theme variable $it", html.contains(it))
        }
    }

    @Test fun mermaidPageShowsHonestParseErrorUi() {
        val html = PreviewHarness.generateMermaidPage("graph TD\n A-->B", "d.mmd")
        assertTrue(html.contains("Diagram parse error"))
        // Parse errors post to the bridge (preview console) instead of hiding.
        assertTrue(html.contains("post(\"error\""))
        // Missing local runtime is disclosed, never silently blank.
        assertTrue(html.contains("mermaid === \"undefined\""))
        assertTrue(html.contains("Mermaid runtime failed to load"))
    }

    @Test fun mermaidSourceIsAlsoShownAsEscapedText() {
        val html = PreviewHarness.generateMermaidPage("flowchart LR\n A<hi>-->&amp;</hi>B", "d.mmd")
        // The <details> source block is HTML-escaped, so injected markup stays text.
        assertTrue(html.contains("&lt;hi&gt;"))
        assertFalse(html.contains("<hi>"))
    }

    @Test fun pathDispatchMatchesDiagramTypes() {
        assertTrue(PreviewHarness.isMermaidPath("docs/ARCH.mmd"))
        assertTrue(PreviewHarness.isMermaidPath("flow.Mermaid"))
        assertFalse(PreviewHarness.isMermaidPath("app.md"))
        assertTrue(PreviewHarness.isSvgPath("assets/logo.SVG"))
        assertFalse(PreviewHarness.isSvgPath("assets/logo.svgz"))
        assertTrue(PreviewHarness.isDiagramPath("a.mmd"))
        assertTrue(PreviewHarness.isDiagramPath("a.svg"))
        assertFalse(PreviewHarness.isDiagramPath("a.html"))
        // Unknown diagram extensions fall back to the mermaid renderer.
        assertTrue(PreviewHarness.generateDiagramPage("a.svg", "<svg/>").contains("Anvex SVG Preview"))
        assertTrue(PreviewHarness.generateDiagramPage("a.mmd", "graph TD").contains("Anvex Diagram Preview"))
    }

    @Test fun svgSanitizerRemovesScriptBlocks() {
        val dirty = "<svg><script>alert(1)</script><rect/></svg>"
        val clean = PreviewHarness.sanitizeInlineSvg(dirty)
        assertFalse(clean.markup.contains("<script"))
        assertFalse(clean.markup.contains("alert(1)"))
        assertTrue(clean.markup.contains("<rect/>"))
        assertTrue(clean.removedScriptBlocks == 1)
    }

    @Test fun svgSanitizerTruncatesAtUnterminatedScript() {
        val dirty = "<svg><rect/><script>alert(1)<svg>"
        val clean = PreviewHarness.sanitizeInlineSvg(dirty)
        assertFalse(clean.markup.contains("<script"))
        assertTrue(clean.markup.contains("<rect/>"))
    }

    @Test fun svgSanitizerRemovesEventHandlers() {
        val dirty = """<svg onload="pwn()" ><a onclick='x()'><text onbegin="y()">hi</text></a></svg>"""
        val clean = PreviewHarness.sanitizeInlineSvg(dirty)
        assertFalse(clean.markup.contains("onload"))
        assertFalse(clean.markup.contains("onclick"))
        assertFalse(clean.markup.contains("onbegin"))
        assertTrue(clean.markup.contains("<text"))
        assertTrue(clean.removedEventHandlers >= 3)
    }

    @Test fun svgSanitizerRemovesUnsafeUrlsButKeepsSafeOnes() {
        val dirty = "<svg>" +
            "<image href=\"http://evil.example/pixel.png\"/>" +
            "<image xlink:href=\"https://evil.example/2.png\"/>" +
            "<a href=\"javascript:alert(1)\">x</a>" +
            "<a href=\"&#106;avascript&#58;alert(2)\">obf</a>" +
            "<image src=\"data:image/png;base64,AAAA\"/>" +
            "<a href=\"#section\">frag</a>" +
            "<image href=\"img/pic.png\"/>" +
            "<use href=\"lib.svg#sym\"/>" +
            "</svg>"
        val clean = PreviewHarness.sanitizeInlineSvg(dirty)
        val markup = clean.markup
        assertFalse(markup.contains("evil.example"))
        assertFalse(markup.contains("javascript:"))
        assertFalse(markup.contains("&#106;avascript"))
        assertTrue(markup.contains("data:image/png;base64,AAAA"))
        assertTrue(markup.contains("#section"))
        assertTrue(markup.contains("img/pic.png"))
        assertTrue(markup.contains("lib.svg#sym"))
        assertTrue(clean.removedExternalReferences >= 4)
    }

    @Test fun svgSanitizerNeutralizesExternalCssReferences() {
        val dirty = "<svg><style>@import url(https://evil.example/x.css); .k{background:url(//evil.example/t.png)}</style><rect/></svg>"
        val clean = PreviewHarness.sanitizeInlineSvg(dirty)
        assertFalse(clean.markup.contains("evil.example"))
        assertTrue(clean.markup.contains("background:none"))
        assertTrue(clean.markup.contains("<rect/>"))
        assertTrue(clean.removedExternalReferences >= 2)
    }

    @Test fun svgPageIsScriptFreeAndSandboxed() {
        val dirty = "<svg onload=\"pwn()\"><script>alert(1)</script><image href=\"http://evil.example/x\"/></svg>"
        val html = PreviewHarness.generateSvgPage(dirty, "d.svg")
        // No script element at all, strict CSP, and the sanitizer's removal is
        // disclosed instead of silently altering the file preview.
        assertFalse(html.contains("<script"))
        assertTrue(html.contains("Content-Security-Policy"))
        assertTrue(html.contains("default-src 'none'"))
        assertFalse(html.contains("onload"))
        assertFalse(html.contains("evil.example"))
        assertTrue(html.contains("Sanitized"))
        // Honest capability disclosure for the sandbox.
        assertTrue(html.contains("Scripts and external resources are disabled"))
    }

    @Test fun svgPageKeepsCleanMarkupUntouched() {
        val cleanSvg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"10\" height=\"10\"/></svg>"
        val html = PreviewHarness.generateSvgPage(cleanSvg, "d.svg")
        assertTrue(html.contains("<rect width=\"10\" height=\"10\"/>"))
        // Nothing was removed — no sanitization notice is shown.
        assertFalse(html.contains("Sanitized"))
    }
}
