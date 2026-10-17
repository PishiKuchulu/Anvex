package com.anvex.core.android.runtime

import android.content.Context
import com.anvex.core.android.fs.RealProjectFileSystem
import com.anvex.core.android.repo.ProjectRepository
import java.io.File

/**
 * Single Android execution boundary used by durable agent tasks and terminal UI.
 * SAF workspaces deliberately do not expose a raw java.io.File to shell/Node.
 *
 * v4.4 additions: [nodeCompat] (in-app Node-compatibility layer on the WebView
 * JS engine) and [termux] (RUN_COMMAND bridge) so callers can honor the
 * NodeBackend setting without touching runtime internals.
 */
class AndroidRuntimeGateway(
    private val projects: ProjectRepository,
    private val context: Context? = null,
    /** Scratch workspace used by the project-less "global" terminal ([HOME_PROJECT_ID]). */
    private val homeDir: File? = null,
) {
    suspend fun shell(
        projectId: String,
        command: String,
        origin: ShellOrigin,
        approval: suspend (com.anvex.core.engine.ActionSafetyPolicy.Assessment) -> Boolean,
    ): ShellResult {
        val root = realRoot(projectId) ?: return ShellResult(false, "", "A real app-private workspace is required for shell execution.")
        return AndroidShellRuntime(root, approval).execute(command, origin = origin)
    }

    suspend fun node(
        projectId: String,
        arguments: List<String>,
        origin: ShellOrigin,
        approval: suspend (com.anvex.core.engine.ActionSafetyPolicy.Assessment) -> Boolean,
    ): NodeResult {
        val root = realRoot(projectId) ?: return NodeResult(false, "", "A real app-private workspace is required for Node execution.")
        return NodeRuntime(root, approval).execute(arguments, origin = origin)
    }

    suspend fun terminal(
        projectId: String,
        approval: suspend (com.anvex.core.engine.ActionSafetyPolicy.Assessment) -> Boolean,
    ): AndroidTerminalSession? {
        val root = realRoot(projectId) ?: return null
        return AndroidTerminalSession(root, approval)
    }

    /**
     * v4.4: in-app Node-compatibility layer (WebView JS engine, workspace-sandboxed).
     * Returns null for SAF workspaces or when no app context is wired.
     */
    suspend fun nodeCompat(projectId: String): NodeCompatRuntime? {
        val root = realRoot(projectId) ?: return null
        val ctx = context ?: return null
        return NodeCompatRuntime(ctx, root)
    }

    /** v4.4: Termux RUN_COMMAND bridge (needs the app context to resolve packages). */
    fun termux(): TermuxBridge? = context?.let { TermuxBridge(it) }

    suspend fun realRootDirectory(projectId: String): File? = realRoot(projectId)

    private suspend fun realRoot(projectId: String): File? {
        if (projectId == HOME_PROJECT_ID) return homeDir?.also { it.mkdirs() }
        val fs = projects.fileSystemForAsync(projectId)
        return (fs as? RealProjectFileSystem)?.rootDirectory()
    }

    companion object {
        /** Pseudo project id that opens the terminal in the app's own scratch directory. */
        const val HOME_PROJECT_ID = "__home__"
    }
}
