package com.anvex.core.android.reliability

import kotlinx.coroutines.flow.map
import com.anvex.core.model.TaskRunItem
import android.content.Context
import com.anvex.core.android.KeystoreCrypto
import com.anvex.core.android.build.ToolchainManager
import com.anvex.core.android.db.*
import com.anvex.core.android.repo.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Safe, durable reliability services. Imported plugin manifests are metadata only; no code is executed. */
class ReliabilityRepository(
    private val context: Context,
    private val db: AnvexDatabase,
    private val settings: SettingsRepository,
    private val toolchain: ToolchainManager,
    private val taskEngine: DurableTaskEngine,
) {
    private val root = File(context.filesDir, "reliability").apply { mkdirs() }
    private val autoBackupDir = File(root, "auto-backups").apply { mkdirs() }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }
    private val snapshotWriteLock = Any()
    private val backupMigration = AdvancedBackupMigration()

    companion object {
        private const val MAX_PLUGIN_MANIFEST_BYTES = 1_000_000
        private const val MAX_PLUGIN_COUNT = 100
        private const val MAX_SNAPSHOT_BYTES = 2_000_000
        private const val MAX_SNAPSHOT_PROJECTS = 500
        private const val MAX_SNAPSHOT_TASKS = 1_000
        private const val MAX_SNAPSHOT_APPROVALS = 500
        private const val MAX_DIAGNOSTIC_DETAILS = 8_000
    }

    init {
        taskEngine.register("repair") { task ->
            when (task.task.label) {
                "Clear preview cache" -> {
                    clearPreviewCache()
                    DurableTaskEngine.Result.Success("Preview cache cleared")
                }
                "Clean stale build workspaces" -> {
                    val removed = cleanupStaleBuildWorkspaces()
                    DurableTaskEngine.Result.Success("Removed $removed stale build workspace(s)")
                }
                "Quarantine corrupt sessions" -> {
                    val count = quarantineCorruptSessions()
                    DurableTaskEngine.Result.Success("Quarantined $count corrupt session(s)")
                }
                else -> DurableTaskEngine.Result.Failure("Unknown repair: ${task.task.label}")
            }
        }
    }

    fun observeEvents(limit: Int = 200): Flow<List<DiagnosticEventEntity>> = db.diagnosticDao().observeRecent(limit)
    fun observeTasks(limit: Int = 100): Flow<List<TaskRunEntity>> = db.taskDao().observeRecent(limit)

    /** UI view of recent tasks: no Room types cross into presentation. */
    fun observeTaskItems(limit: Int = 100): Flow<List<TaskRunItem>> =
        observeTasks(limit).map { list -> list.map { it.toItem() } }
    fun observeBackups(limit: Int = 20): Flow<List<BackupRecordEntity>> = db.backupDao().observeRecent(limit)
    fun observePlugins(): Flow<List<PluginEntity>> = db.pluginDao().observeAll()
    fun observeQuarantine(): Flow<List<QuarantineEntity>> = db.quarantineDao().observeRecent(100)

    suspend fun log(category: String, level: String, message: String, details: String? = null) {
        val safeCategory = category.trim().take(80)
        val safeLevel = level.trim().uppercase().take(20)
        val safeMessage = message.take(2_000)
        val safeDetails = details?.take(MAX_DIAGNOSTIC_DETAILS)
        db.diagnosticDao().insert(DiagnosticEventEntity(category = safeCategory, level = safeLevel, message = safeMessage, details = safeDetails))
        db.diagnosticDao().trim(5000)
    }

    /** Compatibility entry point for existing callers. New work must use the durable queue. */
    suspend fun startTask(type: String, label: String, resumable: Boolean = true): Long =
        taskEngine.enqueue(type, label, resumable = resumable, maxAttempts = 3)

    suspend fun finishTask(id: Long, success: Boolean, detail: String? = null) {
        db.taskDao().finish(id, if (success) "COMPLETED" else "FAILED", detail, System.currentTimeMillis(), System.currentTimeMillis())
    }

    suspend fun markInterruptedTasksResumable() = taskEngine.markProcessInterrupted()

    suspend fun enqueueRepair(label: String): Long =
        taskEngine.enqueue("repair", label, resumable = true, maxAttempts = 2)

    suspend fun cancelTask(id: Long): Boolean = taskEngine.requestCancellation(id)

    suspend fun pauseTask(id: Long): Boolean = taskEngine.pause(id)

    suspend fun resumeTask(id: Long): Boolean = taskEngine.resume(id)

    /** Messages that fail domain decoding are moved aside instead of disappearing silently. */
    suspend fun quarantineCorruptSessions(): Int = withContext(Dispatchers.IO) {
        val rows = db.messageDao().allMessages()
        var count = 0
        rows.forEach { row ->
            val valid = runCatching {
                com.anvex.core.model.MessageRole.valueOf(row.role)
                row.usageJson?.let { json.decodeFromString<com.anvex.core.model.Usage>(it) }
                row.artifactJson?.let { json.decodeFromString<com.anvex.core.model.ArtifactSummary>(it) }
                row.attachmentsJson?.let { json.decodeFromString<List<com.anvex.core.model.AttachmentMeta>>(it) }
                true
            }.getOrDefault(false)
            if (!valid) {
                val alreadyQuarantined = db.quarantineDao().findByOriginalId(row.id) != null
                if (!alreadyQuarantined) {
                    db.quarantineDao().insert(QuarantineEntity(
                        originalId = row.id,
                        projectId = row.projectId,
                        reason = "Message payload failed integrity decoding",
                        payload = buildString {
                            append("id=").append(row.id)
                            append(";projectId=").append(row.projectId)
                            append(";role=").append(row.role)
                            append(";contentLength=").append(row.content.length)
                        },
                    ))
                    db.messageDao().delete(row.id)
                    count++
                }
            }
        }
        if (count > 0) log("recovery", "WARN", "$count corrupt message(s) quarantined")
        count
    }

    /** Local automatic recovery snapshot. Credentials are intentionally excluded. */
    suspend fun createAutomaticSnapshot(reason: String = "automatic"): BackupRecordEntity = withContext(Dispatchers.IO) {
        val payload = AutoSnapshot(
            version = 3,
            reason = reason.take(200),
            createdAt = System.currentTimeMillis(),
            settings = json.encodeToString(settings.settings.first()),
            projects = db.projectDao().all().take(MAX_SNAPSHOT_PROJECTS).map { SnapshotProject(it.id, it.name.take(200), it.type.take(80), it.updatedAt, it.sizeBytes) },
            tasks = db.taskDao().recent(MAX_SNAPSHOT_TASKS).map { SnapshotTask(it.id, it.type.take(80), it.label.take(200), it.state.take(40), it.resumable, it.attempt, it.maxAttempts, it.cancelRequested, it.createdAt, it.updatedAt, it.startedAt, it.finishedAt, it.nextRunAt) },
            approvals = db.approvalGrantDao().recent(MAX_SNAPSHOT_APPROVALS).map { SnapshotApproval(it.sessionId.take(160), it.projectId?.take(160), it.capabilities.take(500), it.expiresAt, it.createdAt) },
        )
        val encodedPayload = json.encodeToString(payload)
        require(encodedPayload.toByteArray(Charsets.UTF_8).size <= MAX_SNAPSHOT_BYTES) { "Automatic snapshot is too large." }
        val encrypted = KeystoreCrypto.encrypt(encodedPayload)
        val file = File(autoBackupDir, "auto-${System.currentTimeMillis()}-${UUID.randomUUID()}.bdbackup")
        val tmp = File(autoBackupDir, ".${file.name}.tmp")
        synchronized(snapshotWriteLock) {
            tmp.writeText(encrypted)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                error("Could not commit automatic snapshot.")
            }
        }
        val record = BackupRecordEntity(kind = "AUTOMATIC", path = file.absolutePath, sizeBytes = file.length(), encrypted = true, portable = false, reason = reason.take(200))
        db.backupDao().insert(record)
        rotateAutomaticBackups(3)
        record
    }

    suspend fun inspectBackupFile(file: File): BackupInspection = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext BackupInspection(false, "Backup file does not exist.", 0)
        if (file.length() > MAX_SNAPSHOT_BYTES * 2L) return@withContext BackupInspection(false, "Backup file is too large.", 0)
        runCatching {
            val snap = json.decodeFromString<AutoSnapshot>(KeystoreCrypto.decrypt(file.readText()))
            require(snap.version in 1..3) { "Unsupported snapshot version" }
            require(snap.projects.size <= MAX_SNAPSHOT_PROJECTS && snap.tasks.size <= MAX_SNAPSHOT_TASKS && snap.approvals.size <= MAX_SNAPSHOT_APPROVALS)
            BackupInspection(true, "Valid encrypted local recovery snapshot", snap.projects.size)
        }.getOrElse { BackupInspection(false, "Backup could not be decrypted or is corrupt.", 0) }
    }

    /** Migrates legacy encrypted automatic snapshots using the shared migration authority. */
    suspend fun migrateAutomaticSnapshot(file: File): Boolean = withContext(Dispatchers.IO) {
        backupMigration.migrateAutomaticSnapshot(file)
    }

    suspend fun latestAutomaticBackup(): BackupRecordEntity? = db.backupDao().allAutomatic().firstOrNull()

    suspend fun rotateAutomaticBackups(keep: Int = 3) = withContext(Dispatchers.IO) {
        val safeKeep = keep.coerceIn(1, 10)
        autoBackupDir.listFiles()?.filter { it.isFile && it.extension == "bdbackup" }?.sortedByDescending { it.lastModified() }?.drop(safeKeep)?.forEach { it.delete() }
        db.backupDao().allAutomatic().filter { !File(it.path).isFile }.forEach { db.backupDao().delete(it.id) }
        db.backupDao().trimAutomatic(safeKeep)
    }

    suspend fun cleanupStaleBuildWorkspaces(): Int = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "build-workspaces")
        if (!dir.isDirectory) return@withContext 0
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        var removed = 0
        dir.listFiles()?.forEach { child -> if (child.lastModified() < cutoff && child.deleteRecursively()) removed++ }
        if (removed > 0) log("repair", "INFO", "Removed $removed stale build workspace(s)")
        removed
    }

    suspend fun clearPreviewCache(): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "preview")
        val ok = !dir.exists() || dir.deleteRecursively()
        log("repair", if (ok) "INFO" else "ERROR", if (ok) "Preview cache cleared" else "Preview cache could not be cleared")
        ok
    }

    suspend fun runRepairCenter(): List<RepairItem> = withContext(Dispatchers.IO) {
        buildList {
            val preview = File(context.cacheDir, "preview")
            if (preview.exists()) add(RepairItem("preview-cache", "Preview cache", "Clear bounded preview cache", true))
            val build = File(context.cacheDir, "build-workspaces")
            if (build.isDirectory && build.listFiles().orEmpty().any { it.lastModified() < System.currentTimeMillis() - 24L * 60 * 60 * 1000 }) {
                add(RepairItem("stale-build", "Build workspaces", "Remove stale temporary build workspaces", true))
            }
            add(RepairItem("sessions", "Chat sessions", "Quarantine malformed persisted sessions", true))
        }
    }

    suspend fun runHealthCheck(): List<HealthItem> {
        val providers = db.providerDao().observeAll().first()
        val projects = db.projectDao().observeAll().first()
        val checks = listOf(
            HealthItem("Workspace database", true, "Room database is reachable."),
            HealthItem("Projects", true, "${projects.size} project(s) registered."),
            HealthItem("AI providers", providers.isNotEmpty(), if (providers.isEmpty()) "No AI provider configured." else "${providers.size} provider(s) configured."),
            HealthItem("Android toolchain", toolchain.isInstalled(), toolchainSummary()),
            HealthItem("Preview cache", true, "Cache is isolated under app cache."),
            HealthItem("Backup store", autoBackupDir.isDirectory, "Automatic snapshots are Keystore-encrypted and credential-free."),
            HealthItem("Security", true, "Diagnostics never persist API keys or secret values."),
        )
        quarantineCorruptSessions()
        log("health", if (checks.all { it.ok }) "INFO" else "WARN", "Health check completed")
        return checks
    }

    private fun toolchainSummary(): String {
        val status = toolchain.status()
        val m = status.manifest
        return if (status.valid && m != null) {
            "Imported bundle: JDK ${m.jdkVersion.ifBlank { "?" }}, Gradle ${m.gradleVersion.ifBlank { "?" }}, ABI ${m.abi.ifBlank { "?" }}."
        } else {
            "No valid toolchain imported${if (status.problems.isNotEmpty()) ": " + status.problems.first() else "."}"
        }
    }

    /** v4.0 fix: expose the automatic backup dir for the Developer Center (was stripped from the packaged source). */
    fun automaticBackupDirectory(): java.io.File = autoBackupDir

    suspend fun registerBuiltInPlugins() {
        installBuiltInPlugin("workspace-health", "Workspace Health", "Diagnose database, storage, providers and toolchain state.")
        installBuiltInPlugin("safe-recovery", "Safe Recovery", "Repair cache/build workspace state without executing external code.")
        installBuiltInPlugin("project-audit", "Project Audit", "Check project metadata and recover malformed chat sessions.")
    }

    private suspend fun installBuiltInPlugin(id: String, name: String, description: String) {
        db.pluginDao().upsert(PluginEntity(id = id, name = name, description = description, version = "1.0", source = "BUILT_IN", enabled = true, builtIn = true))
    }

    suspend fun setPluginEnabled(id: String, enabled: Boolean) {
        val plugin = db.pluginDao().get(id) ?: error("Plugin not found: $id")
        if (plugin.builtIn && !enabled) error("Built-in plugins cannot be disabled.")
        val all = db.pluginDao().all().associateBy { it.id }
        val deps = runCatching { json.decodeFromString<List<String>>(plugin.dependenciesJson) }.getOrDefault(emptyList())
        if (enabled) {
            deps.forEach { spec ->
                val depId = spec.trim().split(Regex("\\s*(?:>=|<=|>|<|=)"), limit = 2).first()
                val dep = all[depId] ?: error("Missing plugin dependency: $depId")
                require(versionSatisfies(dep.version, spec.removePrefix(depId).trim())) { "Dependency $depId does not satisfy '$spec'." }
                require(dep.enabled || dep.builtIn) { "Dependency $depId must be enabled first." }
            }
        } else {
            val activeDependents = all.values.filter { it.enabled && runCatching { json.decodeFromString<List<String>>(it.dependenciesJson) }.getOrDefault(emptyList()).any { spec -> spec.trim().split(Regex("\\s*(?:>=|<=|>|<|=)"), limit = 2).first() == id } }
            require(activeDependents.isEmpty()) { "Cannot disable plugin while active dependents exist." }
        }
        db.pluginDao().setEnabled(id, enabled)
        log("plugin", "INFO", "Plugin ${if (enabled) "enabled" else "disabled"}", id.take(200))
    }

    private fun versionSatisfies(version: String, constraint: String): Boolean {
        if (constraint.isBlank()) return true
        val a = version.split('.').mapNotNull { it.toIntOrNull() }.let { it + List((3 - it.size).coerceAtLeast(0)) { 0 } }.take(3)
        val b = constraint.replace(Regex("^(>=|<=|>|<|=)"), "").trim().split('.').mapNotNull { it.toIntOrNull() }.let { it + List((3 - it.size).coerceAtLeast(0)) { 0 } }.take(3)
        val cmp = a.zip(b).fold(0) { acc, pair -> if (acc != 0) acc else pair.first.compareTo(pair.second) }
        return when { constraint.startsWith(">=") -> cmp >= 0; constraint.startsWith("<=") -> cmp <= 0; constraint.startsWith(">") -> cmp > 0; constraint.startsWith("<") -> cmp < 0; else -> cmp == 0 }
    }

    suspend fun removePlugin(id: String): Boolean {
        val p = db.pluginDao().get(id) ?: return false
        if (p.builtIn) return false
        db.pluginDao().delete(id)
        log("plugin", "INFO", "Skill removed", p.name.take(200))
        return true
    }

    suspend fun exportPluginManifest(): String = json.encodeToString(db.pluginDao().all())

    suspend fun importPluginManifest(text: String): Int {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_PLUGIN_MANIFEST_BYTES) { "Skill manifest is too large." }
        val items = json.decodeFromString<List<PluginEntity>>(text)
        require(items.size <= MAX_PLUGIN_COUNT) { "Skill manifest contains too many entries." }
        items.filter { !it.builtIn }.forEach { item ->
            require(item.id.length <= 200 && item.name.length <= 200 && item.version.length <= 100 && item.description.length <= 2_000) {
                "Skill manifest contains an invalid entry."
            }
            val id = item.id.ifBlank { UUID.randomUUID().toString() }
            val existing = db.pluginDao().get(id)
            require(existing?.builtIn != true) { "Skill ID '$id' is reserved by a built-in skill." }
            db.pluginDao().upsert(item.copy(
                id = id,
                source = "IMPORTED",
                builtIn = false,
                enabled = false,
            ))
        }
        log("plugin", "INFO", "Imported ${items.count { !it.builtIn }} skill manifest(s)")
        return items.count { !it.builtIn }
    }

    @Serializable data class AutoSnapshot(
        val version: Int, val reason: String, val createdAt: Long, val settings: String,
        val projects: List<SnapshotProject>, val tasks: List<SnapshotTask> = emptyList(),
        val approvals: List<SnapshotApproval> = emptyList(),
    )
    @Serializable data class SnapshotProject(val id: String, val name: String, val type: String, val updatedAt: Long, val sizeBytes: Long)
    @Serializable data class SnapshotTask(
        val id: Long, val type: String, val label: String, val state: String, val resumable: Boolean,
        val attempt: Int, val maxAttempts: Int, val cancelRequested: Boolean, val createdAt: Long, val updatedAt: Long,
        val startedAt: Long? = null, val finishedAt: Long? = null, val nextRunAt: Long? = null,
    )
    @Serializable data class SnapshotApproval(val sessionId: String, val projectId: String?, val capabilities: String, val expiresAt: Long, val createdAt: Long)
    data class BackupInspection(val valid: Boolean, val message: String, val projectCount: Int)
    data class RepairItem(val id: String, val name: String, val detail: String, val repairable: Boolean)
    data class HealthItem(val name: String, val ok: Boolean, val detail: String)
}

/** Project id is stored inside the durable checkpoint as `project=<id>;...`. */
fun TaskRunEntity.toItem(): TaskRunItem = TaskRunItem(
    id = id,
    type = type,
    label = label,
    state = state,
    detail = detail,
    projectId = checkpointJson?.split(';')?.firstOrNull { it.startsWith("project=") }?.substringAfter('='),
    attempt = attempt,
    maxAttempts = maxAttempts,
    nextRunAt = nextRunAt,
    resumable = resumable,
)
