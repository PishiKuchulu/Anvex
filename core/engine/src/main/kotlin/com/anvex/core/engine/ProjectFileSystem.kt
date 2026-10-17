package com.anvex.core.engine

import java.io.InputStream

/**
 * Abstract project filesystem implemented by :core:android (java.io.File under
 * app-private storage). Kept as an interface so the engine and its tests run on
 * plain JVM. All paths are RELATIVE, forward-slash separated, and must pass
 * [PathSanitizer.sanitize] before use.
 */
interface ProjectFileSystem {
    fun mkdirs(relativePath: String)
    fun exists(relativePath: String): Boolean
    fun isDirectory(relativePath: String): Boolean
    fun writeFile(relativePath: String, content: String)
    fun writeFileBytes(relativePath: String, bytes: ByteArray)

    /**
     * Streams file content into the workspace. Implementations with a real filesystem
     * should override this to avoid holding large archive entries in memory.
     * The default keeps source compatibility for lightweight/test implementations.
     */
    fun writeFileStream(relativePath: String, input: InputStream, maxBytes: Long = Long.MAX_VALUE) {
        require(maxBytes >= 0) { "maxBytes must be non-negative" }
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "File exceeds configured size limit." }
            out.write(buffer, 0, read)
        }
        writeFileBytes(relativePath, out.toByteArray())
    }
    fun readFile(relativePath: String): String?
    fun readFileBytes(relativePath: String): ByteArray?
    fun appendFile(relativePath: String, content: String)
    fun delete(relativePath: String, recursive: Boolean): Boolean
    fun list(relativePath: String): List<FileEntry>
    fun move(from: String, to: String): Boolean
    fun copy(from: String, to: String): Boolean
    /** All relative file paths (bounded walk, files only). */
    fun walk(): List<String>

    /** Cheap metadata lookup. Implementations should not load file contents. */
    fun fileSize(relativePath: String): Long? = readFileBytes(relativePath)?.size?.toLong()

    data class FileEntry(val name: String, val isDirectory: Boolean, val size: Long)
}
