package com.anvex.core.android

import com.anvex.core.engine.PathSanitizer
import com.anvex.core.engine.ProjectFileSystem
import com.anvex.core.android.fs.RealProjectFileSystem
import com.anvex.core.engine.ZipCodec
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Password-encrypted project archive boundary with staged validation and rollback. */
class ProjectBackupService {
    fun export(fs: ProjectFileSystem, password: CharArray): String {
        require(password.size >= 8) { "Backup password must be at least 8 characters." }
        val zip = java.io.ByteArrayOutputStream()
        // M-06: the whole archive is encrypted with the user's password,
        // so secrets stay in the backup (dropping `.env` would restore a
        // broken project). The unencrypted SAF share path keeps the
        // default excludeSecrets=true.
        ZipCodec.export(fs, zip, excludeSecrets = false)
        require(zip.size() <= MAX_BACKUP_BYTES) { "Project backup exceeds the safety limit." }
        return PortableBackup.encryptBytes(zip.toByteArray(), password)
    }

    fun decryptToBytes(encoded: String, password: CharArray): ByteArray {
        require(encoded.length <= MAX_ENVELOPE_CHARS) { "Backup envelope is too large." }
        val plain = PortableBackup.decryptBytes(encoded, password)
        require(plain.size <= MAX_BACKUP_BYTES) { "Decrypted backup exceeds the safety limit." }
        return plain
    }

    /**
     * Restores through a private staging directory. The current workspace is first
     * archived, then replaced; if commit fails, the previous archive is replayed.
     * This gives transactional semantics at the application boundary without
     * assuming that SAF providers implement filesystem transactions.
     */
    fun import(encoded: String, password: CharArray, fs: ProjectFileSystem): Int {
        val bytes = decryptToBytes(encoded, password)
        val tempRoot = File.createTempFile("anvex-restore-", "")
        check(tempRoot.delete() && tempRoot.mkdirs()) { "Could not create restore staging directory." }
        val staged = File(tempRoot, "staged.zip")
        val previous = File(tempRoot, "previous.zip")
        return try {
            staged.writeBytes(bytes)
            val stagedFs = RealProjectFileSystem(File(tempRoot, "staged-project").apply { mkdirs() })
            FileInputStream(staged).use { ZipCodec.import(it, stagedFs) }
            // The rollback snapshot must be COMPLETE (it is the only copy of
            // the pre-restore workspace): same excludeSecrets=false as the
            // backup export above — the merged safe-by-default would have
            // silently dropped .env from the rollback copy.
            FileOutputStream(previous).use { ZipCodec.export(fs, it, excludeSecrets = false) }
            val count = commit(stagedFs, fs)
            count
        } catch (e: Throwable) {
            runCatching {
                if (previous.isFile) {
                    clear(fs)
                    FileInputStream(previous).use { ZipCodec.import(it, fs) }
                }
            }.getOrElse { rollbackError -> throw IllegalStateException("Restore failed and rollback could not be completed: ${rollbackError.message}", e) }
            throw e
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun commit(staged: ProjectFileSystem, target: ProjectFileSystem): Int {
        clear(target)
        var count = 0
        for (path in staged.walk()) {
            val bytes = staged.readFileBytes(path) ?: continue
            PathSanitizer.parent(path)?.takeIf { it.isNotBlank() }?.let(target::mkdirs)
            target.writeFileBytes(path, bytes)
            count++
        }
        return count
    }

    private fun clear(fs: ProjectFileSystem) {
        fun clearDir(path: String) {
            fs.list(path).forEach { entry ->
                val child = if (path.isBlank()) entry.name else "$path/${entry.name}"
                if (entry.isDirectory) {
                    clearDir(child)
                    fs.delete(child, recursive = true)
                } else {
                    fs.delete(child, recursive = false)
                }
            }
        }
        clearDir("")
    }

    companion object {
        const val MAX_BACKUP_BYTES = 256L * 1024 * 1024
        const val MAX_ENVELOPE_CHARS = 64 * 1024 * 1024
    }
}
