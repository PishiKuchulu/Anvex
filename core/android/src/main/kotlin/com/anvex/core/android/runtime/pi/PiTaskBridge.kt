package com.anvex.core.android.runtime.pi

import com.anvex.core.android.reliability.DurableTaskEngine
import com.anvex.core.android.repo.ProjectRepository
import com.anvex.core.android.repo.ProviderRepository
import org.json.JSONObject

/**
 * Durable-task face of the Pi engine: registers the `pi-agent` task type
 * with DurableTaskEngine so Pi runs get the harness's durability
 * (persistence, interruption marking, checkpoints) for free.
 *
 * Payload (JSON): {prompt, projectId, providerId?, sessionFile?}
 * Checkpoint:   "pi-session" -> the Pi session file, so an interrupted
 *               run can be resumed by enqueueing with that sessionFile.
 *
 * Background tasks have no user to prompt: the ApprovalPrompt denies
 * everything CommandApprovalService does not already allow (safe
 * actions and durable grants). Interactive runs (chat/test surface)
 * call PiEngine directly with a real prompt.
 */
class PiTaskBridge(
    private val taskEngine: DurableTaskEngine,
    private val piEngine: PiEngine,
    private val providers: ProviderRepository,
    private val projects: ProjectRepository,
) {

    init {
        taskEngine.register(TYPE) { task ->
            val payload = task.payload?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return@register DurableTaskEngine.Result.Failure("Missing pi-agent payload")
            val prompt = payload.optString("prompt")
            val projectId = payload.optString("projectId")
            if (prompt.isBlank() || projectId.isBlank()) {
                return@register DurableTaskEngine.Result.Failure("pi-agent payload needs prompt + projectId")
            }
            val root = projects.localRootOrNull(projectId)
                ?: return@register DurableTaskEngine.Result.Failure(
                    "Pi engine needs a local project workspace (SAF workspaces fail closed)",
                )
            val providerId = payload.optString("providerId").ifBlank { null }
                ?: providers.getDefault()?.id
                ?: return@register DurableTaskEngine.Result.Failure("No provider configured")
            val runtime = providers.runtimeConfig(providerId)
                ?: return@register DurableTaskEngine.Result.Failure("Provider credentials unavailable")
            val mapped = PiProviderMapping.forRuntime(runtime)
                ?: return@register DurableTaskEngine.Result.Failure(
                    "Provider kind ${runtime.config.kind} is not supported by the Pi engine yet",
                )
            task.checkpoint("pi-boot", mapped.providerName + "/" + mapped.modelId)
            val result = piEngine.run(
                projectRoot = root,
                config = PiEngine.RunConfig(
                    providerName = mapped.providerName,
                    modelId = mapped.modelId,
                    apiKey = runtime.apiKey,
                    modelsJson = mapped.modelsJson,
                    sessionFile = payload.optString("sessionFile").ifBlank { null },
                    sessionId = "task-${task.task.id}",
                    projectId = projectId,
                ),
                prompt = prompt,
                approvalPrompt = PiEngine.ApprovalPrompt { false },
            )
            if (result.sessionFile != null) task.checkpoint("pi-session", result.sessionFile)
            if (result.success) {
                DurableTaskEngine.Result.Success(result.sessionFile)
            } else {
                DurableTaskEngine.Result.Failure(result.error ?: "Pi run failed")
            }
        }
    }

    /** Enqueues a durable Pi run. Returns the task id. */
    suspend fun enqueue(
        prompt: String,
        projectId: String,
        providerId: String? = null,
        sessionFile: String? = null,
    ): Long {
        val payload = JSONObject()
            .put("prompt", prompt)
            .put("projectId", projectId)
        if (providerId != null) payload.put("providerId", providerId)
        if (sessionFile != null) payload.put("sessionFile", sessionFile)
        return taskEngine.enqueue(
            TYPE,
            "Pi: ${prompt.trim().take(180)}",
            payloadJson = payload.toString(),
            resumable = true,
            maxAttempts = 2,
        )
    }

    companion object {
        const val TYPE = "pi-agent"
    }
}
