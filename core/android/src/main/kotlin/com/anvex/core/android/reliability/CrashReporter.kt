package com.anvex.core.android.reliability

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash capture (2026-11-06: in-app problem/crash tracking).
 *
 * Until now the app recorded NOTHING when it crashed — there was no
 * uncaught-exception handler anywhere in the tree, so a fatal error
 * (e.g. the 4.15.0 first-frame crash) left no trace the user or a
 * diagnostic session could inspect afterwards.
 *
 * Design: the installed default handler writes the crash
 * SYNCHRONOUSLY to a small file in app-private storage — a Room write
 * cannot be trusted inside a dying process — then chains to the
 * previous handler so the OS still records the crash normally. On the
 * next launch, [importPending] moves the file into the diagnostic
 * event store as a category "crash" / level "ERROR" event (visible in
 * the Developer Center's Logs tab) and deletes the file.
 *
 * The format/parse/write helpers are pure `java.io` so they are
 * unit-testable on the JVM; only [install] touches Android APIs.
 */
object CrashReporter {
    private const val FILE_NAME = "last-crash.txt"
    private const val MAX_STACK_CHARS = 20_000
    private const val HEADER = "ANVEX-CRASH v1"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val dir = appContext.filesDir
        val version = packageVersion(appContext)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // The handler itself must never throw: any failure here is
            // swallowed and the previous handler still runs.
            runCatching {
                writeCrashFile(
                    dir,
                    formatCrash(
                        threadName = thread.name,
                        error = error,
                        versionName = version.first,
                        versionCode = version.second,
                        sdkInt = Build.VERSION.SDK_INT,
                        manufacturer = Build.MANUFACTURER ?: "",
                        model = Build.MODEL ?: "",
                        timeMs = System.currentTimeMillis(),
                    ),
                )
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    @Suppress("DEPRECATION") // versionCode is deprecated on API 28+ but is the only form that works on minSdk 26 without a compat dependency.
    private fun packageVersion(context: Context): Pair<String, Long> = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        (pi.versionName ?: "") to pi.versionCode.toLong()
    }.getOrDefault("" to 0L)

    /** Renders one crash report. Pure: every value arrives as a parameter. */
    fun formatCrash(
        threadName: String,
        error: Throwable,
        versionName: String,
        versionCode: Long,
        sdkInt: Int,
        manufacturer: String,
        model: String,
        timeMs: Long,
    ): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMs))
        return buildString {
            append(HEADER).append('\n')
            append("time: ").append(stamp).append(" (").append(timeMs).append(")\n")
            append("app: ").append(versionName).append(" (code ").append(versionCode).append(")\n")
            append("android: SDK ").append(sdkInt).append('\n')
            append("device: ").append(manufacturer).append(' ').append(model).append('\n')
            append("thread: ").append(threadName).append('\n')
            append("exception: ").append(error::class.java.name).append(": ").append(error.message ?: "").append('\n')
            append("stack:\n")
            append(trace.take(MAX_STACK_CHARS))
        }
    }

    fun crashFile(dir: File): File = File(dir, FILE_NAME)

    /** Synchronous write, exactly what a dying process can still do. */
    fun writeCrashFile(dir: File, text: String) {
        crashFile(dir).writeText(text)
    }

    /** The pending crash report's text, or null when the last run ended cleanly. */
    fun readPending(dir: File): String? {
        val file = crashFile(dir)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.startsWith(HEADER) }
    }

    /** The one-line exception summary an imported event is titled with. */
    fun exceptionSummary(reportText: String): String =
        reportText.lineSequence()
            .firstOrNull { it.startsWith("exception: ") }
            ?.removePrefix("exception: ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Unknown crash"

    /**
     * Next-launch import: hands the pending report to [log] (the
     * diagnostic store) and deletes the file ONLY after the write
     * succeeded, so a store failure retries on the following launch
     * instead of losing the only copy of the crash.
     */
    suspend fun importPending(context: Context, log: suspend (String, String, String, String?) -> Unit) =
        importPending(context.filesDir, log)

    /** [importPending] over an explicit directory (JVM-testable). */
    suspend fun importPending(dir: File, log: suspend (String, String, String, String?) -> Unit) {
        val text = readPending(dir) ?: return
        val stored = runCatching {
            log("crash", "ERROR", "Uncaught crash: ${exceptionSummary(text)}", text)
            true
        }.getOrDefault(false)
        if (stored) runCatching { crashFile(dir).delete() }
    }
}
