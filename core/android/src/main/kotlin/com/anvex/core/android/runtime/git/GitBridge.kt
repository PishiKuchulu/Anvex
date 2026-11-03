package com.anvex.core.android.runtime.git

/**
 * JNI bridge to libanvex-git.so (F1, P3): libgit2 statically linked for
 * LOCAL git operations. Failure-tolerant like the other bridges: when the
 * library is absent [available] is false and [GitRepository] reports
 * unavailability instead of crashing. All natives return strings in the
 * wire formats documented in git_bridge.cpp ("ERR: ..." on failure).
 */
object GitBridge {

    @Volatile
    private var loadAttempted = false

    @Volatile
    private var loaded = false

    val available: Boolean
        get() {
            if (!loadAttempted) {
                synchronized(this) {
                    if (!loadAttempted) {
                        loaded = try {
                            System.loadLibrary("anvex-git")
                            true
                        } catch (t: Throwable) {
                            false
                        }
                        loadAttempted = true
                    }
                }
            }
            return loaded
        }

    fun version(): String? =
        if (!available) null else try { nativeGitVersion() } catch (t: Throwable) { null }

    fun isRepo(path: String): Boolean =
        available && try { nativeIsRepo(path) } catch (t: Throwable) { false }

    fun call(fn: () -> String): String =
        if (!available) "ERR: git library unavailable" else try { fn() } catch (t: Throwable) { "ERR: ${t.message}" }

    external fun nativeGitVersion(): String
    external fun nativeIsRepo(path: String): Boolean
    external fun nativeInit(path: String): String
    external fun nativeStatus(path: String): String
    external fun nativeAdd(path: String, pathspec: String): String
    external fun nativeCommit(path: String, message: String, authorName: String, authorEmail: String): String
    external fun nativeLog(path: String, limit: Int): String
    external fun nativeBranches(path: String): String
    external fun nativeCreateBranch(path: String, name: String): String
    external fun nativeCheckout(path: String, name: String): String
    external fun nativeMerge(path: String, name: String): String
    external fun nativeConflicts(path: String): String
    external fun nativeConflictStages(path: String, conflictPath: String): Array<String>
    external fun nativeResolveFile(path: String, conflictPath: String, content: String): String
    external fun nativeAbortMerge(path: String): String
    external fun nativeDiff(path: String): String
}
