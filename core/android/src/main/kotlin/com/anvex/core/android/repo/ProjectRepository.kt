package com.anvex.core.android.repo

import android.content.Context
import android.net.Uri
import com.anvex.core.android.db.ProjectEntity
import com.anvex.core.android.fs.RealProjectFileSystem
import com.anvex.core.engine.PathSanitizer
import com.anvex.core.engine.ProjectTemplates
import com.anvex.core.engine.ZipCodec
import com.anvex.core.engine.ProjectFileSystem
import com.anvex.core.android.fs.SafProjectFileSystem
import com.anvex.core.model.FileNode
import com.anvex.core.model.Project
import com.anvex.core.model.ProjectType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import android.provider.DocumentsContract

/** Result wrapper for user-facing outcomes (brief §23 human-readable errors). */
sealed class RepoResult<out T> {
    data class Ok<T>(val value: T) : RepoResult<T>()
    data class Err(val title: String, val detail: String) : RepoResult<Nothing>()
}

// v4.10.5: bounds for Pi staging of cloud projects (fail-closed: over
// bounds aborts staging so Pi never sees a partial tree).
private const val MAX_STAGE_FILES = 2000
private const val MAX_STAGE_FILE_BYTES = 20L * 1024 * 1024
private const val MAX_STAGE_BYTES = 100L * 1024 * 1024

class ProjectRepository(
    private val context: Context,
    private val dao: com.anvex.core.android.db.ProjectDao,
    private val messageDao: com.anvex.core.android.db.MessageDao? = null,
    private val mutationDao: com.anvex.core.android.db.MutationDao? = null,
) {
    private val projectsRoot: File = File(context.filesDir, "projects").apply { mkdirs() }
    // Values are never null: ConcurrentHashMap rejects null values with an NPE
    // at put time, and a local (non-SAF) project legitimately has no workspace
    // URI. Absence of an entry means "local project", same as a null value did.
    private val workspaceUris = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Audit 02: invoked after a project's rows are deleted, so owners of
     * per-project side state (Pi chat session transcripts, …) can clean up.
     * Wired by AppGraph; null in standalone/test use.
     */
    @Volatile
    var onProjectDeleted: ((String) -> Unit)? = null

    /**
     * Audit 02: seed the workspace-URI cache from the database at startup.
     * The synchronous [fileSystemFor] consults only this map; before any
     * [fileSystemForAsync] call has populated it, a SAF project silently
     * resolved to an (empty, wrong) app-private directory.
     */
    suspend fun warmWorkspaceCache() {
        dao.all().forEach { entity ->
            entity.workspaceUri?.let { workspaceUris[entity.id] = it }
        }
    }

    fun observeAll(): Flow<List<Project>> = dao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun get(id: String): Project? = dao.get(id)?.toDomain()

    fun fileSystemFor(projectId: String): ProjectFileSystem {
        val workspace = workspaceUris[projectId]
        return workspace?.let { SafProjectFileSystem(context.contentResolver, Uri.parse(it)) }
            ?: RealProjectFileSystem(File(projectsRoot, projectId))
    }

    /**
     * The real on-disk root for local projects; null for SAF-backed
     * workspaces, which have no plain File root (callers that need one —
     * e.g. the Pi engine's Node process cwd — must fail closed there).
     */
    suspend fun localRootOrNull(projectId: String): File? {
        val entity = dao.get(projectId) ?: return null
        if (entity.workspaceUri != null) return null
        return File(projectsRoot, projectId).apply { mkdirs() }
    }

    /**
     * Chat workspace root for the Pi engine: local projects resolve via
     * [localRootOrNull]; direct chats (`direct/<uuid>`) live in the
     * app-private direct-chat area (same root DirectChat uses).
     */
    suspend fun chatRootOrNull(projectId: String): File? {
        if (projectId.startsWith("direct/")) {
            val name = projectId.removePrefix("direct/")
            if (name.isBlank() || name.contains('/') || name.contains("..")) return null
            return File(context.filesDir, "direct-chat/$name").apply { mkdirs() }
        }
        return localRootOrNull(projectId)
    }

    /**
     * v4.10.5: Pi staging for SAF/cloud projects. The Pi engine's Node
     * process needs a real local directory as cwd, which a SAF workspace
     * cannot provide — Pi used to fail closed here ("needs a local
     * workspace"). Stage a bounded local mirror, run Pi against it, and
     * sync edits back with [syncBackStagedCopy]. Fail-closed: any skipped
     * file (over bounds, unreadable, suspicious path) aborts staging
     * entirely, so Pi never sees a partial tree.
     */
    data class StageResult(val dir: File?, val error: String?)
    data class SyncReport(val written: Int, val unchanged: Int, val skipped: Int)

    /**
     * SHA-256 of every cloud file captured at staging time, per project.
     * Audit 02: sync-back used to write staged bytes over any cloud file
     * whose content differed — including files the user edited in the SAF
     * workspace WHILE Pi was running (stale staged copy silently won).
     * The snapshot lets sync-back detect that case and skip instead.
     */
    private val stagedCloudHashes = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    suspend fun stageLocalCopy(projectId: String): StageResult = withContext(Dispatchers.IO) {
        val entity = dao.get(projectId) ?: return@withContext StageResult(null, "Project not found.")
        if (entity.workspaceUri == null) return@withContext StageResult(null, "Project is not a cloud project.")
        val fs = runCatching { fileSystemForAsync(projectId) }.getOrNull()
            ?: return@withContext StageResult(null, "Cannot access the project workspace.")
        val staging = File(context.filesDir, "pi-staging/$projectId")
        try {
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()
            var files = 0
            var bytes = 0L
            var failure: String? = null
            val hashes = HashMap<String, String>()
            for (path in fs.walk()) {
                if (failure != null) break
                if (path.split('/').any { it == ".." }) {
                    failure = "Unsafe path in the cloud project: $path."
                    break
                }
                if (fs.isDirectory(path)) continue
                if (files >= MAX_STAGE_FILES) {
                    failure = "Project is too large to stage for Pi ($MAX_STAGE_FILES files)."
                    break
                }
                val data = fs.readFileBytes(path)
                if (data == null) {
                    failure = "Cannot read $path from the cloud project."
                    break
                }
                if (data.size > MAX_STAGE_FILE_BYTES || bytes + data.size > MAX_STAGE_BYTES) {
                    failure = "Project is too large to stage for Pi."
                    break
                }
                val target = File(staging, path)
                target.parentFile?.mkdirs()
                target.writeBytes(data)
                hashes[path] = sha256Hex(data)
                files++
                bytes += data.size
            }
            if (failure != null) {
                staging.deleteRecursively()
                StageResult(null, failure)
            } else {
                stagedCloudHashes[projectId] = hashes
                StageResult(staging, null)
            }
        } catch (e: Exception) {
            runCatching { staging.deleteRecursively() }
            StageResult(null, "Could not stage the project locally: ${e.message}")
        }
    }

    /**
     * Copies Pi's edits from a staging copy back into the cloud project.
     * Only new/changed files are written; files deleted from the staging
     * copy are NEVER deleted from the cloud project (fail-closed).
     */
    suspend fun syncBackStagedCopy(projectId: String, staging: File): SyncReport = withContext(Dispatchers.IO) {
        val fs = runCatching { fileSystemForAsync(projectId) }.getOrNull()
            ?: return@withContext SyncReport(0, 0, 0)
        // One-shot snapshot: consumed by this sync-back (see stageLocalCopy).
        val stagedHashes = stagedCloudHashes.remove(projectId).orEmpty()
        var written = 0
        var unchanged = 0
        var skipped = 0
        val base = staging.absolutePath
        staging.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.absolutePath.removePrefix(base).trimStart('/')
            if (rel.isEmpty() || rel.split('/').any { it == ".." }) {
                skipped++
                return@forEach
            }
            val data = runCatching { file.readBytes() }.getOrNull() ?: run {
                skipped++
                return@forEach
            }
            val existing = runCatching { fs.readFileBytes(rel) }.getOrNull()
            if (existing != null && existing.contentEquals(data)) {
                unchanged++
                return@forEach
            }
            val stagedHash = stagedHashes[rel]
            if (existing != null && stagedHash != null && sha256Hex(existing) != stagedHash) {
                // The cloud copy changed while Pi was running and differs
                // from what Pi produced: never let the stale staged bytes
                // silently overwrite the newer edit (audit 02). The file
                // is reported as skipped so the caller can surface it.
                skipped++
                return@forEach
            }
            val parent = rel.substringBeforeLast('/', "")
            val ok = runCatching {
                if (parent.isNotEmpty()) fs.mkdirs(parent)
                fs.writeFileBytes(rel, data)
            }.isSuccess
            if (ok) written++ else skipped++
        }
        SyncReport(written, unchanged, skipped)
    }

    suspend fun fileSystemForAsync(projectId: String): ProjectFileSystem {
        val entity = dao.get(projectId) ?: throw IllegalArgumentException("Project not found: $projectId")
        // Never put a null: ConcurrentHashMap.put(null) throws NPE (local
        // projects have a null workspaceUri). Absence == local project.
        entity.workspaceUri?.let { workspaceUris[projectId] = it } ?: workspaceUris.remove(projectId)
        return fileSystemFor(projectId)
    }

    suspend fun create(name: String, type: ProjectType, description: String = "", workspaceUri: Uri? = null, starter: com.anvex.core.engine.ProjectTemplates.Starter = com.anvex.core.engine.ProjectTemplates.Starter.LANDING): RepoResult<Project> =
        withContext(Dispatchers.IO) {
            if (name.isBlank()) return@withContext RepoResult.Err("Invalid name", "Project name cannot be empty.")

            val trimmedName = name.trim()
            if (!isValidWorkspaceName(trimmedName)) {
                return@withContext RepoResult.Err(
                    "Invalid project name",
                    "Use a simple folder-safe name without /, \\, . or .. (maximum 80 characters)."
                )
            }
            val id = UUID.randomUUID().toString()
            // A user-selected folder is the exact project root. Do not create a
            // second child directory inside it: many Android SAF providers reject
            // createDocument() on tree roots even when normal read/write access is
            // valid. This also matches OpenCode-style workspace selection.
            val selectedWorkspaceUri = workspaceUri?.toString()
            var committed = false
            // A folder that already holds files is adopted as-is: nothing is overwritten and no starter is written.
            var adoptedExistingFiles = false
            try {
                val fs: ProjectFileSystem = if (selectedWorkspaceUri != null) {
                    val uri = Uri.parse(selectedWorkspaceUri)
                    verifyWritableWorkspace(uri)
                    val saf = SafProjectFileSystem(context.contentResolver, uri)
                    adoptedExistingFiles = saf.list("").isNotEmpty()
                    saf
                } else {
                    RealProjectFileSystem(File(projectsRoot, id))
                }

                val files: List<Pair<String, String>> =
                    if (adoptedExistingFiles) emptyList() else ProjectTemplates.files(starter, trimmedName)
                files.forEach { (path, content) -> fs.writeFile(path, content) }
                // Adopting an existing folder: one sized traversal serves
                // both the type inference and the size (audit P-02).
                val adoptedSized = if (adoptedExistingFiles) fs.walkWithSizes() else null
                val projectType = if (adoptedSized != null) ProjectType.infer(adoptedSized.map { it.first }) else type

                val project = Project(
                    id, trimmedName, projectType, description = description,
                    sizeBytes = adoptedSized?.let { projectSize(it) } ?: projectSize(fs),
                    workspaceUri = selectedWorkspaceUri,
                    // A folder selected by the user belongs to the user. Anvex
                    // must never delete its contents when the project is removed.
                    workspaceOwnedByApp = false,
                )
                dao.upsert(project.toEntity())
                // selectedWorkspaceUri is null for a plain local project
                // (no folder picked); same null-put NPE guard as above.
                selectedWorkspaceUri?.let { workspaceUris[id] = it } ?: workspaceUris.remove(id)
                committed = true
                RepoResult.Ok(project)
            } catch (e: SecurityException) {
                RepoResult.Err(
                    "Cannot create project here",
                    "The selected folder is not writable. Choose another folder or use App private storage."
                )
            } catch (e: Exception) {
                RepoResult.Err(
                    "Project creation failed",
                    e.message?.takeIf { it.isNotBlank() } ?: "The project could not be created in the selected location."
                )
            } finally {
                // Transactional cleanup: a failed creation must not leave a half-built
                // project directory behind. Only a directory created by this operation
                // may be removed; user-selected existing folders are never touched.
                if (!committed && selectedWorkspaceUri == null) {
                    // Only app-private project directories are owned by Anvex.
                    // A SAF folder selected by the user is never cleaned up here.
                    runCatching { File(projectsRoot, id).deleteRecursively() }
                }
            }
        }

    /** Register an existing user-selected SAF directory as a project workspace. */
    suspend fun openExistingFolder(uri: Uri, displayName: String): RepoResult<Project> = withContext(Dispatchers.IO) {
        try {
            // Do not rely on persistedUriPermissions or provider FLAG_* metadata.
            // Android's ACTION_OPEN_DOCUMENT_TREE grants a valid transient URI
            // permission for the current activity, and some providers expose
            // incomplete flags/persistence support. The workspace is registered
            // first; actual read/write operations remain guarded by SAF exceptions.
            val rootId = DocumentsContract.getTreeDocumentId(uri)
            DocumentsContract.buildDocumentUriUsingTree(uri, rootId)

            dao.findByWorkspaceUri(uri.toString())?.let { existing ->
                // Non-null in practice (matched by this URI), guarded anyway
                // so the map's non-null invariant holds everywhere.
                existing.workspaceUri?.let { workspaceUris[existing.id] = it } ?: workspaceUris.remove(existing.id)
                return@withContext RepoResult.Ok(existing.toDomain())
            }

            val fs = SafProjectFileSystem(context.contentResolver, uri)
            // Existing workspaces are editable by design. Verify actual write
            // access before registering the project so the editor/agent cannot
            // later fail after the user has already entered the workspace.
            verifyWritableWorkspace(uri)
            val sized = fs.walkWithSizes()
            val name = displayName.trim().ifBlank { "Workspace" }
            val project = Project(
                id = UUID.randomUUID().toString(),
                name = name,
                type = ProjectType.infer(sized.map { it.first }),
                sizeBytes = projectSize(sized),
                workspaceUri = uri.toString(),
                workspaceOwnedByApp = false,
            )
            dao.upsert(project.toEntity())
            // Project.workspaceUri is typed String? (set from uri.toString()
            // just above, so non-null at runtime), but the map is now
            // <String, String>: same guard as the other writes, or the
            // compiler rejects this assignment.
            project.workspaceUri?.let { workspaceUris[project.id] = it } ?: workspaceUris.remove(project.id)
            RepoResult.Ok(project)
        } catch (e: SecurityException) {
            RepoResult.Err(
                "Folder access denied",
                "Android did not grant access to this folder. Re-select it and allow access, or choose another folder."
            )
        } catch (e: Exception) {
            RepoResult.Err("Open folder failed", e.message ?: "The selected folder could not be opened.")
        }
    }

    suspend fun rename(projectId: String, newName: String): RepoResult<Unit> {
        if (newName.isBlank()) return RepoResult.Err("Invalid name", "Project name cannot be empty.")
        val existing = dao.get(projectId) ?: return RepoResult.Err("Not found", "Project no longer exists.")
        dao.upsert(existing.copy(name = newName.trim(), updatedAt = System.currentTimeMillis()))
        return RepoResult.Ok(Unit)
    }

    suspend fun delete(projectId: String): RepoResult<Unit> = withContext(Dispatchers.IO) {
        val entity = dao.get(projectId)
            ?: return@withContext RepoResult.Err("Not found", "Project no longer exists.")
        try {
            // Audit 02: rows first, files second. The old order removed the
            // files and only then the database rows; a failure between the
            // two left a live project row pointing at a deleted directory.
            messageDao?.deleteForProject(projectId)
            mutationDao?.deleteForProject(projectId)
            dao.delete(projectId)
            workspaceUris.remove(projectId)
            runCatching { onProjectDeleted?.invoke(projectId) }
            val fileFailure: String? = when {
                entity.workspaceUri == null -> {
                    // App-private workspace: Anvex owns the entire directory.
                    val root = File(projectsRoot, projectId)
                    if (root.exists() && !root.deleteRecursively()) {
                        "Anvex could not remove the private project files."
                    } else null
                }
                entity.workspaceOwnedByApp -> {
                    // Anvex-created SAF workspace: remove only this exact child.
                    val removed = SafProjectFileSystem(context.contentResolver, Uri.parse(entity.workspaceUri)).deleteOwnedRoot()
                    if (!removed) {
                        "Anvex could not remove the project folder. The folder still exists in your storage and can be re-opened from Projects."
                    } else null
                }
                else -> {
                    // Existing/user-selected workspace: detach only. NEVER touch its files.
                    null
                }
            }
            if (fileFailure != null) {
                return@withContext RepoResult.Err("Project removed, files remain", fileFailure)
            }
            RepoResult.Ok(Unit)
        } catch (e: Exception) {
            RepoResult.Err("Delete failed", e.message ?: "The project could not be deleted.")
        }
    }

    suspend fun refreshType(projectId: String) {
        val fs = fileSystemForAsync(projectId)
        // ONE traversal supplies both the type inference and the size
        // (audit P-02: this runs after every finalized chat turn).
        val sized = fs.walkWithSizes()
        val type = ProjectType.infer(sized.map { it.first })
        val entity = dao.get(projectId) ?: return
        dao.upsert(entity.copy(type = type.name, updatedAt = System.currentTimeMillis(), sizeBytes = projectSize(sized)))
    }

    /** v1.1 §23: duplicate a project — files copied, fresh id, "(copy)" suffix. */
    suspend fun duplicate(projectId: String): RepoResult<Project> = withContext(Dispatchers.IO) {
        val source = dao.get(projectId) ?: return@withContext RepoResult.Err("Not found", "Project no longer exists.")
        val srcFs = fileSystemForAsync(projectId)
        val files = srcFs.walk()
        if (files.isEmpty()) {
            return@withContext RepoResult.Err("Empty project", "Nothing to duplicate.")
        }
        val newId = UUID.randomUUID().toString()
        val dstFs = RealProjectFileSystem(File(projectsRoot, newId))
        var copied = 0
        var unreadable = 0
        for (path in files) {
            val bytes = srcFs.readFileBytes(path)
            if (bytes == null) {
                // Audit 02: unreadable files used to be skipped silently and
                // the incomplete copy presented as a complete duplicate.
                unreadable++
                continue
            }
            PathSanitizer.parent(path)?.let(dstFs::mkdirs)
            dstFs.writeFileBytes(path, bytes)
            copied++
        }
        if (unreadable > 0) {
            File(projectsRoot, newId).deleteRecursively()
            return@withContext RepoResult.Err(
                "Duplicate incomplete",
                "$unreadable of ${files.size} file(s) could not be read from the source project, so no copy was created.",
            )
        }
        val project = Project(
            id = newId,
            name = "${source.name} (copy)",
            type = runCatching { ProjectType.valueOf(source.type) }.getOrDefault(ProjectType.UNKNOWN),
            description = source.description,
            sizeBytes = projectSize(dstFs),
            workspaceOwnedByApp = false,
        )
        dao.upsert(project.toEntity())
        RepoResult.Ok(project)
    }

    suspend fun touch(projectId: String) {
        val fs = fileSystemForAsync(projectId)
        dao.touch(projectId, System.currentTimeMillis(), projectSize(fs))
    }

    data class BuildArtifact(
        val file: File,
        val createdAt: Long,
        val variant: String,
    )

    private val buildArtifactsRoot: File = File(context.filesDir, "build-artifacts").apply { mkdirs() }

    /** Persist a verified APK outside the disposable Gradle workspace. */
    suspend fun persistBuildArtifact(projectId: String, variant: String, apk: File): BuildArtifact = withContext(Dispatchers.IO) {
        require(apk.isFile && apk.length() > 0) { "The build artifact is missing or empty." }
        val dir = File(buildArtifactsRoot, projectId).apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val safeVariant = variant.lowercase().replace(Regex("[^a-z0-9_-]"), "-")
        val target = File(dir, "Anvex-${safeVariant}-${stamp}.apk")
        apk.copyTo(target, overwrite = true)
        BuildArtifact(target, stamp, safeVariant)
    }

    fun listBuildArtifacts(projectId: String): List<BuildArtifact> =
        File(buildArtifactsRoot, projectId).listFiles { f -> f.isFile && f.extension.equals("apk", true) }
            ?.map { f ->
                val variant = if (f.name.contains("release", true)) "release" else "debug"
                BuildArtifact(f, f.lastModified(), variant)
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    /** Creates an isolated real-directory snapshot suitable for Gradle/AGP processes. SAF projects are copied to cache; source is never modified. */
    suspend fun materializeForBuild(projectId: String): File = withContext(Dispatchers.IO) {
        val entity = dao.get(projectId) ?: error("Project not found")
        if (entity.workspaceUri == null) return@withContext File(projectsRoot, projectId)
        val src = fileSystemForAsync(projectId)
        val out = File(context.cacheDir, "build-workspaces/$projectId-${System.currentTimeMillis()}").apply { mkdirs() }
        var total = 0L
        var copied = 0
        for ((path, listedSize) in src.walkWithSizes()) {
            if (isBuildIgnored(path)) continue
            val size = listedSize ?: 0L
            if (size > MAX_BUILD_FILE_BYTES) error("Build workspace contains an oversized file: $path")
            total += size
            if (total > MAX_BUILD_WORKSPACE_BYTES) error("Build workspace exceeds the ${MAX_BUILD_WORKSPACE_BYTES / (1024 * 1024)} MB safety limit.")
            val bytes = src.readFileBytes(path) ?: continue
            PathSanitizer.parent(path)?.let { if (it.isNotBlank()) File(out, it).mkdirs() }
            File(out, path).apply { parentFile?.mkdirs(); writeBytes(bytes) }
            if (++copied > MAX_BUILD_FILES) error("Build workspace contains too many files.")
        }
        if (copied == 0) error("Project contains no buildable files.")
        out
    }

    fun cleanupBuildWorkspace(dir: File) {
        if (dir.absolutePath.startsWith(File(context.cacheDir, "build-workspaces").absolutePath)) dir.deleteRecursively()
    }

    suspend fun treeAsync(projectId: String): FileNode = withContext(Dispatchers.IO) {
        val project = dao.get(projectId) ?: throw IllegalArgumentException("Project not found")
        val fs = fileSystemForAsync(projectId)
        buildTree(fs, project.name)
    }

    private fun buildTree(fs: ProjectFileSystem, rootName: String): FileNode {
        var nodeCount = 0
        fun walk(dir: String, depth: Int): FileNode {
            nodeCount++
            if (depth > 64 || nodeCount >= MAX_TREE_NODES) {
                return FileNode(dir, dir.substringAfterLast('/').ifBlank { rootName }, true)
            }
            val children = fs.list(dir).sortedWith(
                compareByDescending<ProjectFileSystem.FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() }
            ).mapNotNull { e ->
                if (nodeCount >= MAX_TREE_NODES) return@mapNotNull null
                val path = if (dir.isBlank()) e.name else "$dir/${e.name}"
                if (e.isDirectory) walk(path, depth + 1) else {
                    nodeCount++
                    FileNode(path, e.name, false, e.size)
                }
            }
            return FileNode(dir, if (dir.isBlank()) rootName else dir.substringAfterLast('/'), true, children = children)
        }
        return walk("", 0)
    }

    private companion object {
        const val MAX_TREE_NODES = 20_000
        const val MAX_BUILD_FILES = 50_000
        const val MAX_BUILD_FILE_BYTES = 512L * 1024L * 1024L
        const val MAX_BUILD_WORKSPACE_BYTES = 1_500L * 1024L * 1024L
    }

    /**
     * Project size from ONE traversal whose entries already carry sizes
     * (audit P-02). The old form walked the tree and then called
     * `fs.fileSize()` per file — on SAF every call re-resolves the path
     * through the documents provider, the same per-file round-trip pattern
     * that made sends take minutes before v4.14.8.
     */
    private fun projectSize(fs: ProjectFileSystem): Long = fs.walkWithSizes().sumOf { it.second ?: 0L }

    /** Size of an already-taken [walkWithSizes] listing (no second traversal). */
    private fun projectSize(sized: List<Pair<String, Long?>>): Long = sized.sumOf { it.second ?: 0L }

    private fun isBuildIgnored(path: String): Boolean {
        val first = path.substringBefore('/')
        return first in setOf(".git", ".gradle", ".idea", "build", ".anvex")
    }

    private fun isValidWorkspaceName(name: String): Boolean {
        if (name.isBlank() || name.length > 80 || name == "." || name == "..") return false
        if (name.any { it.isISOControl() || it == '/' || it == '\\' }) return false
        return true
    }

    /**
     * Verify actual write access without depending on persisted permissions or
     * provider-specific FLAG_* metadata. The probe is created and immediately
     * deleted inside the user-selected folder.
     */
    private fun verifyWritableWorkspace(treeUri: Uri) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val probeName = ".anvex-write-test-${UUID.randomUUID()}"
        val probe = DocumentsContract.createDocument(
            context.contentResolver,
            rootUri,
            "text/plain",
            probeName,
        ) ?: throw SecurityException("Android could not create a file in the selected folder")
        try {
            context.contentResolver.openOutputStream(probe, "wt")?.use { out ->
                out.write(byteArrayOf(0x42))
                out.flush()
            } ?: throw SecurityException("Android could not write to the selected folder")
        } finally {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, probe) }
        }
    }

    /** Export the project as ZIP into a SAF-provided [uri]. */
    suspend fun exportZip(projectId: String, uri: Uri): RepoResult<Int> = withContext(Dispatchers.IO) {
        try {
            val fs = fileSystemForAsync(projectId)
            // M-06: the count must be what actually landed in the archive
            // (secrets are excluded from shared exports), not the walk size.
            var exported = 0
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                ZipCodec.export(fs, out, onEntry = { exported++ })
            } ?: return@withContext RepoResult.Err("Export failed", "Could not open the selected location.")
            RepoResult.Ok(exported)
        } catch (e: Exception) {
            RepoResult.Err("Export failed", e.message ?: "Unknown error")
        }
    }

    /** Import a ZIP from a SAF-provided [uri] into a NEW project. */
    suspend fun importZip(displayName: String, uri: Uri): RepoResult<Project> = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val root = File(projectsRoot, id)
        try {
            val fs: ProjectFileSystem = RealProjectFileSystem(root)
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext RepoResult.Err("Import failed", "Could not read the selected file.")
            val count = stream.use { ZipCodec.import(it, fs) }
            if (count == 0) {
                // Audit 02: the empty path used to leak the created root
                // directory (the catch paths below already delete it).
                root.deleteRecursively()
                return@withContext RepoResult.Err("Import failed", "The archive contains no files (or only directories).")
            }
            val sized = fs.walkWithSizes()
            val project = Project(
                id, displayName.ifBlank { "Imported project" },
                ProjectType.infer(sized.map { it.first }),
                sizeBytes = projectSize(sized),
            )
            dao.upsert(project.toEntity())
            RepoResult.Ok(project)
        } catch (e: ZipCodec.ZipSecurityException) {
            root.deleteRecursively()
            RepoResult.Err("Unsafe archive rejected", e.message ?: "The ZIP file failed security checks.")
        } catch (e: Exception) {
            root.deleteRecursively()
            RepoResult.Err("Import failed", e.message ?: "Unknown error")
        }
    }

    fun cacheSizeBytes(): Long = File(context.cacheDir, "preview").walkTopDown().sumOf { it.length() }

    fun clearCache(): Boolean = File(context.cacheDir, "preview").deleteRecursively()


}

internal fun ProjectEntity.toDomain() = Project(
    id = id, name = name, type = runCatching { ProjectType.valueOf(type) }.getOrDefault(ProjectType.UNKNOWN),
    createdAt = createdAt, updatedAt = updatedAt, description = description, sizeBytes = sizeBytes, workspaceUri = workspaceUri, workspaceOwnedByApp = workspaceOwnedByApp,
)

internal fun Project.toEntity() = ProjectEntity(
    id = id, name = name, type = type.name, description = description,
    createdAt = createdAt, updatedAt = updatedAt, sizeBytes = sizeBytes, workspaceUri = workspaceUri, workspaceOwnedByApp = workspaceOwnedByApp,
)

internal fun sanitizePathOr(path: String): String? = PathSanitizer.sanitizeOrNull(path)
