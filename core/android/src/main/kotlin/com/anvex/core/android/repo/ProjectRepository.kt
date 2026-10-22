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

class ProjectRepository(
    private val context: Context,
    private val dao: com.anvex.core.android.db.ProjectDao,
    private val messageDao: com.anvex.core.android.db.MessageDao? = null,
    private val mutationDao: com.anvex.core.android.db.MutationDao? = null,
) {
    private val projectsRoot: File = File(context.filesDir, "projects").apply { mkdirs() }
    private val workspaceUris = java.util.concurrent.ConcurrentHashMap<String, String?>()

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

    suspend fun fileSystemForAsync(projectId: String): ProjectFileSystem {
        val entity = dao.get(projectId) ?: throw IllegalArgumentException("Project not found: $projectId")
        workspaceUris[projectId] = entity.workspaceUri
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
                val projectType = if (adoptedExistingFiles) ProjectType.infer(fs.walk()) else type

                val project = Project(
                    id, trimmedName, projectType, description = description,
                    sizeBytes = projectSize(fs),
                    workspaceUri = selectedWorkspaceUri,
                    // A folder selected by the user belongs to the user. Anvex
                    // must never delete its contents when the project is removed.
                    workspaceOwnedByApp = false,
                )
                dao.upsert(project.toEntity())
                workspaceUris[id] = selectedWorkspaceUri
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
                workspaceUris[existing.id] = existing.workspaceUri
                return@withContext RepoResult.Ok(existing.toDomain())
            }

            val fs = SafProjectFileSystem(context.contentResolver, uri)
            // Existing workspaces are editable by design. Verify actual write
            // access before registering the project so the editor/agent cannot
            // later fail after the user has already entered the workspace.
            verifyWritableWorkspace(uri)
            val files = fs.walk()
            val name = displayName.trim().ifBlank { "Workspace" }
            val project = Project(
                id = UUID.randomUUID().toString(),
                name = name,
                type = ProjectType.infer(files),
                sizeBytes = projectSize(fs),
                workspaceUri = uri.toString(),
                workspaceOwnedByApp = false,
            )
            dao.upsert(project.toEntity())
            workspaceUris[project.id] = project.workspaceUri
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
            when {
                entity.workspaceUri == null -> {
                    // App-private workspace: Anvex owns the entire directory.
                    val root = File(projectsRoot, projectId)
                    if (root.exists() && !root.deleteRecursively()) {
                        return@withContext RepoResult.Err("Delete failed", "Anvex could not remove the private project files.")
                    }
                }
                entity.workspaceOwnedByApp -> {
                    // Anvex-created SAF workspace: remove only this exact child.
                    val removed = SafProjectFileSystem(context.contentResolver, Uri.parse(entity.workspaceUri)).deleteOwnedRoot()
                    if (!removed) {
                        return@withContext RepoResult.Err(
                            "Delete failed",
                            "Anvex could not remove the project folder. Your files were left untouched."
                        )
                    }
                }
                else -> {
                    // Existing/user-selected workspace: detach only. NEVER touch its files.
                }
            }
            messageDao?.deleteForProject(projectId)
            mutationDao?.deleteForProject(projectId)
            dao.delete(projectId)
            workspaceUris.remove(projectId)
            RepoResult.Ok(Unit)
        } catch (e: Exception) {
            RepoResult.Err("Delete failed", e.message ?: "The project could not be deleted.")
        }
    }

    suspend fun refreshType(projectId: String) {
        val fs = fileSystemForAsync(projectId)
        val files = fs.walk()
        val type = ProjectType.infer(files)
        val entity = dao.get(projectId) ?: return
        dao.upsert(entity.copy(type = type.name, updatedAt = System.currentTimeMillis(), sizeBytes = projectSize(fs)))
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
        for (path in files) {
            srcFs.readFileBytes(path)?.let { bytes ->
                PathSanitizer.parent(path)?.let(dstFs::mkdirs)
                dstFs.writeFileBytes(path, bytes)
                copied++
            }
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
        for (path in src.walk()) {
            if (isBuildIgnored(path)) continue
            val size = src.fileSize(path) ?: 0L
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

    private fun projectSize(fs: ProjectFileSystem): Long = fs.walk().sumOf { fs.fileSize(it) ?: 0L }

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
            val n = fs.walk().size
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                ZipCodec.export(fs, out)
            } ?: return@withContext RepoResult.Err("Export failed", "Could not open the selected location.")
            RepoResult.Ok(n)
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
                return@withContext RepoResult.Err("Import failed", "The archive contains no files (or only directories).")
            }
            val files = fs.walk()
            val project = Project(
                id, displayName.ifBlank { "Imported project" },
                ProjectType.infer(files),
                sizeBytes = projectSize(fs),
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
