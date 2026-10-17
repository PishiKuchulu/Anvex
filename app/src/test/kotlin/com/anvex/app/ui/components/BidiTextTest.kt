package com.anvex.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BidiTextTest {
    @Test fun `Persian text is detected as RTL`() = assertTrue(isRtlText("سلام دنیا"))
    @Test fun `Arabic text is detected as RTL`() = assertTrue(isRtlText("مرحبا بالعالم"))
    @Test fun `English text is detected as LTR`() = assertFalse(isRtlText("Hello world"))
    @Test fun `code path remains LTR`() = assertFalse(isRtlText("src/main/kotlin/App.kt"))
    @Test fun `leading punctuation does not defeat RTL detection`() = assertTrue(isRtlText("📌 سلام"))
    @Test fun `leading digits do not defeat RTL detection`() = assertTrue(isRtlText("123 - سلام"))
    @Test fun `mixed Persian with latin code stays paragraph RTL`() = assertTrue(isRtlText("سلام App.kt را باز کن"))
    @Test fun `mixed latin with Persian keeps first strong direction`() = assertFalse(isRtlText("Open سلام settings"))
}
