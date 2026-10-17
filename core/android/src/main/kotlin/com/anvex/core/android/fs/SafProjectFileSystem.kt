package com.anvex.core.android.fs

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.anvex.core.engine.PathSanitizer
import com.anvex.core.engine.ProjectFileSystem

/** SAF-backed workspace implementation. Call from Dispatchers.IO. */
class SafProjectFileSystem(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : ProjectFileSystem {
    private val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)

    /**
     * Root is represented by an empty relative path. PathSanitizer intentionally
     * rejects empty paths for file operations, so SAF root handling must be
     * explicit instead of passing "" through the sanitizer.
     */
    private fun safe(path: String): String =
        if (path.isBlank()) "" else PathSanitizer.sanitize(path)

    private fun child(parentId: String, name: String): Pair<String, Boolean>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        resolver.query(uri, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        ), null, null, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mime = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val display = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (c.moveToNext()) if (c.getString(display) == name) {
                return c.getString(id) to (c.getString(mime) == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
        return null
    }

    private fun document(path: String, createDirs: Boolean = false): Pair<String, Boolean>? {
        val clean = safe(path)
        if (clean.isBlank()) return rootDocumentId to true
        var parent = rootDocumentId
        val parts = clean.split('/')
        parts.forEachIndexed { index, part ->
            val found = child(parent, part)
            val current = if (found == null && createDirs) {
                val uri = DocumentsContract.createDocument(
                    resolver,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, parent),
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    part,
                ) ?: return null
                DocumentsContract.getDocumentId(uri) to true
            } else found ?: return null
            parent = current.first
            if (index == parts.lastIndex) return current
            if (!current.second) return null
        }
        return null
    }

    private fun parentAndName(path: String): Pair<String, String> {
        val clean = safe(path)
        require(clean.isNotBlank()) { "Root cannot be mutated" }
        return clean.substringBeforeLast('/', "") to clean.substringAfterLast('/')
    }

    override fun mkdirs(relativePath: String) { if (safe(relativePath).isNotBlank()) document(relativePath, true) }
    override fun exists(relativePath: String) = document(relativePath) != null
    override fun isDirectory(relativePath: String) = document(relativePath)?.second == true

    override fun writeFile(relativePath: String, content: String) = writeFileBytes(relativePath, content.toByteArray(Charsets.UTF_8))

    override fun writeFileBytes(relativePath: String, bytes: ByteArray) {
        val (parentPath, name) = parentAndName(relativePath)
        val parent = document(parentPath, true) ?: throw java.io.IOException("Workspace folder unavailable")
        val existing = child(parent.first, name)
        val uri = if (existing != null) {
            if (existing.second) throw java.io.IOException("Path is a directory: $relativePath")
            DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.first)
        } else {
            DocumentsContract.createDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(treeUri, parent.first),
                mimeFor(name),
                name,
            ) ?: throw java.io.IOException("Cannot create $name")
        }
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw java.io.IOException("Cannot write $name")
    }

    override fun writeFileStream(relativePath: String, input: java.io.InputStream, maxBytes: Long) {
        val (parentPath, name) = parentAndName(relativePath)
        val parent = document(parentPath, true) ?: throw java.io.IOException("Workspace folder unavailable")
        val existing = child(parent.first, name)
        val uri = if (existing != null) {
            if (existing.second) throw java.io.IOException("Path is a directory: $relativePath")
            DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.first)
        } else {
            DocumentsContract.createDocument(
                resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, parent.first),
                mimeFor(name), name
            ) ?: throw java.io.IOException("Cannot create $name")
        }
        resolver.openOutputStream(uri, "wt")?.use { out ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maxBytes) { "File exceeds configured size limit." }
                out.write(buffer, 0, read)
            }
        } ?: throw java.io.IOException("Cannot write $name")
    }

    override fun readFile(relativePath: String) = readFileBytes(relativePath)?.toString(Charsets.UTF_8)

    override fun readFileBytes(relativePath: String): ByteArray? {
        val doc = document(relativePath) ?: return null
        if (doc.second) return null
        return resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, doc.first))?.use { it.readBytes() }
    }

    override fun fileSize(relativePath: String): Long? {
        val doc = document(relativePath) ?: return null
        if (doc.second) return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, doc.first)
        return resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else {
                val index = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (index < 0 || c.isNull(index)) null else c.getLong(index).coerceAtLeast(0L)
            }
        }
    }

    override fun appendFile(relativePath: String, content: String) {
        val doc = document(relativePath)
        if (doc == null) { writeFile(relativePath, content); return }
        if (doc.second) throw java.io.IOException("Cannot append to directory")
        resolver.openOutputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, doc.first), "wa")?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw java.io.IOException("Cannot append file")
    }

    /** Delete this exact SAF tree root. Call only when the repository has recorded
     * that Anvex created/owns this directory. External workspaces must never call it. */
    fun deleteOwnedRoot(): Boolean {
        return DocumentsContract.deleteDocument(
            resolver,
            DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId),
        )
    }

    override fun delete(relativePath: String, recursive: Boolean): Boolean {
        if (relativePath.isBlank()) return false // Never delete the user-selected workspace root.
        val doc = document(relativePath) ?: return false
        if (doc.second && !recursive && list(relativePath).isNotEmpty()) return false
        if (doc.second && recursive) list(relativePath).forEach { delete(if (relativePath.isBlank()) it.name else "$relativePath/${it.name}", true) }
        return DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, doc.first))
    }

    override fun list(relativePath: String): List<ProjectFileSystem.FileEntry> {
        val parent = document(relativePath) ?: return emptyList()
        if (!parent.second) return emptyList()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent.first)
        val result = ArrayList<ProjectFileSystem.FileEntry>()
        resolver.query(uri, arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        ), null, null, "${DocumentsContract.Document.COLUMN_DISPLAY_NAME} COLLATE NOCASE ASC")?.use { c ->
            val name = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val size = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (c.moveToNext()) {
                val n = c.getString(name) ?: continue
                val dir = c.getString(mime) == DocumentsContract.Document.MIME_TYPE_DIR
                result += ProjectFileSystem.FileEntry(n, dir, if (dir || size < 0 || c.isNull(size)) 0 else c.getLong(size))
            }
        }
        return result
    }

    override fun move(from: String, to: String): Boolean {
        val src = document(from) ?: return false
        val (parentPath, targetName) = parentAndName(to)
        val dst = document(parentPath, true) ?: return false
        val srcName = from.substringAfterLast('/')
        val moved = DocumentsContract.moveDocument(
            resolver,
            DocumentsContract.buildDocumentUriUsingTree(treeUri, src.first),
            treeUri,
            DocumentsContract.buildDocumentUriUsingTree(treeUri, dst.first),
        ) ?: return false
        if (targetName == srcName) return true
        val movedId = DocumentsContract.getDocumentId(moved)
        return DocumentsContract.renameDocument(
            resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, movedId), targetName
        ) != null
    }

    override fun copy(from: String, to: String): Boolean {
        val src = document(from) ?: return false
        if (src.second) return false
        val bytes = readFileBytes(from) ?: return false
        writeFileBytes(to, bytes)
        return true
    }

    override fun walk(): List<String> {
        val out = ArrayList<String>()
        fun visit(dir: String, depth: Int) {
            if (depth > 64 || out.size >= 20_000) return
            list(dir).forEach { e ->
                if (out.size >= 20_000) return@forEach
                val path = if (dir.isBlank()) e.name else "$dir/${e.name}"
                if (e.isDirectory) visit(path, depth + 1) else out += path
            }
        }
        visit("", 0)
        return out
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs", "cjs" -> "text/javascript"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "md", "txt" -> "text/plain"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }
}
