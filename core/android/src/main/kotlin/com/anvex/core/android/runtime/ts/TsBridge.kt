package com.anvex.core.android.runtime.ts

/**
 * JNI bridge to libanvex-ts.so (F2a, P4): tree-sitter core + five
 * grammars. Failure-tolerant like the other bridges: when the library is
 * absent [available] is false and the editor keeps its regex
 * highlighter. The wire format of [nativeParse] is documented in
 * ts_bridge.cpp.
 */
object TsBridge {

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
                            System.loadLibrary("anvex-ts")
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
        if (!available) null else try { nativeTsVersion() } catch (t: Throwable) { null }

    /** Raw protocol text, or null when unavailable / the native call failed. */
    fun parse(
        handle: Long,
        language: String,
        source: String,
        edit: TsEdit?,
    ): String? {
        if (!available) return null
        return try {
            nativeParse(
                handle, language, source,
                edit?.startByte ?: -1,
                edit?.oldEndByte ?: -1,
                edit?.newEndByte ?: -1,
                edit?.startRow ?: 0,
                edit?.startCol ?: 0,
                edit?.oldEndRow ?: 0,
                edit?.oldEndCol ?: 0,
                edit?.newEndRow ?: 0,
                edit?.newEndCol ?: 0,
            ).takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }
    }

    fun release(handle: Long) {
        if (!available || handle <= 0) return
        try { nativeRelease(handle) } catch (t: Throwable) { /* cache eviction also frees */ }
    }

    external fun nativeTsVersion(): String

    external fun nativeParse(
        handle: Long,
        language: String,
        source: String,
        editStart: Int,
        editOldEnd: Int,
        editNewEnd: Int,
        editStartRow: Int,
        editStartCol: Int,
        editOldEndRow: Int,
        editOldEndCol: Int,
        editNewEndRow: Int,
        editNewEndCol: Int,
    ): String

    external fun nativeRelease(handle: Long)
}
