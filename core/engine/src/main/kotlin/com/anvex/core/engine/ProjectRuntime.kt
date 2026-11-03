package com.anvex.core.engine

/**
 * The Preview runtime's capability matrix and the capability-aware system
 * prompt built from it.
 *
 * History: this file also carried a `ProjectRuntime` interface seam and
 * its `WebViewPreviewRuntime` implementation. Neither had a production
 * consumer — the live paths are the dispatcher (chat/agent actions), the
 * orchestrator, and the preview server in core:android — so the seam was
 * removed (audit 2026-11-03). The capability table itself is live:
 * Agent Center renders it and [RuntimeCapabilityReport] feeds the system
 * prompt, so it must keep telling the truth about the Preview runtime.
 */

/** Capability tri-state used across the engine, prompts, and UI. */
enum class CommandCapability { SUPPORTED, PARTIAL, UNSUPPORTED }

data class RuntimeCapabilities(
    val filesystem: CommandCapability,
    val fileEditing: CommandCapability,
    val staticHtmlPreview: CommandCapability,
    val javaScript: CommandCapability,
    val jsxTranspile: CommandCapability,
    val reactPreview: CommandCapability,
    val offlineReactPreview: CommandCapability,
    val nodeJs: CommandCapability,
    val npm: CommandCapability,
    val vite: CommandCapability,
    val shell: CommandCapability,
    val typescript: CommandCapability,
) {
    companion object {
        /**
         * The real capabilities of the v1.1 WebView-based PREVIEW runtime —
         * this table describes Preview/Chat command translation ONLY. It is
         * NOT the device capability matrix: the bundled Node.js runtime
         * (Terminal/Agent) is reported by
         * `NodeMobileRuntime.isBundledAvailable()` in core:android. (v4.6.1:
         * the Agent Center matrix once read this table directly and showed
         * Node.js "Unavailable" on devices where the bundled runtime works.)
         */
        val CURRENT = RuntimeCapabilities(
            filesystem = CommandCapability.SUPPORTED,
            fileEditing = CommandCapability.SUPPORTED,
            staticHtmlPreview = CommandCapability.SUPPORTED,
            javaScript = CommandCapability.SUPPORTED,
            jsxTranspile = CommandCapability.SUPPORTED,       // bundled @babel/standalone
            reactPreview = CommandCapability.SUPPORTED,       // bundled React UMD + wrappers
            offlineReactPreview = CommandCapability.SUPPORTED, // v1.1: local runtime assets
            nodeJs = CommandCapability.UNSUPPORTED,
            npm = CommandCapability.PARTIAL,                  // recorded, resolved at preview
            vite = CommandCapability.UNSUPPORTED,             // no bundler/dev server
            shell = CommandCapability.PARTIAL,                // whitelisted translation only
            typescript = CommandCapability.PARTIAL,            // type-stripping only
        )
    }
}

/**
 * v1.1 §28 — single source of truth for the capability-aware system prompt.
 * The AI sees exactly what the runtime can do; the matrix is generated from
 * [RuntimeCapabilities.CURRENT] and [CommandTranslator.capabilityMatrix], so
 * prompt text can never drift from actual behavior.
 */
object RuntimeCapabilityReport {

    fun describe(): String = buildString {
        appendLine("Android Runtime Capabilities (the environment you are actually running in):")
        appendLine()
        appendLine("Filesystem:        SUPPORTED")
        appendLine("File editing:      SUPPORTED")
        appendLine("Static HTML:       SUPPORTED (fully offline preview)")
        appendLine("JavaScript:        SUPPORTED (executes in the preview browser)")
        appendLine("JSX/TSX transpile: SUPPORTED (bundled Babel, offline)")
        appendLine("React preview:     SUPPORTED (React/ReactDOM bundled offline; other bare imports resolve from CDN)")
        appendLine("Node.js:           NOT AVAILABLE in the Preview runtime. A bundled Node.js 24 runtime IS available to the Terminal/Agent on supported ABIs (arm64-v8a, armeabi-v7a, x86_64); it is never available to Preview.")
        appendLine("npm:               PARTIAL in Preview (dependencies recorded; no node_modules is ever created). Real npm 11 runs in the Terminal/Agent via the bundled Node.js runtime.")
        appendLine("Vite:              NOT AVAILABLE (no bundler or dev server)")
        appendLine("TypeScript:        PARTIAL (type stripping only, no type checking)")
        appendLine("Shell:             SANDBOXED / LIMITED (whitelisted file commands translated, no processes)")
        appendLine()
        appendLine("Translatable shell commands:")
        CommandTranslator.capabilityMatrix
            .filter { it.second != CommandTranslator.Tier.UNSUPPORTED }
            .forEach { (name, tier) ->
                appendLine("- $name: ${if (tier == CommandTranslator.Tier.SUPPORTED) "SUPPORTED" else "PARTIAL"}")
            }
        appendLine()
        appendLine("NOT executable (do not generate these as required steps):")
        CommandTranslator.capabilityMatrix
            .filter { it.second == CommandTranslator.Tier.UNSUPPORTED }
            .forEach { (name, _) -> appendLine("- $name") }
    }
}
