package com.anvex.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportDetectionTest {
    @Test fun `zip request is detected`() = assertTrue(UserIntentClassifier.requestsExport("make the app source as a zip"))
    @Test fun `give me the source is detected`() = assertTrue(UserIntentClassifier.requestsExport("give me the source as a file"))
    @Test fun `persian give me a file is detected`() = assertTrue(UserIntentClassifier.requestsExport("یه فایل بده"))
    @Test fun `how-to question is not an export`() = assertFalse(UserIntentClassifier.requestsExport("how do I make a zip file?"))
}
