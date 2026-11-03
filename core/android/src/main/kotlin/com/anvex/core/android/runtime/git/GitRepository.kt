package com.anvex.core.android.runtime.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One porcelain status entry: index/workdir state chars ('.', 'M', 'A', 'D', 'R', '?'). */
data class GitStatusEntry(val index: Char, val workdir: Char, val path: String) {
    val isConflicted: Boolean get() = index == 'U' || workdir == 'U'
    val isUntracked: Boolean get() = workdir == '?'
}

data class GitCommitInfo(
    val oid: String,
    val authorName: String,
    val authorEmail: String,
    val epochSeconds: Long,
    val subject: String,
)

data class GitBranchInfo(val name: String, val current: Boolean, val oid: String)

/** The three stages of one conflicted file (null = stage absent, e.g. deleted by them). */
data class GitConflictStages(val ancestor: String?, val ours: String?, val theirs: String?)

enum class GitMergeResult { FAST_FORWARD, UP_TO_DATE, CLEAN, CONFLICT }

/**
 * Typed facade over [GitBridge] (F1, P3). All calls run on IO; the bridge
 * is string-typed, parsing and error mapping live here so the UI never sees
 * the wire format. Git failures surface as [Result] failures whose message
 * carries libgit2's own error text — no silent swallowing.
 */
class GitRepository(private val rootPath: String) {

    val isRepo: Boolean get() = GitBridge.isRepo(rootPath)

    private fun ok(result: String): Result<String> =
        if (result.startsWith("ERR:")) Result.failure(IllegalStateException(result.removePrefix("ERR:").trim()))
        else Result.success(result)

    suspend fun init(): Result<Unit> = withContext(Dispatchers.IO) {
        ok(GitBridge.call { GitBridge.nativeInit(rootPath) }).map { }
    }

    suspend fun status(): Result<List<GitStatusEntry>> = withContext(Dispatchers.IO) {
        ok(GitBridge.call { GitBridge.nativeStatus(rootPath) }).map { raw ->
            raw.lineSequence().filter { it.isNotBlank() }.map { line ->
                val tab = line.indexOf('\t')
                val codes = if (tab >= 0) line.substring(0, tab) else "??"
                GitStatusEntry(
                    index = codes.getOrElse(0) { '?' },
                    workdir = codes.getOrElse(1) { '?' },
                    path = if (tab >= 0) line.substring(tab + 1) else line,
                )
            }.toList()
        }
    }

    suspend fun add(pathspec: String = "."): Result<Unit> = withContext(Dispatchers.IO) {
        ok(GitBridge.call { GitBridge.nativeAdd(rootPath, pathspec) }).map { }
    }

    /** Creates a commit from the index; returns the new commit oid. */
    suspend fun commit(message: String, authorName: String, authorEmail: String): Result<String> =
        withContext(Dispatchers.IO) {
            ok(GitBridge.call { GitBridge.nativeCommit(rootPath, message, authorName, authorEmail) })
        }

    suspend fun log(limit: Int = 100): Result<List<GitCommitInfo>> = withContext(Dispatchers.IO) {
        ok(GitBridge.call { GitBridge.nativeLog(rootPath, limit) }).map { raw ->
            raw.lineSequence().filter { it.isNotBlank() }.map { line ->
                val f = line.split('\t')
                GitCommitInfo(
                    oid = f.getOrElse(0) { "" },
                    authorName = f.getOrElse(1) { "" },
                    authorEmail = f.getOrElse(2) { "" },
                    epochSeconds = f.getOrElse(3) { "0" }.toLongOrNull() ?: 0,
                    subject = f.getOrElse(4) { "" },
                )
            }.toList()
        }
    }

    suspend fun branches(): Result<List<GitBranchInfo>> = withContext(Dispatchers.IO) {
        ok(GitBridge.call { GitBridge.nativeBranches(rootPath) }).map { raw ->
            raw.lineSequence().filter { it.isNotBlank() }.map { line ->
                val f = line.split('\t')
                GitBranchInfo(
                    name = f.getOrElse(0) { "" },
                    current = f.getOrElse(1) { "0" } == "1",
                    oid = f.getOrElse(2) { "" },
                )
            }.toList()
        }
    }

    suspend fun createBranch(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        ok(GitBridge.call { GitBridge.nativeCreateBranch(rootPath, name) }).map { }
    }

    suspend fun checkout(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        ok(GitBridge.call { GitBridge.nativeCheckout(rootPath, name) }).map { }
    }

    suspend fun merge(branch: String): Result<GitMergeResult> = withContext(Dispatchers.IO) {
        ok(GitBridge.call { GitBridge.nativeMerge(rootPath, branch) }).map { raw ->
            when (raw.trim()) {
                "FASTFORWARD" -> GitMergeResult.FAST_FORWARD
                "UP_TO_DATE" -> GitMergeResult.UP_TO_DATE
                "CLEAN" -> GitMergeResult.CLEAN
                else -> GitMergeResult.CONFLICT
            }
        }
    }

    suspend fun conflicts(): Result<List<String>> = withContext(Dispatchers.IO) {
        ok(GitBridge.call { GitBridge.nativeConflicts(rootPath) }).map { raw ->
            raw.lineSequence().filter { it.isNotBlank() }.toList()
        }
    }

    suspend fun conflictStages(conflictPath: String): Result<GitConflictStages> = withContext(Dispatchers.IO) {
        if (!GitBridge.available) {
            Result.failure(IllegalStateException("git library unavailable"))
        } else {
            try {
                val arr = GitBridge.nativeConflictStages(rootPath, conflictPath)
                Result.success(
                    GitConflictStages(
                        ancestor = arr.getOrNull(0)?.takeIf { it.isNotEmpty() },
                        ours = arr.getOrNull(1)?.takeIf { it.isNotEmpty() },
                        theirs = arr.getOrNull(2)?.takeIf { it.isNotEmpty() },
                    )
                )
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }

    /** Writes [content] as the resolved file and stages it, clearing its conflict. */
    suspend fun resolveFile(conflictPath: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        ok(GitBridge.call { GitBridge.nativeResolveFile(rootPath, conflictPath, content) }).map { }
    }

    suspend fun abortMerge(): Result<Unit> = withContext(Dispatchers.IO) {
        ok(GitBridge.call { GitBridge.nativeAbortMerge(rootPath) }).map { }
    }

    /** Unified patch HEAD → workdir (staged + unstaged); blank when clean or unborn. */
    suspend fun diff(): Result<String> = withContext(Dispatchers.IO) {
        ok(GitBridge.call { GitBridge.nativeDiff(rootPath) })
    }
}
