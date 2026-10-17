package com.anvex.core.engine.vcs

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * GitLite — a pure-Kotlin local Git implementation (GOAL 2, "complete local git").
 *
 * Honest scope: this is a real *local* VCS with the actual Git object model —
 * loose objects (blob/tree/commit/tag), SHA-1 object ids, zlib compression, a real
 * `index` (v2, DIRC) file, refs, HEAD and reflogs — so a repository created and
 * committed through GitLite is readable and continues to work with the desktop/
 * termux `git` CLI (covered by GitLiteTest compatibility assertions). Deliberately
 * NOT implemented (docs/KNOWN_LIMITATIONS.md): packfiles, network transport
 * (clone/fetch/push), merge/rebase, submodules, signatures and hooks. Remote
 * publishing stays on the GitHub REST repository.
 */
object GitLite {

    // ------------------------------------------------------------------ public API

    /** Opens or creates a repository rooted at [root] (`.git` inside it). */
    fun init(root: File): Repository {
        val gitDir = File(root, ".git")
        gitDir.mkdirs()
        File(gitDir, "objects").mkdirs()
        File(gitDir, "refs/heads").mkdirs()
        File(gitDir, "refs/tags").mkdirs()
        val head = File(gitDir, "HEAD")
        if (!head.isFile) head.writeText("ref: refs/heads/main\n")
        if (!File(gitDir, "index").isFile) writeIndex(gitDir, emptyList())
        if (!File(gitDir, "config").isFile) {
            File(gitDir, "config").writeText(
                """
                [core]
                        repositoryformatversion = 0
                        filemode = false
                        bare = false
                        logallrefupdates = true
                """.trimIndent() + "\n",
            )
        }
        if (!File(gitDir, "description").isFile) {
            File(gitDir, "description").writeText("Unnamed repository; edit this file 'description' to name the repository.\n")
        }
        return Repository(root)
    }

    fun open(root: File): Repository? {
        val gitDir = File(root, ".git")
        if (!gitDir.isDirectory || !File(gitDir, "HEAD").isFile) return null
        return Repository(root)
    }

    class Repository internal constructor(val root: File) {
        private val gitDir get() = File(root, ".git")
        internal val objectsDir get() = File(gitDir, "objects")

        // ------------------------------------------------------------ status

        data class Status(
            val branch: String,
            val staged: List<Entry>,
            val unstaged: List<Entry>,
            val untracked: List<String>,
            val headCommit: String?,
        ) {
            data class Entry(val path: String, val change: String)
            val isClean: Boolean get() = staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty()
        }

        fun currentBranch(): String {
            val head = File(gitDir, "HEAD").readText().trim()
            return if (head.startsWith("ref: refs/heads/")) head.removePrefix("ref: refs/heads/") else "DETACHED"
        }

        fun headCommit(): String? {
            val head = File(gitDir, "HEAD").readText().trim()
            if (!head.startsWith("ref: ")) return head
            val ref = File(gitDir, head.removePrefix("ref: "))
            return if (ref.isFile) ref.readText().trim().takeIf { it.isNotBlank() } else null
        }

        fun status(): Status {
            val index = readIndex()
            val headTree = headCommit()?.let { commitTreeOf(it) } ?: emptyMap()
            val working = workingTreeFiles()
            val staged = ArrayList<Status.Entry>()
            val unstaged = ArrayList<Status.Entry>()
            val untracked = ArrayList<String>()

            for (entry in index) {
                val file = File(root, entry.path)
                if (!file.isFile) {
                    unstaged += Status.Entry(entry.path, "deleted")
                    continue
                }
                val blobSha = hashObject("blob", file.readBytes()) ?: continue
                if (blobSha != entry.sha) unstaged += Status.Entry(entry.path, "modified")
                working.remove(entry.path)
            }
            untracked += working.sorted()
            for (entry in index) {
                if (!headTree.containsKey(entry.path)) staged += Status.Entry(entry.path, "added")
                else if (headTree[entry.path] != entry.sha) staged += Status.Entry(entry.path, "modified")
            }
            for ((path, _) in headTree) {
                if (index.none { it.path == path }) staged += Status.Entry(path, "deleted")
            }
            return Status(currentBranch(), staged.sortedBy { it.path }, unstaged.sortedBy { it.path }, untracked, headCommit())
        }

        // ------------------------------------------------------------ staging

        fun add(paths: Collection<String>): Int {
            val index = readIndex().toMutableList()
            var changed = 0
            for (raw in paths) {
                val path = normalize(raw) ?: continue
                val file = File(root, path)
                val existing = index.indexOfFirst { it.path == path }
                if (!file.isFile) {
                    if (existing >= 0) { index.removeAt(existing); changed++ }
                    continue
                }
                // The blob object is written now so the index never references a
                // missing object (git fsck contract).
                val sha = writeObject("blob", file.readBytes()) ?: continue
                val entry = IndexEntry.of(path, sha, file)
                if (existing >= 0) index[existing] = entry else index.add(entry)
                changed++
            }
            if (changed > 0) writeIndex(gitDir, index)
            return changed
        }

        fun addAll(): Int = add(workingTreeFiles().toList())

        fun rm(paths: Collection<String>): Int {
            val index = readIndex().toMutableList()
            var removed = 0
            for (raw in paths) {
                val path = normalize(raw) ?: continue
                val existing = index.indexOfFirst { it.path == path }
                // Like `git rm`: only tracked files are removed; an untracked file is left alone.
                if (existing < 0) continue
                index.removeAt(existing); removed++
                val file = File(root, path)
                if (file.isFile) file.delete()
            }
            if (removed > 0) writeIndex(gitDir, index)
            return removed
        }

        /**
         * v4.4 (architect review): `git rm --cached` semantics — remove from the
         * index ONLY and keep the working-tree file. The plain [rm] above keeps
         * `git rm` semantics (index + working tree); callers now have both.
         */
        fun rmCached(paths: Collection<String>): Int {
            val index = readIndex().toMutableList()
            var removed = 0
            for (raw in paths) {
                val path = normalize(raw) ?: continue
                val existing = index.indexOfFirst { it.path == path }
                if (existing >= 0) { index.removeAt(existing); removed++ }
            }
            if (removed > 0) writeIndex(gitDir, index)
            return removed
        }

        // ------------------------------------------------------------ commit

        data class Commit(
            val sha: String,
            val tree: String,
            val parents: List<String>,
            val author: String,
            val message: String,
            val timestamp: Long,
        )

        fun commit(message: String, authorName: String = "Anvex Harness", authorEmail: String = "harness@anvex.local"): Commit? {
            if (message.isBlank()) return null
            val index = readIndex()
            if (index.isEmpty()) return null
            val previousHead = headCommit()
            val parents = listOfNotNull(previousHead)
            val treeSha = writeTree(index)
            val timestamp = System.currentTimeMillis() / 1000
            val ident = "$authorName <$authorEmail> $timestamp +0000"
            val body = buildString {
                append("tree ").append(treeSha).append('\n')
                parents.forEach { append("parent ").append(it).append('\n') }
                append("author ").append(ident).append('\n')
                append("committer ").append(ident).append('\n')
                append('\n')
                append(message.trim()).append('\n')
            }
            val commitSha = writeObject("commit", body.toByteArray(Charsets.UTF_8)) ?: return null
            val branch = currentBranch()
            val refFile = File(gitDir, "refs/heads/$branch")
            refFile.parentFile?.mkdirs()
            refFile.writeText("$commitSha\n")
            appendRefLog(branch, previousHead, commitSha, message)
            return Commit(commitSha, treeSha, parents, authorName, message.trim(), timestamp * 1000)
        }

        fun log(max: Int = 50): List<Commit> {
            var cursor = headCommit()
            val out = ArrayList<Commit>()
            while (cursor != null && out.size < max) {
                val commit = commitOf(cursor) ?: break
                out += commit
                cursor = commit.parents.firstOrNull()
            }
            return out
        }

        fun commitOf(sha: String): Commit? {
            val raw = readObject(sha) ?: return null
            if (!raw.first.startsWith("commit")) return null
            val text = raw.second.toString(Charsets.UTF_8)
            val tree = Regex("^tree ([0-9a-f]{40})$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
                ?: return null
            val parents = Regex("^parent ([0-9a-f]{40})$", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList()
            val author = Regex("^author (.+?) <[^>]+> (\\d+) [+-]\\d{4}$", RegexOption.MULTILINE).find(text)
            val message = text.substringAfter("\n\n", "").trim()
            return Commit(sha, tree, parents, author?.groupValues?.get(1) ?: "unknown", message, (author?.groupValues?.get(2)?.toLongOrNull() ?: 0L) * 1000)
        }

        // ------------------------------------------------------------ branches & tags

        fun branches(): List<String> =
            File(gitDir, "refs/heads").listFiles()?.map { it.name }?.sorted() ?: listOf("main")

        fun createBranch(name: String): Boolean {
            if (!isValidRefName(name)) return false
            val head = headCommit() ?: return false
            val ref = File(gitDir, "refs/heads/$name")
            if (ref.isFile) return false
            ref.parentFile?.mkdirs()
            ref.writeText("$head\n")
            return true
        }

        fun checkout(branch: String, force: Boolean = false): Boolean {
            if (!File(gitDir, "refs/heads/$branch").isFile) return false
            if (!force && !status().isClean) return false
            val target = File(gitDir, "refs/heads/$branch").readText().trim()
            val tree = commitTreeOf(target) ?: return false
            resetWorkingTree(tree)
            File(gitDir, "HEAD").writeText("ref: refs/heads/$branch\n")
            writeIndex(gitDir, treeToIndex(tree))
            return true
        }

        fun createTag(name: String, message: String = ""): Boolean {
            if (!isValidRefName(name)) return false
            val head = headCommit() ?: return false
            val tag = buildString {
                append("object ").append(head).append('\n')
                append("type commit\n")
                append("tag ").append(name).append('\n')
                append("tagger Anvex Harness <harness@anvex.local> ").append(System.currentTimeMillis() / 1000).append(" +0000\n\n")
                if (message.isNotBlank()) append(message.trim()).append('\n')
            }
            val sha = writeObject("tag", tag.toByteArray(Charsets.UTF_8)) ?: return false
            val ref = File(gitDir, "refs/tags/$name")
            if (ref.isFile) return false
            ref.parentFile?.mkdirs()
            ref.writeText("$sha\n")
            return true
        }

        fun tags(): List<String> = File(gitDir, "refs/tags").listFiles()?.map { it.name }?.sorted() ?: emptyList()

        // ------------------------------------------------------------ diff

        /** Unified diff of the working file against the index (unstaged changes). */
        fun diff(path: String): String? {
            val normalized = normalize(path) ?: return null
            val file = File(root, normalized)
            val indexEntry = readIndex().firstOrNull { it.path == normalized }
            val oldBytes = indexEntry?.let { readObject(it.sha)?.second } ?: return null
            val oldText = oldBytes.toString(Charsets.UTF_8)
            val newText = if (file.isFile) file.readText() else ""
            val body = com.anvex.core.engine.DiffEngine.diff(oldText, newText).joinToString("\n") { line ->
                when (line.op) {
                    com.anvex.core.engine.DiffEngine.Op.EQUAL -> " ${line.text}"
                    com.anvex.core.engine.DiffEngine.Op.INSERT -> "+${line.text}"
                    com.anvex.core.engine.DiffEngine.Op.DELETE -> "-${line.text}"
                }
            }
            return "--- a/$normalized\n+++ b/$normalized\n$body"
        }

        /** Content of a file at the last commit (null if untracked at HEAD). */
        fun fileAtHead(path: String): String? {
            val normalized = normalize(path) ?: return null
            val tree = headCommit()?.let { commitTreeOf(it) } ?: return null
            val sha = tree[normalized] ?: return null
            return readObject(sha)?.second?.toString(Charsets.UTF_8)
        }

        // ------------------------------------------------------------------ internals

        private fun readIndex(): List<IndexEntry> = readIndexFrom(gitDir)

        private fun workingTreeFiles(): MutableSet<String> {
            val out = mutableSetOf<String>()
            root.walkTopDown().forEach { file ->
                if (file == root || file.startsWith(gitDir)) return@forEach
                if (file.isFile) {
                    val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
                    if (rel.startsWith(".git")) return@forEach
                    out.add(rel)
                }
            }
            return out
        }

        private fun modeFor(file: File): Int = if (file.canExecute()) MODE_EXECUTABLE else MODE_REGULAR

        private fun commitTreeOf(commitSha: String): Map<String, String>? {
            val commit = commitOf(commitSha) ?: return null
            return treeOf(commit.tree)
        }

        private fun treeOf(treeSha: String): Map<String, String> {
            val raw = readObject(treeSha) ?: return emptyMap()
            if (!raw.first.startsWith("tree")) return emptyMap()
            return flatTree(raw.second, "")
        }

        private fun flatTree(bytes: ByteArray, prefix: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            var i = 0
            while (i < bytes.size) {
                val space = indexOfByte(bytes, ' '.code.toByte(), i)
                val mode = bytes.copyOfRange(i, space).toString(Charsets.US_ASCII)
                val nul = indexOfByte(bytes, 0.toByte(), space + 1)
                val name = bytes.copyOfRange(space + 1, nul).toString(Charsets.UTF_8)
                val sha = bytes.copyOfRange(nul + 1, nul + 21).toHexString()
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (mode == "40000" || mode == "040000") {
                    readObject(sha)?.let { sub -> out.putAll(flatTree(sub.second, path)) }
                } else {
                    out[path] = sha
                }
                i = nul + 21
            }
            return out
        }

        private fun resetWorkingTree(tree: Map<String, String>) {
            workingTreeFiles().forEach { rel ->
                if (!tree.containsKey(rel)) File(root, rel).delete()
            }
            tree.forEach { (path, sha) ->
                val bytes = readObject(sha)?.second ?: return@forEach
                val target = File(root, path)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            root.walkBottomUp()
                .filter { it.isDirectory && it != root && !it.startsWith(gitDir) && it.listFiles()?.isEmpty() == true }
                .forEach { it.delete() }
        }

        private fun treeToIndex(tree: Map<String, String>): List<IndexEntry> =
            tree.map { (path, sha) ->
                val f = File(root, path)
                IndexEntry.of(path, sha, f)
            }.sortedBy { it.path }

        private fun appendRefLog(branch: String, oldHead: String?, newSha: String, message: String) {
            runCatching {
                val log = File(gitDir, "logs/refs/heads/$branch")
                log.parentFile?.mkdirs()
                val ts = System.currentTimeMillis() / 1000
                val old = oldHead ?: "0".repeat(40)
                log.appendText("$old $newSha $ts +0000\tcommit: ${message.trim().take(72)}\n")
            }
        }

        private fun normalize(raw: String): String? {
            if (raw.isBlank() || raw.startsWith(".git/")) return null
            val path = raw.trim().replace('\\', '/')
            if (path.split('/').any { it == ".." }) return null
            return path.trimStart('/').takeIf { it.isNotEmpty() }
        }
    }

    // ------------------------------------------------------------------ index (DIRC v2)

    data class IndexEntry(
        val path: String,
        val sha: String,
        val size: Long,
        val mtimeSec: Long,
        val mode: Int,
        /** Full stat data: git compares these against the worktree to avoid re-hashing. */
        val ctimeSec: Long = 0,
        val dev: Long = 0,
        val ino: Long = 0,
        val uid: Long = 0,
        val gid: Long = 0,
    ) {
        companion object {
            /** Real stat snapshot so `git status` treats our entries as up-to-date. */
            fun of(path: String, sha: String, file: java.io.File): IndexEntry {
                val attrs = HashMap<String, Long>()
                runCatching {
                    val view = java.nio.file.Files.readAttributes(
                        file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
                    )
                    attrs["ctime"] = view.creationTime().to(java.util.concurrent.TimeUnit.SECONDS)
                    attrs["mtime"] = view.lastModifiedTime().to(java.util.concurrent.TimeUnit.SECONDS)
                    attrs["size"] = view.size()
                }
                runCatching {
                    for (key in listOf("unix:ctime", "unix:dev", "unix:ino", "unix:uid", "unix:gid")) {
                        java.nio.file.Files.getAttribute(file.toPath(), key)?.let { value ->
                            val num = (value as? Number)?.toLong() ?: return@let
                            attrs[key.removePrefix("unix:")] = num
                        }
                    }
                }
                val mtime = attrs["mtime"] ?: (file.lastModified() / 1000)
                return IndexEntry(
                    path = path,
                    sha = sha,
                    size = attrs["size"] ?: file.length(),
                    mtimeSec = mtime,
                    mode = if (file.canExecute()) MODE_EXECUTABLE else MODE_REGULAR,
                    ctimeSec = attrs["ctime"] ?: mtime,
                    dev = attrs["dev"] ?: 0,
                    ino = attrs["ino"] ?: 0,
                    uid = attrs["uid"] ?: 0,
                    gid = attrs["gid"] ?: 0,
                )
            }
        }
    }

    internal fun readIndexFrom(gitDir: File): List<IndexEntry> {
        val file = File(gitDir, "index")
        if (!file.isFile) return emptyList()
        val bytes = file.readBytes()
        if (bytes.size < 12 || !bytes.copyOfRange(0, 4).contentEquals("DIRC".toByteArray())) return emptyList()
        val count = be32(bytes, 8).toInt()
        val entries = ArrayList<IndexEntry>()
        var i = 12
        while (entries.size < count) {
            if (i + 62 > bytes.size) break
            val mtimeSec = be32(bytes, i + 8)
            val mode = be32(bytes, i + 24).toInt()
            val size = be32(bytes, i + 36)
            val sha = bytes.copyOfRange(i + 40, i + 60).toHexString()
            val flags = ((bytes[i + 60].toInt() and 0xFF) shl 8) or (bytes[i + 61].toInt() and 0xFF)
            val nameLen = flags and 0xFFF
            val nameStart = i + 62
            val name: String = if (nameLen in 1..0xFFE && nameStart + nameLen <= bytes.size) {
                bytes.copyOfRange(nameStart, nameStart + nameLen).toString(Charsets.UTF_8)
            } else {
                // Long paths (>= 4095): NUL-terminated scan.
                var end = nameStart
                while (end < bytes.size && bytes[end] != 0.toByte()) end++
                bytes.copyOfRange(nameStart, end).toString(Charsets.UTF_8)
            }
            // git index padding: entry occupies a multiple of 8 bytes with >= 1 NUL.
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            i += (62 + nameBytes.size + 8) and 7.inv()
            entries += IndexEntry(name, sha, size, mtimeSec, mode)
        }
        return entries
    }

    private fun writeIndex(gitDir: File, entries: List<IndexEntry>) {
        val sorted = entries.sortedBy { it.path }
        val out = ByteArrayOutputStream()
        fun be32v(v: Long) {
            out.write(((v shr 24) and 0xFF).toInt()); out.write(((v shr 16) and 0xFF).toInt())
            out.write(((v shr 8) and 0xFF).toInt()); out.write((v and 0xFF).toInt())
        }
        out.write("DIRC".toByteArray())
        be32v(2)
        be32v(sorted.size.toLong())
        for (e in sorted) {
            val name = e.path.toByteArray(Charsets.UTF_8)
            be32v(e.ctimeSec) // ctime sec (real stat — git trusts it and skips re-hash)
            be32v(0) // ctime nsec
            be32v(e.mtimeSec)
            be32v(0) // mtime nsec
            be32v(e.dev)
            be32v(e.ino)
            be32v(e.mode.toLong())
            be32v(e.uid)
            be32v(e.gid)
            be32v(e.size)
            out.write(e.sha.hexToBytes())
            val flags = name.size.coerceAtMost(0xFFF)
            out.write((flags shr 8) and 0xFF); out.write(flags and 0xFF)
            out.write(name)
            val total = (62 + name.size + 8) and 7.inv()
            repeat(total - 62 - name.size) { out.write(0) }
        }
        val content = out.toByteArray()
        val sha = MessageDigest.getInstance("SHA-1").digest(content)
        File(gitDir, "index").writeBytes(content + sha)
    }

    // ------------------------------------------------------------------ objects

    internal fun hashObject(type: String, bytes: ByteArray): String? {
        val header = "$type ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII)
        return runCatching {
            MessageDigest.getInstance("SHA-1").digest(header + bytes).toHexString()
        }.getOrNull()
    }

    /** Builds nested trees from index entries; returns the root tree sha. */
    private fun Repository.writeTree(index: List<IndexEntry>): String {
        fun buildTree(prefix: String, paths: List<IndexEntry>): String {
            data class Record(val sortKey: String, val name: String, val dir: Boolean, val sha: String, val exec: Boolean)
            val records = ArrayList<Record>()
            val dirs = LinkedHashMap<String, MutableList<IndexEntry>>()
            for (entry in paths) {
                val rel = if (prefix.isEmpty()) entry.path else entry.path.removePrefix("$prefix/")
                val slash = rel.indexOf('/')
                if (slash < 0) {
                    records += Record(rel, rel, false, entry.sha, entry.mode == MODE_EXECUTABLE)
                } else {
                    val dir = rel.substring(0, slash)
                    val childPath = rel.substring(slash + 1)
                    dirs.getOrPut(dir) { ArrayList() } += entry.copy(path = childPath)
                }
            }
            dirs.forEach { (dir, children) ->
                val childSha = buildTree(dir, children)
                records += Record("$dir/", dir, true, childSha, false)
            }
            // Git sorts tree entries by raw name; directory entries compare as name + '/'.
            val sorted = records.sortedWith(compareBy { it.sortKey })
            val out = ByteArrayOutputStream()
            for (r in sorted) {
                val mode = when {
                    r.dir -> "40000"
                    r.exec -> "100755"
                    else -> "100644"
                }
                out.write(mode.toByteArray(Charsets.US_ASCII))
                out.write(' '.code)
                out.write(r.name.toByteArray(Charsets.UTF_8))
                out.write(0)
                out.write(r.sha.hexToBytes())
            }
            return writeObject("tree", out.toByteArray()) ?: error("Failed to write git tree object")
        }
        return buildTree("", index.sortedBy { it.path })
    }

    private fun Repository.writeObject(type: String, bytes: ByteArray): String? {
        val sha = hashObject(type, bytes) ?: return null
        val dir = File(objectsDir, sha.substring(0, 2))
        val file = File(dir, sha.substring(2))
        if (file.isFile) return sha // loose objects are content-addressed and immutable
        dir.mkdirs()
        val header = "$type ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII)
        file.writeBytes(deflate(header + bytes))
        return sha
    }

    private fun Repository.readObject(sha: String): Pair<String, ByteArray>? {
        if (!sha.matches(Regex("[0-9a-f]{40}"))) return null
        val file = File(objectsDir, "${sha.substring(0, 2)}/${sha.substring(2)}")
        if (!file.isFile) return null
        val raw = inflate(file.readBytes())
        val nul = indexOfByte(raw, 0.toByte(), 0)
        if (nul < 0) return null
        val header = raw.copyOfRange(0, nul).toString(Charsets.US_ASCII)
        return header.substringBefore(' ') to raw.copyOfRange(nul + 1, raw.size)
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(bytes)
        deflater.finish()
        val buffer = ByteArray(64 * 1024)
        val out = ByteArrayOutputStream()
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            if (n > 0) out.write(buffer, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(bytes)
        val buffer = ByteArray(64 * 1024)
        val out = ByteArrayOutputStream()
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n > 0) out.write(buffer, 0, n)
                if (inflater.needsDictionary() || (inflater.needsInput() && inflater.remaining == 0 && out.size() > 0)) break
                if (n == 0 && inflater.needsInput()) break
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun be32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or (bytes[offset + 3].toLong() and 0xFF)

    private fun indexOfByte(bytes: ByteArray, needle: Byte, from: Int): Int {
        for (i in from until bytes.size) if (bytes[i] == needle) return i
        return -1
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    internal fun isValidRefName(name: String): Boolean =
        name.isNotBlank() && name.length <= 200 &&
            !name.startsWith('.') && !name.contains("..") && !name.contains(' ') &&
            !name.endsWith(".lock") && name.split('/').none { it.isBlank() || it == "." }

    /** git index modes (decimal): 0100644 regular, 0100755 executable. */
    private const val MODE_REGULAR: Int = 33188
    private const val MODE_EXECUTABLE: Int = 33261
}
