package com.anvex.app.ui.screens.git

import com.anvex.core.android.AppGraph
import java.io.File

/** Where a project's git operations run: its real root, or the SAF mirror. */
class GitRoot(val dir: File, val mirrored: Boolean)

/**
 * Resolves the working directory for git: the project's real root when it
 * has one (app-private storage), otherwise the app-private mirror that the
 * user syncs explicitly. Null only when neither exists (no context).
 */
suspend fun resolveGitRoot(graph: AppGraph, projectId: String): GitRoot? {
    val real = graph.androidRuntime.realRootDirectory(projectId)
    if (real != null) return GitRoot(real, mirrored = false)
    val mirror = graph.androidRuntime.gitMirrorDirectory(projectId) ?: return null
    return GitRoot(mirror, mirrored = true)
}
