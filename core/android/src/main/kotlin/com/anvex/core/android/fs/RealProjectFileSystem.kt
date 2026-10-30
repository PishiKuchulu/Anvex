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
        writeAtomically(resolve(relativePath), content.toByteArray(Charsets.UTF_8))
    }

    override fun writeFileBytes(relativePath: String, bytes: ByteArray) {
        writeAtomically(resolve(relativePath), bytes)
    }

    /**
     * v4.10.1 (BUG-06): writes go tmp + fsync + rename (the same discipline
     * writeFileStream already had) — a process kill mid-write used to leave
     * the target truncated or half-written.
     */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp-${java.util.UUID.randomUUID()}")
        try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            require(tmp.renameTo(target)) { "Could not atomically publish ${target.name}" }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
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

    /**
     * M-07: `File.deleteRecursively()` follows directory symlinks, so
     * deleting a project (or a recursive `rm`) could delete files
     * OUTSIDE the project through a planted link. A symlink is always
     * deleted as a link, never followed. Note the raw (non-canonical)
     * construction for the link test: `resolve()` canonicalizes and
     * throws for an escaping link before we could test it.
     */
    override fun delete(relativePath: String, recursive: Boolean): Boolean {
        val safe = PathSanitizer.sanitize(relativePath)
        val raw = File(root, safe)
        if (java.nio.file.Files.isSymbolicLink(raw.toPath())) return raw.delete()
        val f = resolve(relativePath)
        return if (recursive) deleteTreeNoFollow(f) else f.delete()
    }

    private fun isSymlink(f: File): Boolean = java.nio.file.Files.isSymbolicLink(f.toPath())

    /**
     * M-07: Kotlin's `deleteRecursively()` follows symlinks to directories. A link created
     * inside the project (`ln -s /sdcard/DCIM pics` from the terminal) would have its TARGET's
     * contents deleted. Links are removed as links and never descended into.
     */
    private fun deleteTreeNoFollow(f: File): Boolean {
        if (isSymlink(f)) return f.delete()
        if (f.isDirectory) {
            val children = f.listFiles() ?: return f.delete()
            var ok = true
            for (child in children) if (!deleteTreeNoFollow(child)) ok = false
            if (!ok) return false
        }
        return f.delete() || !f.exists()
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
        // v4.10.1 (BUG-04): renameTo onto an existing file silently clobbers
        // it (POSIX rename) and the journal only holds the SOURCE's content,
        // so the overwritten file was unrecoverable. Refuse instead; a
        // same-path move is a no-op success.
        if (src.canonicalFile == dst.canonicalFile) return true
        dst.parentFile?.mkdirs()
        if (dst.exists()) return false
        return src.renameTo(dst)
    }

    override fun copy(from: String, to: String): Boolean {
        val src = resolve(from)
        if (!src.isFile) return false
        val dst = resolve(to)
        // v4.10.1 (BUG-04): copyTo(overwrite=true) deletes the destination
        // first — for `cp a a` that deletes the only copy before reading it.
        if (src.canonicalFile == dst.canonicalFile) return true
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
        return true
    }

    /**
     * M-07: `walkTopDown()` follows directory symlinks, so a planted link
     * made `walk()` yield paths `resolve()` then rejects with
     * SecurityException — failing whole exports/searches — and could
     * expose files outside the project. Symlinks are never descended
     * into and never listed.
     */
    override fun walk(): List<String> {
        if (!root.exists()) return emptyList()
        // M-07: do not descend into / list symlinks — they may point outside the project
        // and every later read of such a path would throw a SecurityException.
        return root.walkTopDown()
            .onEnter { it == root || !isSymlink(it) }
            .filter { it.isFile && !isSymlink(it) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .toList()
    }

    fun rootDirectory(): File = root

    fun sizeBytes(): Long = root.walkTopDown().onEnter { it == root || !isSymlink(it) }.filter { it.isFile && !isSymlink(it) }.sumOf { it.length() }

    fun deleteAll() {
        root.deleteRecursively()
        root.mkdirs()
    }
}
