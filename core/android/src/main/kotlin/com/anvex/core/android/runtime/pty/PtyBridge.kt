package com.anvex.core.android.runtime.pty

/**
 * JNI bridge to libanvex-pty.so (F3, P2): real forkpty() terminal sessions.
 *
 * Failure-tolerant by contract: if the library is missing (partial install,
 * unsupported ABI), [available] is false and [PtySession] declines to start
 * so the terminal falls back to the legacy pipe session — a terminal that
 * works beats a feature that crashes. The native side is a transport only:
 * which commands may run and the approval gates stay in the Kotlin policy
 * layer, exactly like the pipe path.
 */
object PtyBridge {

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
                            System.loadLibrary("anvex-pty")
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

    /** Bridge ABI version (1 = P0 probe, 2 = full PTY surface); -1 when unavailable. */
    fun bridgeVersion(): Int =
        if (!available) -1 else try { nativePtyVersion() } catch (t: Throwable) { -1 }

    /** [masterFd, childPid] or an empty array when the fork failed. */
    fun create(shellPath: String, cwd: String, env: Array<String>, rows: Int, cols: Int): IntArray =
        if (!available) intArrayOf()
        else try {
            nativeCreate(shellPath, cwd, env, rows, cols)
        } catch (t: Throwable) {
            intArrayOf()
        }

    /** Blocking read; returns bytes read or -1 on EOF/error. Call on an IO thread only. */
    fun read(fd: Int, buffer: ByteArray): Int =
        try { nativeRead(fd, buffer) } catch (t: Throwable) { -1 }

    fun write(fd: Int, data: ByteArray): Int =
        try { nativeWrite(fd, data) } catch (t: Throwable) { -1 }

    fun resize(fd: Int, rows: Int, cols: Int) {
        try { nativeResize(fd, rows, cols) } catch (t: Throwable) { /* best-effort */ }
    }

    /** True when the slave termios has ECHO off (password-style input). */
    fun echoOff(fd: Int): Boolean =
        try { nativeEchoOff(fd) } catch (t: Throwable) { false }

    fun signal(pid: Int, sig: Int) {
        try { nativeSignal(pid, sig) } catch (t: Throwable) { /* best-effort */ }
    }

    fun close(fd: Int) {
        try { nativeClose(fd) } catch (t: Throwable) { /* best-effort */ }
    }

    private external fun nativePtyVersion(): Int
    private external fun nativeCreate(
        shellPath: String,
        cwd: String,
        envPairs: Array<String>,
        rows: Int,
        cols: Int,
    ): IntArray
    private external fun nativeRead(fd: Int, buffer: ByteArray): Int
    private external fun nativeWrite(fd: Int, data: ByteArray): Int
    private external fun nativeResize(fd: Int, rows: Int, cols: Int)
    private external fun nativeEchoOff(fd: Int): Boolean
    private external fun nativeSignal(pid: Int, sig: Int)
    private external fun nativeClose(fd: Int)
}
