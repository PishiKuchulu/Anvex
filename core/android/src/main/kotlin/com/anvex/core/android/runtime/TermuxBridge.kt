package com.anvex.core.android.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * v4.4: bridge to the Termux RUN_COMMAND service.
 *
 * Sends a command to Termux (com.termux) through its RunCommandService so
 * heavy tooling — real Gradle, python, node, npm, git — can run on the phone
 * CPU inside Termux's proper Linux environment. Anvex only *sends* the intent:
 * execution and its output happen inside Termux, and the UI reports exactly
 * that ("sent to Termux"). Nothing is forged, no output is invented, and no
 * result is claimed unless Termux actually ran the command.
 *
 * Requirements (reported honestly by [availability]):
 *  - Termux installed and the "Allow external apps" toggle enabled in Termux,
 *  - this app holds the RUN_COMMAND permission (declared in the manifest),
 *  - Termux must expose its RunCommandService (component presence is checked).
 */
class TermuxBridge(private val context: Context) {

    data class Availability(
        val available: Boolean,
        val termuxInstalled: Boolean,
        val serviceResolvable: Boolean,
        val reason: String?,
    )

    /** Candidate components, newest layout first (Termux app owns RUN_COMMAND now). */
    private val components = listOf(
        ComponentName(TERMUX_PACKAGE, "$TERMUX_PACKAGE.app.RunCommandService"),
        ComponentName("com.termux.api", "$TERMUX_PACKAGE.app.RunCommandService"),
    )

    fun availability(): Availability {
        val pm = context.packageManager
        val installed = runCatching {
            pm.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        }.getOrDefault(false)
        if (!installed) {
            return Availability(false, false, false, "Termux is not installed.")
        }
        val resolvable = components.any { component ->
            runCatching {
                pm.getServiceInfo(component, 0)
                true
            }.getOrDefault(false)
        }
        if (!resolvable) {
            return Availability(
                false, true, false,
                "Termux is installed but its RunCommand service is not reachable. Enable \"Allow external apps\" in Termux properties.",
            )
        }
        return Availability(true, true, true, null)
    }

    /**
     * Send a command to Termux. [executable] must be an absolute path inside
     * the Termux prefix (e.g. /data/data/com.termux/files/usr/bin/node).
     * Returns true when the intent was accepted by the OS — this does NOT
     * mean the command succeeded; output stays inside Termux.
     */
    fun send(workDir: String, executable: String, arguments: List<String>): Boolean {
        val availability = availability()
        if (!availability.available) return false
        if (arguments.size > 128 || arguments.any { it.length > 8_000 }) return false
        val command = buildString {
            append(executable)
            arguments.forEach { arg ->
                append(' ')
                if (arg.contains(Regex("""[^\w@%+=:,./-]"""))) {
                    append('\'').append(arg.replace("'", "'\\''")).append('\'')
                } else {
                    append(arg)
                }
            }
        }
        val intent = Intent(ACTION_RUN_COMMAND).apply {
            setPackage(TERMUX_PACKAGE)
            putExtra(EXTRA_RUN_COMMAND_PATH, SHELL)
            putExtra(EXTRA_RUN_COMMAND_ARGUMENTS, arrayOf("-c", command))
            putExtra(EXTRA_RUN_COMMAND_WORKDIR, workDir.ifBlank { TERMUX_PREFIX })
            putExtra(EXTRA_RUN_COMMAND_BACKGROUND, false)
            putExtra(EXTRA_RUN_COMMAND_COMMAND_LABEL, "Anvex bridge")
            putExtra(EXTRA_RUN_COMMAND_DESCRIPTION, "Command sent from the Anvex workspace")
        }
        return runCatching {
            context.startService(intent)
            true
        }.getOrDefault(false)
    }

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        const val SHELL = "$TERMUX_PREFIX/bin/sh"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_RUN_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_RUN_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_RUN_COMMAND_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        private const val EXTRA_RUN_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_DESCRIPTION"
    }
}
