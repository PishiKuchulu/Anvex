package com.anvex.core.android.reliability

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * CrashReporter (2026-11-06): the pure file/format half of the crash
 * pipeline — the handler install itself is Android-only and stays
 * untested here, exactly like the other runtime bindings.
 */
class CrashReporterTest {

    private fun sampleReport(): String = CrashReporter.formatCrash(
        threadName = "main",
        error = IllegalStateException("boom happened"),
        versionName = "4.15.4",
        versionCode = 103,
        sdkInt = 35,
        manufacturer = "Xiaomi",
        model = "M2012K11AG",
        timeMs = 1_762_000_000_000,
    )

    @Test
    fun `report carries header version device and exception lines`() {
        val text = sampleReport()
        assertTrue(text.startsWith("ANVEX-CRASH v1"))
        assertTrue(text.contains("app: 4.15.4 (code 103)"))
        assertTrue(text.contains("android: SDK 35"))
        assertTrue(text.contains("device: Xiaomi M2012K11AG"))
        assertTrue(text.contains("thread: main"))
        assertTrue(text.contains("exception: java.lang.IllegalStateException: boom happened"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom happened"))
    }

    @Test
    fun `exception summary is the exception line`() {
        assertEquals(
            "java.lang.IllegalStateException: boom happened",
            CrashReporter.exceptionSummary(sampleReport()),
        )
        assertEquals("Unknown crash", CrashReporter.exceptionSummary("ANVEX-CRASH v1\nnothing else"))
    }

    @Test
    fun `pending round trip and foreign files are ignored`() {
        val dir = Files.createTempDirectory("anvex-crash-test").toFile()
        assertNull(CrashReporter.readPending(dir))
        // One captured instance: the embedded stack trace names the
        // calling line, so regenerating the report would not compare equal.
        val report = sampleReport()
        CrashReporter.writeCrashFile(dir, report)
        assertEquals(report, CrashReporter.readPending(dir))
        // A file that is not one of ours must not be imported as a crash.
        CrashReporter.writeCrashFile(dir, "someone else's file")
        assertNull(CrashReporter.readPending(dir))
        dir.deleteRecursively()
    }

    @Test
    fun `import stores the crash and deletes the file`() {
        runBlocking {
        val dir = Files.createTempDirectory("anvex-crash-test").toFile()
        val report = sampleReport()
        CrashReporter.writeCrashFile(dir, report)
        var captured: List<String?>? = null
        CrashReporter.importPending(dir) { category, level, message, details ->
            captured = listOf(category, level, message, details)
        }
        assertEquals("crash", captured?.get(0))
        assertEquals("ERROR", captured?.get(1))
        assertTrue(captured?.get(2)?.startsWith("Uncaught crash: java.lang.IllegalStateException") == true)
        assertEquals(report, captured?.get(3))
        assertFalse("file must be deleted after a successful import", CrashReporter.crashFile(dir).exists())
        dir.deleteRecursively()
        }
    }

    @Test
    fun `import keeps the file when the store write fails`() {
        runBlocking {
        val dir = Files.createTempDirectory("anvex-crash-test").toFile()
        CrashReporter.writeCrashFile(dir, sampleReport())
        CrashReporter.importPending(dir) { _, _, _, _ -> throw RuntimeException("db down") }
        assertTrue("a failed import must not lose the only copy of the crash", CrashReporter.crashFile(dir).exists())
        dir.deleteRecursively()
        }
    }

    @Test
    fun `import without a pending crash does nothing`() {
        runBlocking {
        val dir = Files.createTempDirectory("anvex-crash-test").toFile()
        var called = false
        CrashReporter.importPending(dir) { _, _, _, _ -> called = true }
        assertFalse(called)
        dir.deleteRecursively()
        }
    }
}
