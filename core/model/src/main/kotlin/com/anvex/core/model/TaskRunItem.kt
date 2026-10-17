package com.anvex.core.model

/** Read-only view of a durable task for presentation. Keeps Room entities out of the UI layer. */
data class TaskRunItem(
    val id: Long,
    val type: String,
    val label: String,
    val state: String,
    val detail: String?,
    val projectId: String?,
    /** Retry progress, mirrored from the durable task record. */
    val attempt: Int = 0,
    val maxAttempts: Int = 1,
    /** Earliest wall-clock time at which a queued retry may execute; null when none is scheduled. */
    val nextRunAt: Long? = null,
    /** Whether the task engine allows resuming this task from its checkpoint. */
    val resumable: Boolean = true,
)
