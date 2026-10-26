package com.anvex.core.android.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val description: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sizeBytes: Long,
    val workspaceUri: String?,
    val workspaceOwnedByApp: Boolean,
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val model: String?,
    val providerId: String?,
    val usageJson: String?,
    val artifactJson: String?,
    val error: String?,
    val attachmentsJson: String?,
    val thinkingLevel: String?,
    /** v4.4: persisted reasoning trace for assistant messages (schema v13). */
    val reasoning: String? = null,
)

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val apiKeyEnc: String?,
    val baseUrl: String,
    val model: String,
    /** Legacy non-secret header JSON retained only for migration compatibility. */
    val extraHeadersJson: String,
    /** Current header storage; encrypted because custom headers can contain credentials. */
    val extraHeadersEnc: String?,
    val temperature: Double?,
    val maxTokens: Int?,
    val inputPricePerMillion: Double?,
    val outputPricePerMillion: Double?,
    val isDefault: Boolean,
    val createdAt: Long,
)

/**
 * v2 (Anvex 1.1): persisted change journal — the file-mutation half of
 * "persist action results" (fix prompt §24). Every AI/user/import mutation is
 * stored (bounded per project by [MutationDao.trim]) so changes can be
 * reviewed, diffed, and reverted even after app restart / process death.
 */
@Entity(tableName = "mutations", indices = [Index("projectId")])
data class MutationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: String,
    val messageId: String?,
    val path: String,
    val operation: String,
    val source: String,
    val beforeContent: String?,
    val afterContent: String?,
    val renamedTo: String?,
    val timestamp: Long,
)


@Entity(tableName = "diagnostic_events", indices = [Index("createdAt"), Index("level")])
data class DiagnosticEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val level: String,
    val message: String,
    val details: String?,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "task_runs", indices = [Index("state"), Index("updatedAt"), Index("nextRunAt")])
data class TaskRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val label: String,
    val state: String,
    val resumable: Boolean,
    val detail: String? = null,
    /** Durable task input. Must never contain secrets. */
    val payloadJson: String? = null,
    /** Durable checkpoint owned by the task handler. Must never contain secrets. */
    val checkpointJson: String? = null,
    val attempt: Int = 0,
    val maxAttempts: Int = 1,
    val cancelRequested: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    /** Earliest wall-clock time at which a queued retry may execute. */
    val nextRunAt: Long? = null,
)

@Entity(tableName = "backup_records", indices = [Index("kind"), Index("createdAt")])
data class BackupRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val path: String,
    val sizeBytes: Long,
    val encrypted: Boolean,
    val portable: Boolean,
    val reason: String?,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "plugins")
data class PluginEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val version: String,
    val source: String,
    val enabled: Boolean,
    val builtIn: Boolean,
    val dependenciesJson: String = "[]",
    val packagePath: String? = null,
    val integritySha256: String? = null,
    val skillInstructions: String? = null,
    val skillCapabilitiesJson: String = "[]",
    val installedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "approval_grants", indices = [Index("sessionId"), Index("expiresAt")])
data class ApprovalGrantEntity(
    @PrimaryKey val sessionId: String,
    val projectId: String?,
    /** Comma-separated ToolCapability enum names; validated by repository. */
    val capabilities: String,
    val resourceFingerprint: String? = null,
    val maxRisk: String = "REQUIRES_APPROVAL",
    val expiresAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "quarantine", indices = [Index("projectId"), Index("createdAt")])
data class QuarantineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalId: String,
    val projectId: String,
    val reason: String,
    val payload: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Query("SELECT * FROM projects WHERE workspaceUri = :workspaceUri LIMIT 1")
    suspend fun findByWorkspaceUri(workspaceUri: String): ProjectEntity?

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    suspend fun all(): List<ProjectEntity>

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE projects SET updatedAt = :ts, sizeBytes = :size WHERE id = :id")
    suspend fun touch(id: String, ts: Long, size: Long)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE projectId = :projectId ORDER BY createdAt ASC")
    fun observeForProject(projectId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE projectId = :projectId ORDER BY createdAt DESC LIMIT 1")
    suspend fun lastForProject(projectId: String): MessageEntity?

    @Query("SELECT * FROM messages ORDER BY createdAt ASC")
    suspend fun allMessages(): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    /** Phase 2 search: project ids whose messages contain [q] (LIKE wildcards escaped by the caller). */
    @Query("SELECT DISTINCT projectId FROM messages WHERE content LIKE '%' || :q || '%' ESCAPE '\\'")
    suspend fun projectIdsMatching(q: String): List<String>

    @Query("SELECT * FROM messages WHERE role = 'ASSISTANT' AND usageJson IS NOT NULL ORDER BY createdAt DESC")
    suspend fun allAssistantUsage(): List<MessageEntity>

    /** v4.4.6: distinct project-less direct-chat conversation ids ("direct/<uuid>"). */
    @Query("SELECT DISTINCT projectId FROM messages WHERE projectId LIKE 'direct/%' ESCAPE '\\' ORDER BY projectId")
    suspend fun directConversationIds(): List<String>

    /** v4.4.6: first message of a conversation (for list titles). */
    @Query("SELECT * FROM messages WHERE projectId = :projectId ORDER BY createdAt ASC LIMIT 1")
    suspend fun firstForProject(projectId: String): MessageEntity?

    /** v4.4.6: message count of a conversation. */
    @Query("SELECT COUNT(*) FROM messages WHERE projectId = :projectId")
    suspend fun countForProject(projectId: String): Int

    /** v4.4.6: newest message timestamp of a conversation (for list sorting). */
    @Query("SELECT MAX(createdAt) FROM messages WHERE projectId = :projectId")
    suspend fun lastMessageTime(projectId: String): Long?
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun get(id: String): ProviderEntity?

    @Query("SELECT * FROM providers WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): ProviderEntity?

    @Query("SELECT * FROM providers ORDER BY createdAt ASC")
    suspend fun all(): List<ProviderEntity>

    @Query("DELETE FROM providers")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE providers SET isDefault = 0")
    suspend fun clearDefault()

    @Update
    suspend fun update(provider: ProviderEntity)
}

@Dao
interface MutationDao {
    @Query("SELECT * FROM mutations WHERE projectId = :projectId ORDER BY timestamp ASC LIMIT :limit")
    suspend fun forProject(projectId: String, limit: Int): List<MutationEntity>

    @Query("SELECT * FROM mutations WHERE projectId = :projectId ORDER BY timestamp ASC LIMIT :limit")
    fun observeForProject(projectId: String, limit: Int): Flow<List<MutationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(mutations: List<MutationEntity>)

    /** Keep only the newest [keep] mutations for a project (bounded journal, §29). */
    @Query(
        "DELETE FROM mutations WHERE projectId = :projectId AND id NOT IN " +
            "(SELECT id FROM mutations WHERE projectId = :projectId ORDER BY timestamp DESC, id DESC LIMIT :keep)"
    )
    suspend fun trim(projectId: String, keep: Int)

    @Query("DELETE FROM mutations WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)

    @Query("SELECT * FROM mutations WHERE projectId = :projectId ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(projectId: String, limit: Int): Flow<List<MutationEntity>>
}


@Dao
interface DiagnosticDao {
    @Insert suspend fun insert(event: DiagnosticEventEntity)
    @Query("SELECT * FROM diagnostic_events ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DiagnosticEventEntity>>
    @Query("SELECT * FROM diagnostic_events ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DiagnosticEventEntity>
    @Query("DELETE FROM diagnostic_events WHERE id NOT IN (SELECT id FROM diagnostic_events ORDER BY createdAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

@Dao
interface TaskDao {
    @Insert suspend fun insert(task: TaskRunEntity): Long
    @Query("SELECT * FROM task_runs ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TaskRunEntity>>
    /** v4.0 fix: suspend snapshot for the LAN/agent status providers. */
    @Query("SELECT * FROM task_runs ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TaskRunEntity>
    @Query("SELECT * FROM task_runs WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): TaskRunEntity?
    @Query("SELECT * FROM task_runs WHERE (state = 'QUEUED' AND (nextRunAt IS NULL OR nextRunAt <= :now)) OR (state = 'RETRYING' AND (nextRunAt IS NULL OR nextRunAt <= :now)) OR (state = 'INTERRUPTED' AND resumable = 1) ORDER BY COALESCE(nextRunAt, createdAt) ASC LIMIT :limit")
    suspend fun queued(now: Long, limit: Int): List<TaskRunEntity>
    @Query("UPDATE task_runs SET state = :state, detail = :detail, updatedAt = :updatedAt, nextRunAt = NULL WHERE id = :id")
    suspend fun setState(id: Long, state: String, detail: String?, updatedAt: Long)
    @Query("UPDATE task_runs SET state = 'RETRYING', detail = :detail, nextRunAt = :nextRunAt, updatedAt = :updatedAt WHERE id = :id AND state = 'RUNNING'")
    suspend fun scheduleRetry(id: Long, detail: String?, nextRunAt: Long, updatedAt: Long): Int
    @Query("UPDATE task_runs SET state = 'RUNNING', attempt = attempt + 1, startedAt = :startedAt, updatedAt = :updatedAt, cancelRequested = 0, nextRunAt = NULL WHERE id = :id AND state IN ('QUEUED','RETRYING','INTERRUPTED')")
    suspend fun claim(id: Long, startedAt: Long, updatedAt: Long): Int
    @Query("UPDATE task_runs SET state = 'INTERRUPTED', updatedAt = :ts, detail = 'Process ended while task was running' WHERE state = 'RUNNING'")
    suspend fun markRunningInterrupted(ts: Long = System.currentTimeMillis())
    @Query("UPDATE task_runs SET state = 'QUEUED', updatedAt = :ts, cancelRequested = 0, nextRunAt = NULL WHERE id = :id AND state IN ('INTERRUPTED','PAUSED','WAITING_APPROVAL','RETRYING')")
    suspend fun restart(id: Long, ts: Long): Int
    @Query("UPDATE task_runs SET state = 'PAUSED', detail = :detail, updatedAt = :updatedAt WHERE id = :id AND state = 'RUNNING'")
    suspend fun pause(id: Long, detail: String?, updatedAt: Long): Int
    @Query("UPDATE task_runs SET checkpointJson = :checkpoint, detail = :detail, updatedAt = :updatedAt WHERE id = :id AND state = 'RUNNING'")
    suspend fun checkpoint(id: Long, checkpoint: String?, detail: String?, updatedAt: Long): Int
    @Query("UPDATE task_runs SET cancelRequested = 1, state = CASE WHEN state = 'QUEUED' THEN 'CANCELLED' ELSE state END, updatedAt = :updatedAt, finishedAt = CASE WHEN state = 'QUEUED' THEN :updatedAt ELSE finishedAt END WHERE id = :id AND state IN ('QUEUED','RUNNING','INTERRUPTED','RETRYING')")
    suspend fun requestCancel(id: Long, updatedAt: Long): Int
    @Query("UPDATE task_runs SET state = :state, detail = :detail, updatedAt = :updatedAt, finishedAt = :finishedAt WHERE id = :id")
    suspend fun finish(id: Long, state: String, detail: String?, updatedAt: Long, finishedAt: Long)
}

@Dao
interface BackupDao {
    @Insert suspend fun insert(record: BackupRecordEntity): Long
    @Query("SELECT * FROM backup_records ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BackupRecordEntity>>
    @Query("SELECT * FROM backup_records WHERE kind = 'AUTOMATIC' ORDER BY createdAt DESC")
    suspend fun allAutomatic(): List<BackupRecordEntity>
    @Query("DELETE FROM backup_records WHERE id = :id")
    suspend fun delete(id: Long)
    @Query("DELETE FROM backup_records WHERE kind = 'AUTOMATIC' AND id NOT IN (SELECT id FROM backup_records WHERE kind = 'AUTOMATIC' ORDER BY createdAt DESC LIMIT :keep)")
    suspend fun trimAutomatic(keep: Int)
}

@Dao
interface PluginDao {
    @Query("SELECT * FROM plugins ORDER BY builtIn DESC, name ASC")
    fun observeAll(): Flow<List<PluginEntity>>
    @Query("SELECT * FROM plugins")
    suspend fun all(): List<PluginEntity>
    @Query("SELECT * FROM plugins WHERE id = :id LIMIT 1")
    suspend fun get(id: String): PluginEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(plugin: PluginEntity)
    @Query("UPDATE plugins SET enabled = :enabled WHERE id = :id AND builtIn = 0")
    suspend fun setEnabled(id: String, enabled: Boolean)
    @Query("DELETE FROM plugins WHERE id = :id AND builtIn = 0")
    suspend fun delete(id: String)
}

@Dao
interface ApprovalGrantDao {
    @Query("SELECT * FROM approval_grants WHERE sessionId = :sessionId LIMIT 1")
    suspend fun get(sessionId: String): ApprovalGrantEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(grant: ApprovalGrantEntity)

    @Query("SELECT * FROM approval_grants ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ApprovalGrantEntity>
    @Query("DELETE FROM approval_grants WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)

    @Query("DELETE FROM approval_grants WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int

}

@Dao
interface QuarantineDao {
    @Insert suspend fun insert(item: QuarantineEntity)
    @Query("SELECT * FROM quarantine WHERE originalId = :originalId ORDER BY createdAt DESC LIMIT 1")
    suspend fun findByOriginalId(originalId: String): QuarantineEntity?
    @Query("SELECT * FROM quarantine ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<QuarantineEntity>>
}

@Database(
    entities = [
        ProjectEntity::class, MessageEntity::class, ProviderEntity::class,
        MutationEntity::class, DiagnosticEventEntity::class, TaskRunEntity::class,
        BackupRecordEntity::class, PluginEntity::class, QuarantineEntity::class, ApprovalGrantEntity::class,
    ],
    version = 13,
    exportSchema = true,
)
abstract class AnvexDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun messageDao(): MessageDao
    abstract fun providerDao(): ProviderDao
    abstract fun mutationDao(): MutationDao
    abstract fun diagnosticDao(): DiagnosticDao
    abstract fun taskDao(): TaskDao
    abstract fun backupDao(): BackupDao
    abstract fun pluginDao(): PluginDao
    abstract fun quarantineDao(): QuarantineDao
    abstract fun approvalGrantDao(): ApprovalGrantDao

    companion object {
        /**
         * v1 -> v2 statements: adds the `mutations` change-journal table (new in
         * 1.1). Purely additive — no existing column changes, all v1 data
         * preserved. Kept as a list so JVM tests execute the exact production
         * SQL against a real SQLite engine (xerial sqlite-jdbc) and verify data
         * preservation (v1.1 §5/§31).
         */
        val MIGRATION_1_2_STATEMENTS = listOf(
            "CREATE TABLE IF NOT EXISTS `mutations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`projectId` TEXT NOT NULL, `messageId` TEXT, `path` TEXT NOT NULL, " +
                "`operation` TEXT NOT NULL, `source` TEXT NOT NULL, `beforeContent` TEXT, " +
                "`afterContent` TEXT, `renamedTo` TEXT, `timestamp` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_mutations_projectId` ON `mutations` (`projectId`)",
        )

        /**
         * Explicit migration chain (v1.1 P0-3 fix). NO destructive fallback:
         * user projects, conversations, and provider configs must survive every
         * upgrade. A missing future migration now fails loudly (IllegalStateException)
         * at open time instead of silently wiping data. Covered by
         * core/android/src/test/.../MigrationTest.kt against a real SQLite engine.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2_STATEMENTS.forEach(db::execSQL)
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `projects` ADD COLUMN `workspaceUri` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `attachmentsJson` TEXT")
            }
        }

        /** v3 -> v4: ownership is explicit. Existing SAF workspaces default to false
         * so an upgrade can never accidentally delete a user-owned folder. */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `projects` ADD COLUMN `workspaceOwnedByApp` INTEGER NOT NULL DEFAULT 0")
            }
        }


        /** v4 -> v5: reasoning preference, usage pricing metadata, and message reasoning label. */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `providers` ADD COLUMN `inputPricePerMillion` REAL")
                db.execSQL("ALTER TABLE `providers` ADD COLUMN `outputPricePerMillion` REAL")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `thinkingLevel` TEXT")
            }
        }
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `diagnostic_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `category` TEXT NOT NULL, `level` TEXT NOT NULL, `message` TEXT NOT NULL, `details` TEXT, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_diagnostic_events_createdAt` ON `diagnostic_events` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_diagnostic_events_level` ON `diagnostic_events` (`level`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `task_runs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `label` TEXT NOT NULL, `state` TEXT NOT NULL, `resumable` INTEGER NOT NULL, `detail` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_runs_state` ON `task_runs` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_runs_updatedAt` ON `task_runs` (`updatedAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `backup_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `path` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `encrypted` INTEGER NOT NULL, `portable` INTEGER NOT NULL, `reason` TEXT, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_backup_records_kind` ON `backup_records` (`kind`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_backup_records_createdAt` ON `backup_records` (`createdAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `plugins` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `version` TEXT NOT NULL, `source` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `builtIn` INTEGER NOT NULL, `installedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `quarantine` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `originalId` TEXT NOT NULL, `projectId` TEXT NOT NULL, `reason` TEXT NOT NULL, `payload` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_quarantine_projectId` ON `quarantine` (`projectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_quarantine_createdAt` ON `quarantine` (`createdAt`)")
            }
        }
        /** v6 -> v7: encrypt provider extra headers. Legacy JSON remains readable for upgrade. */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `providers` ADD COLUMN `extraHeadersEnc` TEXT")
            }
        }

        /** v7 -> v8: durable task state machine, payload/checkpoint and cancellation metadata. */
        val MIGRATION_7_8_STATEMENTS = listOf(
            "ALTER TABLE `task_runs` ADD COLUMN `payloadJson` TEXT",
            "ALTER TABLE `task_runs` ADD COLUMN `checkpointJson` TEXT",
            "ALTER TABLE `task_runs` ADD COLUMN `attempt` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `task_runs` ADD COLUMN `maxAttempts` INTEGER NOT NULL DEFAULT 1",
            "ALTER TABLE `task_runs` ADD COLUMN `cancelRequested` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `task_runs` ADD COLUMN `startedAt` INTEGER",
            "ALTER TABLE `task_runs` ADD COLUMN `finishedAt` INTEGER",
        )
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_7_8_STATEMENTS.forEach(db::execSQL)
            }
        }

        /** v8 -> v9: durable session/project-scoped approval grants with expiry. */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `approval_grants` (`sessionId` TEXT NOT NULL, `projectId` TEXT, `capabilities` TEXT NOT NULL, `expiresAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`sessionId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_approval_grants_sessionId` ON `approval_grants` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_approval_grants_expiresAt` ON `approval_grants` (`expiresAt`)")
            }
        }

        /** v9 -> v10: durable retry scheduling. Existing tasks remain immediately runnable. */
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `task_runs` ADD COLUMN `nextRunAt` INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_runs_nextRunAt` ON `task_runs` (`nextRunAt`)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `plugins` ADD COLUMN `dependenciesJson` TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /** v11 -> v12: plugin package/integrity + executable declarative skill metadata + resource-scoped approval. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `plugins` ADD COLUMN `packagePath` TEXT")
                db.execSQL("ALTER TABLE `plugins` ADD COLUMN `integritySha256` TEXT")
                db.execSQL("ALTER TABLE `plugins` ADD COLUMN `skillInstructions` TEXT")
                db.execSQL("ALTER TABLE `plugins` ADD COLUMN `skillCapabilitiesJson` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `approval_grants` ADD COLUMN `resourceFingerprint` TEXT")
                db.execSQL("ALTER TABLE `approval_grants` ADD COLUMN `maxRisk` TEXT NOT NULL DEFAULT 'REQUIRES_APPROVAL'")
            }
        }

        /** v12 -> v13: reasoning trace for assistant messages (architect feature 1: extended thinking). */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `reasoning` TEXT")
            }
        }

        val ALL_MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
    }
}
