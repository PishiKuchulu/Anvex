package com.anvex.core.android.runtime.pi

import com.anvex.core.android.reliability.DurableTaskEngine
import com.anvex.core.android.repo.ProjectRepository
import com.anvex.core.android.repo.ProviderRepository
import com.anvex.core.android.repo.SettingsRepository
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Durable-task face of the Pi engine: registers the `pi-agent` task type
 * with DurableTaskEngine so Pi runs get the harness's durability
 * (persistence, interruption marking, checkpoints) for free.
 *
 * Payload (JSON): {prompt, projectId, providerId?, sessionFile?, context?}
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
    private val settings: SettingsRepository,
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
            // Agent Center context field (audit round 2, R2-2): Pi tasks
            // have no structured context slot, so the user's context is
            // folded into the prompt the engine receives — the field must
            // never be silently dropped at the facade again.
            val context = payload.optString("context")
            val effectivePrompt = if (context.isBlank()) {
                prompt
            } else {
                "$prompt\n\nAdditional context from the user:\n$context"
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
            val thinking = settings.settings.first().thinkingLevel
            val result = piEngine.run(
                projectRoot = root,
                config = PiEngine.RunConfig(
                    providerName = mapped.providerName,
                    modelId = mapped.modelId,
                    thinkingLevel = thinking.toPiLevel(),
                    apiKey = runtime.apiKey,
                    modelsJson = mapped.modelsJson,
                    sessionFile = payload.optString("sessionFile").ifBlank { null },
                    sessionId = "task-${task.task.id}",
                    projectId = projectId,
                ),
                prompt = effectivePrompt,
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

    /**
     * Enqueues a durable Pi run. Returns the task id.
     *
     * Wiring note (audit 05, Gap 1): this entry point is public and the
     * bridge is exposed as `AppGraph.piTaskBridge`, but the Agent Center
     * start action historically enqueued EVERY goal through the native
     * `AgentTaskBridge`, so with the Pi engine selected, tasks ran on the
     * native loop anyway (or stalled). Callers must branch on the
     * engine setting — `AppGraph.enqueueAgentTask` does exactly that;
     * UI code should call the facade, not either bridge directly.
     */
    suspend fun enqueue(
        prompt: String,
        projectId: String,
        providerId: String? = null,
        sessionFile: String? = null,
        context: String = "",
    ): Long {
        val payload = JSONObject()
            .put("prompt", prompt)
            .put("projectId", projectId)
        if (providerId != null) payload.put("providerId", providerId)
        if (sessionFile != null) payload.put("sessionFile", sessionFile)
        if (context.isNotBlank()) payload.put("context", context)
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
