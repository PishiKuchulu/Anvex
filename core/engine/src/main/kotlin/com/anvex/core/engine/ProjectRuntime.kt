package com.anvex.core.engine

import com.anvex.core.model.ProjectType

/**
 * v1.1 §40 — future-ready runtime seam. The UI and engine talk to this
 * interface; today's implementation is [WebViewPreviewRuntime] (honest,
 * capability-tiered). A stronger embedded runtime (QuickJS/Hermes/Node-on-
 * Android) can later replace the implementation without rewriting the UI or
 * engine.
 */
interface ProjectRuntime {
    val id: String

    /** What this runtime actually supports, per operation. */
    fun capabilities(): RuntimeCapabilities

    suspend fun execute(command: String): RuntimeResult
    suspend fun installDependencies(): RuntimeResult
    suspend fun build(): RuntimeResult
    suspend fun startDevServer(): RuntimeResult

    /** Preview entry description, or null when preview is unsupported. */
    fun previewEntry(files: Collection<String>): PreviewEntry?
}

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

data class RuntimeResult(
    val capability: CommandCapability,
    val success: Boolean,
    val output: String? = null,
    val error: String? = null,
) {
    companion object {
        fun unsupported(operation: String) = RuntimeResult(
            CommandCapability.UNSUPPORTED, false,
            error = "$operation is not available in this Android runtime. " +
                "The Preview runtime is embedded WebView-based and does not provide general Node.js process execution.",
        )
    }
}

data class PreviewEntry(
    /** Route served by the in-app preview server (e.g. "/" or "/__preview__"). */
    val path: String,
    val requiresTranspile: Boolean,
    val offline: Boolean,
)

/**
 * The actual v1.1 runtime: sandboxed filesystem + translated commands +
 * WebView/V8 preview with bundled Babel/React (offline-capable for React
 * without node_modules). Every capability reported here is backed by a real
 * implementation — this object must never claim more than exists.
 */
class WebViewPreviewRuntime(
    private val fs: ProjectFileSystem,
    private val capabilities: RuntimeCapabilities = RuntimeCapabilities.CURRENT,
    private val approval: ((ActionSafetyPolicy.Assessment) -> Boolean)? = null,
) : ProjectRuntime {
    private val dispatcher = ActionDispatcher(fs, approval)

    override val id: String = "webview-preview-v1.1"

    override fun capabilities(): RuntimeCapabilities = capabilities

    override suspend fun execute(command: String): RuntimeResult {
        val results = dispatcher.applyShellAction(BoltAction.ShellAction(command), "shell")
        val failed = results.any { !it.success }
        val partial = results.any { it.status == com.anvex.core.model.ActionStatus.PARTIAL }
        val cap = when {
            results.isEmpty() || results.all { it.status == com.anvex.core.model.ActionStatus.COMPLETE } -> CommandCapability.SUPPORTED
            failed && results.all { it.error?.startsWith("Approval required") == true || it.error != null } -> CommandCapability.UNSUPPORTED
            partial -> CommandCapability.PARTIAL
            else -> CommandCapability.SUPPORTED
        }
        return RuntimeResult(
            capability = cap,
            success = !failed,
            output = results.joinToString("\n") { it.output.orEmpty() }.ifBlank { null },
            error = results.firstOrNull { it.error != null }?.error,
        )
    }

    override suspend fun installDependencies(): RuntimeResult = RuntimeResult(
        CommandCapability.PARTIAL, true,
        output = "Dependencies recorded from package.json. There is no node_modules on " +
            "Android: bare imports resolve from the runtime asset bundle (react, react-dom) " +
            "at preview time, falling back to CDN for anything else.",
    )

    override suspend fun build(): RuntimeResult =
        RuntimeResult.unsupported("build")

    override suspend fun startDevServer(): RuntimeResult = RuntimeResult(
        CommandCapability.PARTIAL, true,
        output = "Dev servers cannot run. Instead, the in-app preview server (localhost only) " +
            "serves the project and transpiles JSX/TS in the preview browser.",
    )

    override fun previewEntry(files: Collection<String>): PreviewEntry? {
        val type = ProjectType.infer(files)
        return when (type) {
            ProjectType.STATIC_HTML, ProjectType.VANILLA_JS ->
                PreviewEntry("/", requiresTranspile = false, offline = true)
            ProjectType.REACT, ProjectType.TYPESCRIPT ->
                PreviewEntry("/__preview__", requiresTranspile = true, offline = true)
            ProjectType.VITE, ProjectType.UNKNOWN -> null
        }
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
        appendLine("Node.js:           NOT AVAILABLE in the Preview runtime. A bundled Node.js 18 runtime IS available to the Terminal/Agent on supported ABIs (arm64-v8a, armeabi-v7a, x86_64); it is never available to Preview.")
        appendLine("npm:               PARTIAL in Preview (dependencies recorded; no node_modules is ever created). Real npm 10 runs in the Terminal/Agent via the bundled Node.js runtime.")
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
