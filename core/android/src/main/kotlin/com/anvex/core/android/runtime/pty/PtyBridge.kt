package com.anvex.core.android.runtime.pty

/**
 * JNI bridge to `libanvex-pty.so` (cpp/pty/pty_bridge.cpp).
 *
 * P0 scaffolding for the desktop-grade upgrade (plan feature F3): the loader
 * and a version probe only. The real forkpty session surface lands in P2.
 * Loading is failure-tolerant by design — a missing or unloadable library
 * (exotic ABI, corrupted install) must never crash the app; the terminal
 * falls back to the existing pipe-based session, exactly as the Node
 * runtime falls back when libnode is absent.
 */
object PtyBridge {

    /** True when libanvex-pty.so loaded for this device's ABI. */
    val available: Boolean = runCatching {
        System.loadLibrary("anvex-pty")
        true
    }.getOrDefault(false)

    /** Bridge ABI version from native code, or -1 when unavailable. */
    fun bridgeVersion(): Int =
        if (available) runCatching { nativePtyVersion() }.getOrDefault(-1) else -1

    external fun nativePtyVersion(): Int
}
