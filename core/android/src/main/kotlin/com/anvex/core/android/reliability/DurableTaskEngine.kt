package com.anvex.core.android.reliability

import com.anvex.core.android.db.TaskRunEntity
import com.anvex.core.android.db.TaskDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Process-independent task state persisted in Room. The coroutine worker is only
 * an executor; truth lives in task_runs, so process death becomes recoverable
 * instead of silently losing work.
 */
class DurableTaskEngine(
    private val dao: TaskDao,
    parentScope: CoroutineScope,
) {
    @Serializable
    data class Checkpoint(val step: String = "", val offset: Long = 0, val note: String? = null)

    data class TaskContext(
        val task: TaskRunEntity,
        val payload: String?,
        val checkpoint: String?,
        private val checkpointWriter: suspend (String?, String?) -> Unit,
        private val cancellationProbe: suspend () -> Boolean,
    ) {
        suspend fun checkpoint(value: String?, detail: String? = null) = checkpointWriter(value, detail)
        suspend fun isCancellationRequested(): Boolean = cancellationProbe()
        suspend fun ensureActive() {
            if (isCancellationRequested()) throw TaskCancelledException()
        }
    }

    sealed interface Result {
        data class Success(val detail: String? = null) : Result
        data class Retry(val detail: String? = null) : Result
        data class WaitingApproval(val detail: String? = null) : Result
        data class Paused(val detail: String? = null) : Result
        data class Failure(val detail: String? = null) : Result
    }

    class TaskCancelledException : CancellationException("Task cancellation requested")
    class TaskPausedException : CancellationException("Task pause requested")

    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val workerMutex = Mutex()
    private val handlers = ConcurrentHashMap<String, suspend (TaskContext) -> Result>()
    private val running = ConcurrentHashMap<Long, Job>()

    companion object {
        const val QUEUED = "QUEUED"
        const val RUNNING = "RUNNING"
        const val WAITING_APPROVAL = "WAITING_APPROVAL"
        const val PAUSED = "PAUSED"
        const val INTERRUPTED = "INTERRUPTED"
        const val RETRYING = "RETRYING"
        const val COMPLETED = "COMPLETED"
        const val FAILED = "FAILED"
        const val CANCELLED = "CANCELLED"
        const val MAX_PAYLOAD_BYTES = 512_000
        const val MAX_CHECKPOINT_BYTES = 512_000
        private const val MAX_RETRY_DELAY_MS = 30_000L
    }

    init {
        scope.launch {
            while (isActive) {
                wake.receive()
                drain()
            }
        }
        scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(1_000L)
                wake.trySend(Unit)
            }
        }
    }

    fun register(type: String, handler: suspend (TaskContext) -> Result) {
        require(type.isNotBlank() && type.length <= 80) { "Invalid task type" }
        check(handlers.putIfAbsent(type, handler) == null) { "Task handler already registered: $type" }
    }

    suspend fun enqueue(
        type: String,
        label: String,
        payloadJson: String? = null,
        checkpointJson: String? = null,
        resumable: Boolean = true,
        maxAttempts: Int = 3,
    ): Long {
        validatePersistedText(payloadJson, MAX_PAYLOAD_BYTES, "payload")
        validatePersistedText(checkpointJson, MAX_CHECKPOINT_BYTES, "checkpoint")
        require(maxAttempts in 1..10) { "maxAttempts must be 1..10" }
        val id = dao.insert(
            TaskRunEntity(
                type = type.trim().take(80),
                label = label.trim().take(200),
                state = QUEUED,
                resumable = resumable,
                payloadJson = payloadJson,
                checkpointJson = checkpointJson,
                maxAttempts = maxAttempts,
            )
        )
        wake.trySend(Unit)
        return id
    }

    suspend fun requestCancellation(id: Long): Boolean {
        val changed = dao.requestCancel(id, System.currentTimeMillis()) > 0
        if (changed) running[id]?.cancel(TaskCancelledException())
        wake.trySend(Unit)
        return changed
    }

    suspend fun pause(id: Long): Boolean {
        val task = dao.get(id) ?: return false
        if (task.state != RUNNING) return false
        val changed = dao.pause(id, "Paused; durable checkpoint retained.", System.currentTimeMillis()) > 0
        if (changed) running[id]?.cancel(TaskPausedException())
        wake.trySend(Unit)
        return changed
    }

    suspend fun resume(id: Long): Boolean {
        val task = dao.get(id) ?: return false
        if (!task.resumable || task.state !in setOf(INTERRUPTED, PAUSED, WAITING_APPROVAL)) return false
        val changed = dao.restart(id, System.currentTimeMillis()) > 0
        if (changed) wake.trySend(Unit)
        return changed
    }

    suspend fun markProcessInterrupted() = dao.markRunningInterrupted()

    suspend fun get(id: Long): TaskRunEntity? = dao.get(id)

    suspend fun checkpoint(id: Long, value: String?, detail: String? = null): Boolean {
        validatePersistedText(value, MAX_CHECKPOINT_BYTES, "checkpoint")
        return dao.checkpoint(id, value, detail?.take(2_000), System.currentTimeMillis()) > 0
    }

    /** Runs all currently queued tasks. Safe to call after process recovery. */
    suspend fun drain() = workerMutex.withLock {
        while (true) {
            val next = dao.queued(System.currentTimeMillis(), 1).firstOrNull() ?: return
            runOne(next)
        }
    }

    private suspend fun runOne(queued: TaskRunEntity) {
        val handler = handlers[queued.type]
        if (handler == null) {
            dao.finish(queued.id, FAILED, "No handler registered for task type '${queued.type}'.", System.currentTimeMillis(), System.currentTimeMillis())
            return
        }
        val now = System.currentTimeMillis()
        if (dao.claim(queued.id, now, now) == 0) return
        val task = dao.get(queued.id) ?: return
        val job = scope.launch(Dispatchers.IO) {
            try {
                val result = handler(
                    TaskContext(
                        task = task,
                        payload = task.payloadJson,
                        checkpoint = task.checkpointJson,
                        checkpointWriter = { value, detail ->
                            validatePersistedText(value, MAX_CHECKPOINT_BYTES, "checkpoint")
                            dao.checkpoint(task.id, value, detail?.take(2_000), System.currentTimeMillis())
                        },
                        cancellationProbe = {
                            dao.get(task.id)?.cancelRequested == true
                        },
                    )
                )
                val latest = dao.get(task.id)
                if (latest?.cancelRequested == true) {
                    dao.finish(task.id, CANCELLED, "Cancelled by user.", System.currentTimeMillis(), System.currentTimeMillis())
                } else {
                    when (result) {
                        is Result.Success -> dao.finish(task.id, COMPLETED, result.detail, System.currentTimeMillis(), System.currentTimeMillis())
                        is Result.Failure -> dao.finish(task.id, FAILED, result.detail, System.currentTimeMillis(), System.currentTimeMillis())
                        is Result.WaitingApproval -> dao.setState(task.id, WAITING_APPROVAL, result.detail, System.currentTimeMillis())
                        is Result.Paused -> dao.setState(task.id, PAUSED, result.detail, System.currentTimeMillis())
                        is Result.Retry -> retryOrFail(task, result.detail)
                    }
                }
            } catch (_: TaskCancelledException) {
                dao.finish(task.id, CANCELLED, "Cancelled by user.", System.currentTimeMillis(), System.currentTimeMillis())
            } catch (_: TaskPausedException) {
                dao.setState(task.id, PAUSED, "Paused; durable checkpoint retained.", System.currentTimeMillis())
            } catch (e: CancellationException) {
                // External coroutine cancellation is treated as an interruption;
                // the next process can safely resume from the last checkpoint.
                dao.setState(task.id, INTERRUPTED, "Execution coroutine cancelled; checkpoint retained.", System.currentTimeMillis())
                throw e
            } catch (e: Throwable) {
                retryOrFail(task, e.message ?: e::class.simpleName ?: "Task failed")
            }
        }
        running[task.id] = job
        try { job.join() } finally { running.remove(task.id) }
    }

    private suspend fun retryOrFail(task: TaskRunEntity, detail: String?) {
        val current = dao.get(task.id) ?: return
        if (current.cancelRequested) {
            dao.finish(task.id, CANCELLED, "Cancelled by user.", System.currentTimeMillis(), System.currentTimeMillis())
            return
        }
        if (current.attempt < current.maxAttempts) {
            val delayMs = min(MAX_RETRY_DELAY_MS, 500L * (1L shl min(current.attempt, 6)))
            val nextRunAt = System.currentTimeMillis() + delayMs
            dao.scheduleRetry(task.id, "Retry ${current.attempt + 1}/${current.maxAttempts}: ${detail.orEmpty()}".take(2_000), nextRunAt, System.currentTimeMillis())
            // Do not block the worker during backoff. The persisted nextRunAt survives process death.
            wake.trySend(Unit)
        } else {
            dao.finish(task.id, FAILED, detail?.take(2_000), System.currentTimeMillis(), System.currentTimeMillis())
        }
    }

    private fun validatePersistedText(value: String?, maxBytes: Int, field: String) {
        if (value == null) return
        require(value.toByteArray(Charsets.UTF_8).size <= maxBytes) { "Task $field is too large" }
        // Durable task state is intentionally not a credential store. Reject
        // common credential-shaped JSON keys before they can reach disk.
        val lowered = value.lowercase()
        val sensitiveKeys = listOf("\"apikey\"", "\"api_key\"", "\"access_token\"", "\"refresh_token\"", "\"password\"", "\"client_secret\"", "\"authorization\"", "\"cookie\"")
        require(sensitiveKeys.none(lowered::contains)) { "Task $field contains credential-like data" }
    }
}
