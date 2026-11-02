package com.anvex.core.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentDetectionTest {

    @Test
    fun englishIntentAnnouncementsMatch() {
        assertTrue(looksLikeIntentOnly("I will create the project files and then run the build."))
        assertTrue(looksLikeIntentOnly("Let me start by setting up the folder structure."))
        assertTrue(looksLikeIntentOnly("Here's my plan: first the model, then the UI."))
        assertTrue(looksLikeIntentOnly("I'm going to add the settings screen now."))
    }

    @Test
    fun persianIntentAnnouncementsMatchWithAndWithoutZwnj() {
        // With ZWNJ (U+200C) and spelled with a plain space instead.
        assertTrue(looksLikeIntentOnly("حالا فایل‌ها را ایجاد می‌کنم و بعد بیلد می‌گیرم."))
        assertTrue(looksLikeIntentOnly("این کار را انجام میدم و نتیجه را گزارش می‌کنم."))
        assertTrue(looksLikeIntentOnly("اول ساختار پروژه را میسازم."))
    }

    @Test
    fun plainAnswersAndSummariesDoNotMatch() {
        assertFalse(looksLikeIntentOnly("The capital of France is Paris."))
        assertFalse(looksLikeIntentOnly("Done — the screen is implemented and the tests pass."))
        assertFalse(looksLikeIntentOnly(""))
        assertFalse(looksLikeIntentOnly("   "))
    }

    @Test
    fun veryLongTextsDoNotMatch() {
        val long = "I will " + "x".repeat(4_100)
        assertFalse(looksLikeIntentOnly(long))
    }

    @Test
    fun markupOnlyRepliesAreDetected() {
        assertTrue(isMarkupOnlyOrEmpty(""))
        assertTrue(isMarkupOnlyOrEmpty("   \n  "))
        assertTrue(isMarkupOnlyOrEmpty("<tool_results>"))
        assertTrue(isMarkupOnlyOrEmpty("<tool_results>\n</tool_results>"))
        assertTrue(isMarkupOnlyOrEmpty("</tool_result>"))
        assertTrue(isMarkupOnlyOrEmpty("  <TOOL_RESULTS>  "))
    }

    @Test
    fun markupMixedWithRealProseIsARealAnswer() {
        assertFalse(isMarkupOnlyOrEmpty("Here is what the tool returned: <tool_results> ok"))
        assertFalse(isMarkupOnlyOrEmpty("<tool_results> The build passed."))
        assertFalse(isMarkupOnlyOrEmpty("Done — the report is in REPORT.md."))
    }

    @Test
    fun userReportedPersianNarrationMatchesIntent() {
        // The exact shape from the 2026-11-02 report: plan narration that
        // previously matched no pattern, followed by an alien tool call.
        assertTrue(looksLikeIntentOnly("مرحله‌به‌مرحله پیش می‌رم: اول ساختار کامل پروژه را می‌خوانم، بعد فایل‌های کلیدی را بررسی می‌کنم و در پایان گزارش را تحویل می‌دم."))
    }

    @Test
    fun alienToolDialectIsDetectedAndStripped() {
        val round = "اول ساختار پروژه را می‌خوانم.\nopen|>tools<|sep|><|open|>call tool=\"boltArtifact\"|>" +
            "index=\"1\"<|sep|><|open|>argument key=\"id\" type=\"string\"<|sep|>research<|close|>argument"
        assertTrue(containsUnparsedToolMarkup(round))
        assertEquals("اول ساختار پروژه را می‌خوانم.", stripUnparsedToolMarkup(round))
        assertFalse(containsUnparsedToolMarkup("Done — the report is written."))
        assertFalse(containsUnparsedToolMarkup("Use a <boltArtifact> block to create the file."))
    }
}
