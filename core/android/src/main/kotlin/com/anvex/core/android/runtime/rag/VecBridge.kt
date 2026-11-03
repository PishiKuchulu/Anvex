package com.anvex.core.android.runtime.rag

/**
 * JNI bridge to libanvex-vec.so (P7/F6): SQLite 3.50.4 + sqlite-vec
 * 0.1.9 statically linked. Same failure-tolerant loading as the other
 * Anvex bridges: when the library is missing (unsupported ABI, build
 * without the rag libs), [loaded] is false and RAG reports itself
 * unavailable instead of crashing.
 */
object VecBridge {
    val loaded: Boolean
    val loadError: String?

    init {
        var ok = false
        var err: String? = null
        try {
            System.loadLibrary("anvex-vec")
            ok = true
        } catch (t: Throwable) {
            err = t.message ?: t.javaClass.simpleName
        }
        loaded = ok
        loadError = err
    }

    external fun nativeVecVersion(): String?
    external fun nativeFileHash(dbPath: String, project: String, path: String): String?
    external fun nativeReplaceFile(
        dbPath: String,
        project: String,
        path: String,
        fileHash: String,
        chunkIds: Array<String>,
        startLines: IntArray,
        endLines: IntArray,
        texts: Array<String>,
        embeddings: ByteArray,
    ): Int
    external fun nativeQuery(dbPath: String, project: String, query: ByteArray, k: Int): String?
    external fun nativeCount(dbPath: String, project: String): Int
    external fun nativeClearProject(dbPath: String, project: String)
}
