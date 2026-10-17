package com.anvex.core.android.repo

import com.anvex.core.android.db.MutationDao
import com.anvex.core.android.db.MutationEntity
import com.anvex.core.model.FileMutation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * v1.1 §24 — persists the file-mutation journal ("action results") per project
 * so changes survive app restart / process death and remain revertible.
 * Bounded: only the newest [KEEP] mutations per project are kept (§29 memory).
 */
class MutationRepository(private val dao: MutationDao) {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun observeForProject(projectId: String): Flow<List<FileMutation>> =
        dao.observeForProject(projectId, KEEP).map { list -> list.mapNotNull { it.toDomain() } }

    suspend fun forProject(projectId: String): List<FileMutation> =
        dao.forProject(projectId, KEEP).mapNotNull { it.toDomain() }

    suspend fun recordAll(projectId: String, mutations: List<FileMutation>) {
        if (mutations.isEmpty()) return
        dao.insertAll(mutations.map { it.toEntity(projectId) })
        dao.trim(projectId, KEEP)
    }

    suspend fun deleteForProject(projectId: String) = dao.deleteForProject(projectId)

    fun observeRecent(projectId: String, limit: Int = 200): Flow<List<FileMutation>> =
        dao.observeRecent(projectId, limit).map { list -> list.mapNotNull { it.toDomain() } }

    private fun MutationEntity.toDomain(): FileMutation? = try {
        FileMutation(
            path = path,
            operation = com.anvex.core.model.MutationOp.valueOf(operation),
            before = beforeContent,
            after = afterContent,
            timestamp = timestamp,
            source = com.anvex.core.model.MutationSource.valueOf(source),
            renamedTo = renamedTo,
            messageId = messageId,
        )
    } catch (_: Exception) {
        null
    }

    private fun FileMutation.toEntity(projectId: String) = MutationEntity(
        projectId = projectId,
        messageId = messageId,
        path = path,
        operation = operation.name,
        source = source.name,
        beforeContent = before,
        afterContent = after,
        renamedTo = renamedTo,
        timestamp = timestamp,
    )

    companion object {
        const val KEEP = 200
    }
}
