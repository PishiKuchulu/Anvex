package com.anvex.core.android.fs

import com.anvex.core.engine.PathSanitizer
import com.anvex.core.engine.ProjectFileSystem
import java.io.File

/**
 * Real filesystem implementation: each project lives in
 * `<filesDir>/projects/<projectId>/` — app-private storage, no runtime
 * permissions needed, wiped only on app uninstall or explicit user action.
 */
class RealProjectFileSystem(private val root: File) : ProjectFileSystem {

    init {
        root.mkdirs()
    }

    private fun resolve(relativePath: String): File {
        val safe = PathSanitizer.sanitize(relativePath)
        val f = File(root, safe)
        // Defense in depth: canonical path must remain under root.
        if (!f.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
            throw SecurityException("Path escapes project root: $relativePath")
        }
        return f
    }

    override fun mkdirs(relativePath: String) {
        resolve(relativePath).mkdirs()
    }

    override fun exists(relativePath: String): Boolean = resolve(relativePath).exists()

    override fun isDirectory(relativePath: String): Boolean = resolve(relativePath).isDirectory

    override fun writeFile(relativePath: String, content: String) {
        val f = resolve(relativePath)
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
    }

    override fun writeFileBytes(relativePath: String, bytes: ByteArray) {
        val f = resolve(relativePath)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
    }

    override fun writeFileStream(relativePath: String, input: java.io.InputStream, maxBytes: Long) {
        val target = resolve(relativePath)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp-${java.util.UUID.randomUUID()}")
        var total = 0L
        try {
            java.io.FileOutputStream(tmp).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= maxBytes) { "File exceeds configured size limit." }
                    out.write(buffer, 0, read)
                }
                out.fd.sync()
            }
            require(tmp.renameTo(target)) { "Could not atomically publish $relativePath" }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    override fun readFile(relativePath: String): String? {
        val f = resolve(relativePath)
        if (!f.isFile) return null
        return f.readText(Charsets.UTF_8)
    }

    override fun readFileBytes(relativePath: String): ByteArray? {
        val f = resolve(relativePath)
        if (!f.isFile) return null
        return f.readBytes()
    }

    override fun fileSize(relativePath: String): Long? {
        val f = resolve(relativePath)
        return if (f.isFile) f.length() else null
    }

    override fun appendFile(relativePath: String, content: String) {
        val f = resolve(relativePath)
        f.parentFile?.mkdirs()
        f.appendText(content, Charsets.UTF_8)
    }

    override fun delete(relativePath: String, recursive: Boolean): Boolean {
        val f = resolve(relativePath)
        return if (recursive) f.deleteRecursively() else f.delete()
    }

    override fun list(relativePath: String): List<ProjectFileSystem.FileEntry> {
        val dir = if (relativePath.isBlank()) root else resolve(relativePath)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.map { ProjectFileSystem.FileEntry(it.name, it.isDirectory, it.length()) }
            ?.sortedWith(compareByDescending<ProjectFileSystem.FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()
    }

    override fun move(from: String, to: String): Boolean {
        val src = resolve(from)
        if (!src.exists()) return false
        val dst = resolve(to)
        dst.parentFile?.mkdirs()
        if (dst.isDirectory) return false
        return src.renameTo(dst)
    }

    override fun copy(from: String, to: String): Boolean {
        val src = resolve(from)
        if (!src.isFile) return false
        val dst = resolve(to)
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
        return true
    }

    override fun walk(): List<String> {
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .toList()
    }

    fun rootDirectory(): File = root

    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun deleteAll() {
        root.deleteRecursively()
        root.mkdirs()
    }
}
