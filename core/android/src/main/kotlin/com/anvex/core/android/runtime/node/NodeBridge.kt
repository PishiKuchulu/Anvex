package com.anvex.core.android.runtime.node

/**
 * JNI bridge to the bundled nodejs-mobile runtime (libnode.so).
 *
 * Node can be started exactly ONCE per process and then blocks until its
 * event loop drains, so this must only be called from [NodeExecService],
 * which lives in a dedicated ":node" process that is killed after each run.
 */
object NodeBridge {

    /** True when libnode.so exists for this device's ABI and both libraries loaded. */
    val loaded: Boolean = try {
        System.loadLibrary("anvex-node")
        System.loadLibrary("node")
        true
    } catch (t: Throwable) {
        // UnsatisfiedLinkError is an Error, not an Exception — catch Throwable.
        false
    }

    /**
     * Starts Node with the given argv (argv[0] must be "node"). Blocks the
     * calling thread until Node exits; returns the Node exit code.
     */
    external fun startNodeWithArguments(arguments: Array<String>): Int
}
