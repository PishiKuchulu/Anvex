package com.anvex.core.android.runtime.rag

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.anvex.core.android.AppGraph
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ragDataStore: DataStore<Preferences> by preferencesDataStore(name = "rag_prefs")

/**
 * Facade for local RAG (P7/F6): per-project enablement (default OFF,
 * plan §6), model lifecycle, WorkManager scheduling, the indexer, and
 * semantic search used by the `search_code` tool.
 */
class RagManager(
    private val context: Context,
    private val gateway: com.anvex.core.android.runtime.AndroidRuntimeGateway,
) {
    val models: RagModelManager = RagModelManager(context)

    private val enabledKey = stringSetPreferencesKey("enabled_projects")

    fun isEnabled(projectId: String): Flow<Boolean> =
        context.ragDataStore.data.map { prefs -> projectId in (prefs[enabledKey] ?: emptySet()) }

    suspend fun setEnabled(projectId: String, enabled: Boolean) {
        context.ragDataStore.edit { prefs ->
            val current = prefs[enabledKey] ?: emptySet()
            prefs[enabledKey] = if (enabled) current + projectId else current - projectId
        }
        if (enabled) enqueueAutoIndex(projectId) else cancelWork(projectId)
    }

    suspend fun isEnabledNow(projectId: String): Boolean = isEnabled(projectId).first()

    /** Constrained background pass: charging + idle, exactly as specified. */
    fun enqueueAutoIndex(projectId: String) {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .build()
        val request = OneTimeWorkRequestBuilder<CodebaseIndexerWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(CodebaseIndexerWorker.KEY_PROJECT_ID to projectId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(projectId), ExistingWorkPolicy.KEEP, request,
        )
    }

    /** User-triggered pass ("Index now"): expedited, falls back gracefully. */
    fun enqueueIndexNow(projectId: String) {
        val request = OneTimeWorkRequestBuilder<CodebaseIndexerWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(CodebaseIndexerWorker.KEY_PROJECT_ID to projectId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(projectId), ExistingWorkPolicy.REPLACE, request,
        )
    }

    fun cancelWork(projectId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(projectId))
    }

    /** Direct indexing run (worker + tests); model must be Ready. */
    suspend fun indexProject(projectId: String): IndexReport {
        val fs = gateway.fileSystem(projectId)
            ?: return IndexReport(0, 0, 0, 0, false, "Project files are not accessible on this device.")
        val embedder = models.createEmbedder()
            ?: return IndexReport(0, 0, 0, 0, false, "Embedding model is not downloaded yet.")
        embedder.use { active ->
            val indexer = CodebaseIndexer(models.vecStore(), active, gateway.codeIntelligence())
            return indexer.index(projectId, fs)
        }
    }

    /** Semantic search over the project's stored chunks. */
    suspend fun search(projectId: String, query: String, k: Int = 8): List<RagHit> {
        val embedder = models.createEmbedder() ?: return emptyList()
        embedder.use { active ->
            val vector = active.embed(query) ?: return emptyList()
            return models.vecStore().query(projectId, vector, k)
        }
    }

    fun chunkCount(projectId: String): Int = models.vecStore().count(projectId)

    fun clearProject(projectId: String) {
        models.vecStore().clearProject(projectId)
    }

    private fun workName(projectId: String): String = "rag-index-$projectId"
}

/**
 * WorkManager entry point for indexing. Fails (with a reason in the
 * output data) when the model is not downloaded or the project is not
 * file-accessible, so the state is inspectable via WorkInfo.
 */
class CodebaseIndexerWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT_ID)
            ?: return Result.failure(workDataOf(KEY_MESSAGE to "missing project id"))
        val manager = AppGraph.get().androidRuntime.ragManager()
            ?: return Result.failure(workDataOf(KEY_MESSAGE to "RAG unavailable"))
        if (manager.models.state.value !is RagModelState.Ready) {
            return Result.failure(workDataOf(KEY_MESSAGE to "Embedding model is not downloaded yet."))
        }
        val report = manager.indexProject(projectId)
        val message = report.message
        return if (message != null) {
            Result.failure(workDataOf(KEY_MESSAGE to message))
        } else {
            Result.success(
                workDataOf(
                    KEY_INDEXED to report.filesIndexed,
                    KEY_CHUNKS to report.chunksStored,
                    KEY_SCANNED to report.filesScanned,
                ),
            )
        }
    }

    companion object {
        const val KEY_PROJECT_ID = "projectId"
        const val KEY_MESSAGE = "message"
        const val KEY_INDEXED = "indexed"
        const val KEY_CHUNKS = "chunks"
        const val KEY_SCANNED = "scanned"
    }
}
