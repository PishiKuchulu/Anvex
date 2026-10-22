package com.anvex.core.android.runtime.node

/**
 * JNI bridge to the bundled nodejs-mobile runtime (libnode.so).
 *
 * Node can be started exactly ONCE per process and then blocks until its
 * event loop drains, so this must only be called from [NodeExecService],
 * which lives in a dedicated ":node" process that is killed after each run.
 */
object NodeBridge {

    /** The link error when [loaded] is false (diagnostics; null on success). */
    val loadError: String?

    /** True when libnode.so exists for this device's ABI and both libraries loaded. */
    val loaded: Boolean

    init {
        var err: String? = null
        loaded = try {
            // Preload libnode's dependency closure explicitly, in dependency
            // order, BEFORE the bridge. Loading the bridge alone lets bionic
            // pull libnode.so implicitly, and that path fails to resolve
            // ICU symbols libnode references (observed on the API-35
            // emulator smoke: "cannot locate symbol
            // UCNV_TO_U_CALLBACK_STOP_78 referenced by libnode.so"), while
            // the same libraries load cleanly one by one. The closure ships
            // in the app lib dir next to libnode.so (see the libnode-v24.18.0
            // runtime package); system libraries are not in this list.
            for (lib in CLOSURE) System.loadLibrary(lib)
            System.loadLibrary("anvex-node")
            true
        } catch (t: Throwable) {
            // UnsatisfiedLinkError is an Error, not an Exception — catch Throwable.
            err = t.toString()
            false
        }
        loadError = err
    }

    /** libnode.so's bundled DT_NEEDED closure, leaves first. */
    private val CLOSURE = listOf(
        "z", "cares", "sqlite3", "crypto", "ssl",
        "icudata", "icuuc", "icui18n", "node",
    )

    /**
     * Starts Node with the given argv (argv[0] must be "node"). Blocks the
     * calling thread until Node exits; returns the Node exit code.
     */
    external fun startNodeWithArguments(arguments: Array<String>): Int
}
