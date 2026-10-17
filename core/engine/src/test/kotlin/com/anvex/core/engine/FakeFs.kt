package com.anvex.core.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** In-memory ProjectFileSystem for engine unit tests. */
class FakeFs : ProjectFileSystem {
    val files = LinkedHashMap<String, String>()
    val dirs = mutableSetOf("")

    override fun mkdirs(relativePath: String) {
        dirs.add(relativePath)
        var acc = ""
        relativePath.split('/').filter { it.isNotBlank() }.forEach { acc = if (acc.isEmpty()) it else "$acc/$it"; dirs.add(acc) }
    }

    override fun exists(relativePath: String): Boolean = files.containsKey(relativePath) || dirs.contains(relativePath)

    override fun isDirectory(relativePath: String): Boolean =
        relativePath.isBlank() || (dirs.contains(relativePath) && !files.containsKey(relativePath))

    override fun writeFile(relativePath: String, content: String) {
        files[relativePath] = content
    }

    override fun writeFileBytes(relativePath: String, bytes: ByteArray) {
        files[relativePath] = String(bytes, Charsets.UTF_8)
    }

    override fun readFile(relativePath: String): String? = files[relativePath]

    override fun readFileBytes(relativePath: String): ByteArray? = files[relativePath]?.toByteArray()

    override fun appendFile(relativePath: String, content: String) {
        files[relativePath] = (files[relativePath] ?: "") + content
    }

    override fun delete(relativePath: String, recursive: Boolean): Boolean {
        val removedFile = files.remove(relativePath) != null
        var removedDir = false
        if (recursive) {
            val prefix = "$relativePath/"
            val keys = files.keys.filter { it.startsWith(prefix) }
            keys.forEach { files.remove(it) }
            removedDir = dirs.remove(relativePath) || keys.isNotEmpty()
        } else {
            removedDir = dirs.remove(relativePath)
        }
        return removedFile || removedDir
    }

    override fun list(relativePath: String): List<ProjectFileSystem.FileEntry> {
        val base = if (relativePath.isBlank()) "" else relativePath
        val names = mutableSetOf<String>()
        files.keys.forEach { path ->
            if (path.startsWith("$base/") || (base.isEmpty())) {
                val rest = if (base.isEmpty()) path else path.removePrefix("$base/")
                names.add(rest.substringBefore('/'))
            }
        }
        return names.map { ProjectFileSystem.FileEntry(it, false, 0) }
    }

    override fun move(from: String, to: String): Boolean {
        val content = files.remove(from) ?: return false
        files[to] = content
        return true
    }

    override fun copy(from: String, to: String): Boolean {
        files[to] = files[from] ?: return false
        return true
    }

    override fun walk(): List<String> = files.keys.sorted()
}
