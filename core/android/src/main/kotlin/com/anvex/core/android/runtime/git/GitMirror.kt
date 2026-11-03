package com.anvex.core.android.runtime.git

import android.content.Context
import com.anvex.core.engine.ProjectFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * App-private git mirror for projects without a real filesystem path
 * (SAF workspaces) — F1's answer to ".git cannot live behind SAF": git
 * operations run on a plain directory under filesDir, and the user moves
 * content between the project and the mirror with explicit sync actions.
 *
 * Semantics are deliberately simple and visible:
 *  - [syncIn] makes the mirror's working files match the project
 *    (the mirror's own .git directory is never touched).
 *  - [syncOut] writes every mirrored working file back into the project
 *    (last-writer-wins; there is no second merge layer).
 * Both are bounded (file count + total bytes) so a huge project cannot
 * exhaust storage silently — hitting a bound fails the sync loudly.
 */
object GitMirror {

    private const val MAX_FILES = 2_000
    private const val MAX_BYTES = 64L * 1024 * 1024

    fun dir(context: Context, projectId: String): File =
        File(context.filesDir, "git-mirrors/$projectId").apply { mkdirs() }

    private fun isGitInternal(rel: String): Boolean =
        rel == ".git" || rel.startsWith(".git/")

    suspend fun syncIn(fs: ProjectFileSystem, mirror: File): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val paths = fs.walk().filter { !isGitInternal(it) }
            check(paths.size <= MAX_FILES) { "Project has ${paths.size} files (mirror limit $MAX_FILES)" }
            var total = 0L
            var count = 0
            val wanted = paths.toHashSet()
            for (rel in paths) {
                val bytes = fs.readFileBytes(rel) ?: continue
                total += bytes.size
                check(total <= MAX_BYTES) { "Project exceeds the ${MAX_BYTES / 1024 / 1024} MB mirror limit" }
                val out = File(mirror, rel)
                out.parentFile?.mkdirs()
                out.writeBytes(bytes)
                count++
            }
            // Drop mirror files the project no longer has (keeps status honest).
            mirror.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(mirror).invariantSeparatorsPath }
                .filter { !isGitInternal(it) && it !in wanted }
                .forEach { File(mirror, it).delete() }
            count
        }
    }

    suspend fun syncOut(fs: ProjectFileSystem, mirror: File): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val files = mirror.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(mirror).invariantSeparatorsPath }
                .filter { !isGitInternal(it) }
                .toList()
            check(files.size <= MAX_FILES) { "Mirror has ${files.size} files (limit $MAX_FILES)" }
            var total = 0L
            var count = 0
            for (rel in files) {
                val bytes = File(mirror, rel).readBytes()
                total += bytes.size
                check(total <= MAX_BYTES) { "Mirror exceeds the size limit" }
                fs.writeFileBytes(rel, bytes)
                count++
            }
            count
        }
    }

    /** Writes one mirrored file back to the project (used after a conflict resolution). */
    suspend fun syncOutFile(fs: ProjectFileSystem, mirror: File, relPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val f = File(mirror, relPath)
                check(f.isFile) { "Mirrored file missing: $relPath" }
                fs.writeFileBytes(relPath, f.readBytes())
                Unit
            }
        }
}
