package com.anvex.core.android.reliability

import com.anvex.core.engine.AgentOrchestrator
import com.anvex.core.engine.AgentActionExecutor
import com.anvex.core.engine.AgentPlanner
import com.anvex.core.engine.ActionDispatcher
import com.anvex.core.engine.BoltAction
import com.anvex.core.engine.ToolCapability
import com.anvex.core.engine.ProjectFileSystem
import com.anvex.core.android.runtime.AndroidRuntimeGateway
import com.anvex.core.android.repo.MutationRepository
import com.anvex.core.android.runtime.ShellOrigin
import com.anvex.core.android.artifacts.ArtifactManager
import com.anvex.core.engine.ArtifactFormat
import com.anvex.core.android.runtime.ShellResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Bridges agent plans into the durable task engine without persisting credentials. */
class AgentTaskBridge(
    private val taskEngine: DurableTaskEngine,
    private val fsProvider: suspend (String) -> ProjectFileSystem,
    private val planner: AgentPlanner,
    private val commandApprovalService: CommandApprovalService,
    private val mutationRepository: MutationRepository? = null,
    private val runtime: AndroidRuntimeGateway? = null,
    private val artifactManager: ArtifactManager? = null,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        taskEngine.register("agent") { task ->
            val input = task.payload?.let { json.decodeFromString<AgentInput>(it) }
                ?: return@register DurableTaskEngine.Result.Failure("Missing agent task input")
            mutex.withLock {
                val projectId = input.projectId ?: return@withLock DurableTaskEngine.Result.Failure("Agent tasks require a project workspace")
                val fs = fsProvider(projectId)
                val startIndex = task.checkpoint?.substringAfter("step=", "-1")?.takeWhile { it.isDigit() }?.toIntOrNull()?.plus(1) ?: 0
                val dispatcher = ActionDispatcher(fs)
                var persistedMutationCount = 0
                val executor = AgentActionExecutor { action ->
                    when (action) {
                        is BoltAction.FileAction -> dispatcher.applyFileAction(action, approvalGranted = commandApprovalAllows(input, action))
                        is BoltAction.ShellAction -> runtime?.shell(input.projectId, action.content, ShellOrigin.AI) {
                            commandApprovalAllows(input, action)
                        }?.toActionResult() ?: com.anvex.core.engine.ActionResult(false, error = "Android shell runtime is unavailable.")
                        is BoltAction.StartAction -> runtime?.shell(input.projectId, action.content, ShellOrigin.AI) {
                            commandApprovalAllows(input, action)
                        }?.toActionResult() ?: com.anvex.core.engine.ActionResult(false, error = "Android start/runtime is unavailable.")
                        is BoltAction.ArtifactAction -> createArtifact(input.projectId, fs, action, commandApprovalAllows(input, action))
                        is BoltAction.ToolAction -> com.anvex.core.engine.ActionResult(false, error = "Tool actions run in chat sessions only.")
                        is BoltAction.UnknownAction -> com.anvex.core.engine.ActionResult(false, error = "Unsupported action type: ${action.type}")
                    }
                }
                val result = AgentOrchestrator(planner, dispatcher, executor).run(
                    goal = input.goal,
                    context = input.context,
                    approve = { step, _ -> commandApprovalAllows(input, step.action) },
                    shouldCancel = { taskEngine.get(task.task.id)?.cancelRequested == true },
                    startStepIndex = startIndex,
                    onStepCompleted = { index, step, stepResult ->
                        // Persist only the new mutation journal entries; model output and credentials never enter durable task state.
                        val all = dispatcher.mutations.all()
                        if (mutationRepository != null && persistedMutationCount < all.size) {
                            mutationRepository.recordAll(projectId, all.subList(persistedMutationCount, all.size))
                            persistedMutationCount = all.size
                        }
                        taskEngine.checkpoint(task.task.id, "step=$index;stepId=${step.id};success=${stepResult.success}")
                    },
                )
                when (result.state) {
                    com.anvex.core.engine.AgentRunState.COMPLETED -> DurableTaskEngine.Result.Success("Agent completed ${result.results.size} step(s)")
                    com.anvex.core.engine.AgentRunState.CANCELLED -> DurableTaskEngine.Result.Failure("Agent cancelled")
                    com.anvex.core.engine.AgentRunState.WAITING_APPROVAL -> {
                        val plan = result.plan
                        val pending = result.results.lastOrNull { it.approvalRequired }
                        val pendingStep = plan?.steps?.firstOrNull { it.id == pending?.stepId }
                        if (pendingStep != null && plan != null) {
                            val assessment = com.anvex.core.engine.ActionSafetyPolicy.assess(pendingStep.action)
                            val capability = requiredCapability(pendingStep.action, assessment)
                            val fingerprint = commandApprovalService.fingerprintFor(pendingStep.action)
                            taskEngine.checkpoint(task.task.id, "step=${plan.steps.indexOf(pendingStep) - 1};stepId=${pendingStep.id};approvalFingerprint=$fingerprint;capability=${capability.name};session=${input.sessionId};project=${input.projectId}")
                        }
                        DurableTaskEngine.Result.WaitingApproval(result.error ?: "Agent approval required")
                    }
                    else -> DurableTaskEngine.Result.Failure(result.error ?: "Agent execution failed")
                }
            }
        }
    }

    suspend fun enqueue(goal: String, context: String = "", projectId: String? = null, sessionId: String = "agent-${System.currentTimeMillis()}"): Long {
        require(goal.isNotBlank() && goal.length <= 8_000)
        require(context.length <= 32_000)
        require(sessionId.isNotBlank() && sessionId.length <= 160)
        require(projectId != null && projectId.isNotBlank() && projectId.length <= 160)
        val payload = json.encodeToString(AgentInput.serializer(), AgentInput(goal.trim(), context, projectId, sessionId))
        return taskEngine.enqueue("agent", "Agent: ${goal.trim().take(180)}", payloadJson = payload, resumable = true, maxAttempts = 2)
    }

    private suspend fun commandApprovalAllows(input: AgentInput, action: BoltAction): Boolean {
        return commandApprovalService.decide(input.sessionId, input.projectId, action).allowed
    }

    private suspend fun createArtifact(projectId: String, fs: ProjectFileSystem, action: BoltAction.ArtifactAction, approvalGranted: Boolean): com.anvex.core.engine.ActionResult {
        val assessment = com.anvex.core.engine.ActionSafetyPolicy.assess(action)
        if (assessment.requiresApproval && !approvalGranted) return com.anvex.core.engine.ActionResult(false, error = "Approval required: ${assessment.reason}", status = com.anvex.core.model.ActionStatus.FAILED)
        val manager = artifactManager ?: return com.anvex.core.engine.ActionResult(false, error = "Artifact packaging runtime is unavailable.")
        return runCatching {
            val generated = when (action.format.lowercase()) {
                "zip" -> manager.packageProject(projectId, fs, action.paths, action.outputName)
                "pdf" -> manager.createPdf(projectId, action.title, action.content, action.outputName)
                "txt" -> manager.createDocument(projectId, action.content, ArtifactFormat.TXT, action.outputName)
                "md", "markdown" -> manager.createDocument(projectId, action.content, ArtifactFormat.MD, action.outputName)
                "json" -> manager.createDocument(projectId, action.content, ArtifactFormat.JSON, action.outputName)
                else -> throw IllegalArgumentException("Unsupported generated-file format: ${action.format}")
            }
            com.anvex.core.engine.ActionResult(true, "Created ${generated.file.name} (${generated.file.length()} bytes)", changedFiles = listOf(generated.file.name), status = com.anvex.core.model.ActionStatus.COMPLETE)
        }.getOrElse { e -> com.anvex.core.engine.ActionResult(false, error = e.message ?: "Packaging failed", status = com.anvex.core.model.ActionStatus.FAILED) }
    }

    private fun ShellResult.toActionResult(): com.anvex.core.engine.ActionResult =
        com.anvex.core.engine.ActionResult(
            success = success,
            output = output,
            error = error,
            durationMs = 0,
            status = if (success) com.anvex.core.model.ActionStatus.COMPLETE else com.anvex.core.model.ActionStatus.FAILED,
        )

    /** Approves only the exact action fingerprint recorded by a waiting durable task. */
    suspend fun approveWaitingTask(taskId: Long, ttlMs: Long = 10 * 60 * 1000L): Boolean {
        val task = taskEngine.get(taskId) ?: return false
        if (task.type != "agent" || task.state != DurableTaskEngine.WAITING_APPROVAL) return false
        val checkpoint = task.checkpointJson ?: return false
        val fingerprint = checkpoint.field("approvalFingerprint") ?: return false
        val capability = checkpoint.field("capability")?.let { runCatching { ToolCapability.valueOf(it) }.getOrNull() } ?: return false
        val session = checkpoint.field("session") ?: return false
        val project = checkpoint.field("project") ?: return false
        if (!fingerprint.matches(Regex("[A-Fa-f0-9]{64}"))) return false
        if (!session.startsWith("agent-") || session.length > 160 || project.isBlank() || project.length > 160) return false
        return commandApprovalService.grantFingerprint(session, project, capability, fingerprint, ttlMs)
    }

    suspend fun resumeAfterApproval(taskId: Long): Boolean =
        taskEngine.resume(taskId)

    private fun String.field(name: String): String? =
        split(';').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.takeIf { it.isNotBlank() }

    private fun requiredCapability(action: com.anvex.core.engine.BoltAction, assessment: com.anvex.core.engine.ActionSafetyPolicy.Assessment): ToolCapability = when {
        assessment.requiresApproval && action is BoltAction.UnknownAction -> ToolCapability.DESTRUCTIVE
        assessment.requiresApproval && (action is BoltAction.ShellAction || action is BoltAction.StartAction) -> ToolCapability.SYSTEM
        action is BoltAction.FileAction -> ToolCapability.WORKSPACE_WRITE
        action is BoltAction.ShellAction || action is BoltAction.StartAction -> ToolCapability.SYSTEM
        else -> ToolCapability.DESTRUCTIVE
    }

    @Serializable
    private data class AgentInput(
        val goal: String,
        val context: String,
        val projectId: String? = null,
        val sessionId: String = "",
    )
}
