package com.anvex.core.engine.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightDiagnosticsTest {

    @Test
    fun `unbalanced json braces are errors`() {
        val result = LightDiagnostics.analyze("config.json", """{"a": {"b": 1}""")
        assertTrue(result.diagnostics.any { it.severity == LightDiagnostics.Severity.ERROR && it.message.contains("Unclosed") })
    }

    @Test
    fun `valid json is clean except todos`() {
        val result = LightDiagnostics.analyze("config.json", """{"a": 1, "b": [2, 3]}""")
        assertTrue("diag=" + result.diagnostics, result.diagnostics.isEmpty())
    }

    @Test
    fun `html mismatched tag is reported`() {
        val html = "<html><body><div></span></body></html>"
        val result = LightDiagnostics.analyze("index.html", html)
        assertTrue(result.diagnostics.any { it.severity == LightDiagnostics.Severity.ERROR && it.message.contains("Mismatched") })
    }

    @Test
    fun `html void tags do not require closing`() {
        val html = "<!doctype html><html><body><br><img src=\"x.png\"></body></html>"
        val result = LightDiagnostics.analyze("index.html", html)
        assertTrue(result.diagnostics.none { it.severity == LightDiagnostics.Severity.ERROR })
    }

    @Test
    fun `js brackets inside strings and comments are ignored`() {
        val js = """
            // this comment has { unbalanced ( braces
            const s = "with } brackets { inside";
            function f() { return [1, 2]; }
        """.trimIndent()
        val result = LightDiagnostics.analyze("app.js", js)
        assertTrue(result.diagnostics.none { it.severity == LightDiagnostics.Severity.ERROR })
    }

    @Test
    fun `js unbalanced parens are errors`() {
        val js = "function f( { return 1; }"
        val result = LightDiagnostics.analyze("app.js", js)
        assertTrue(result.diagnostics.any { it.severity == LightDiagnostics.Severity.ERROR })
    }

    @Test
    fun `todos surface as info diagnostics with line numbers`() {
        val js = "const a = 1;\n// TODO: implement\nconst b = 2;"
        val result = LightDiagnostics.analyze("app.js", js)
        val todo = result.diagnostics.first { it.message.startsWith("TODO") }
        assertEquals(2, todo.line)
    }

    @Test
    fun `duplicate top level json keys warn`() {
        val json = """{"name": "a", "name": "b"}"""
        val result = LightDiagnostics.analyze("x.json", json)
        assertTrue(result.diagnostics.any { it.severity == LightDiagnostics.Severity.WARNING && it.message.contains("Duplicate") })
    }

    @Test
    fun `css unclosed block is error`() {
        val result = LightDiagnostics.analyze("style.css", ".a { color: red;")
        assertTrue(result.diagnostics.any { it.severity == LightDiagnostics.Severity.ERROR && it.message.contains("Unclosed '{'") })
    }
}
