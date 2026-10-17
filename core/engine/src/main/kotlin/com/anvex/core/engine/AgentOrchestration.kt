package com.anvex.core.engine

/** A bounded, deterministic plan produced by an agent planner. */
data class AgentPlan(
    val id: String,
    val goal: String,
    val steps: List<AgentStep>,
) {
    init {
        require(id.isNotBlank() && id.length <= 120)
        require(goal.isNotBlank() && goal.length <= 8_000)
        require(steps.size <= 100) { "Agent plan is too large." }
    }
}

data class AgentStep(
    val id: String,
    val title: String,
    val action: BoltAction,
    val requiresApproval: Boolean = false,
) {
    init {
        require(id.isNotBlank() && id.length <= 120)
        require(title.isNotBlank() && title.length <= 300)
    }
}

fun interface AgentPlanner {
    suspend fun plan(goal: String, context: String): AgentPlan
}

/** Execution boundary for an agent action. Android integrations may route shell/start actions
 * through a real runtime gateway while the engine keeps file actions in the workspace dispatcher. */
fun interface AgentActionExecutor {
    suspend fun execute(action: BoltAction): ActionResult
}

enum class AgentRunState { PLANNING, RUNNING, WAITING_APPROVAL, COMPLETED, FAILED, CANCELLED }

data class AgentStepResult(
    val stepId: String,
    val success: Boolean,
    val output: String? = null,
    val error: String? = null,
    val changedFiles: List<String> = emptyList(),
    val approvalRequired: Boolean = false,
)

data class AgentRunResult(
    val state: AgentRunState,
    val plan: AgentPlan?,
    val results: List<AgentStepResult>,
    val error: String? = null,
)

/**
 * Agent execution is deliberately separate from model/provider code. A planner
 * decides what should happen; the dispatcher/runtime decides what is allowed.
 */
class AgentOrchestrator(
    private val planner: AgentPlanner,
    private val dispatcher: ActionDispatcher,
    private val executor: AgentActionExecutor? = null,
) {
    suspend fun run(
        goal: String,
        context: String = "",
        approve: suspend (AgentStep, ActionSafetyPolicy.Assessment) -> Boolean = { _, _ -> false },
        shouldCancel: suspend () -> Boolean = { false },
        startStepIndex: Int = 0,
        onStepCompleted: suspend (index: Int, step: AgentStep, result: AgentStepResult) -> Unit = { _, _, _ -> },
    ): AgentRunResult {
        if (goal.isBlank()) return AgentRunResult(AgentRunState.FAILED, null, emptyList(), "Goal is empty")
        val plan = runCatching { planner.plan(goal.take(8_000), context.take(32_000)) }
            .getOrElse { return AgentRunResult(AgentRunState.FAILED, null, emptyList(), it.message ?: "Planning failed") }
        val results = ArrayList<AgentStepResult>(plan.steps.size)
        val safeStart = startStepIndex.coerceIn(0, plan.steps.size)
        for ((index, step) in plan.steps.withIndex()) {
            if (index < safeStart) continue
            if (shouldCancel()) return AgentRunResult(AgentRunState.CANCELLED, plan, results)
            val assessment = ActionSafetyPolicy.assess(step.action)
            if (step.requiresApproval || assessment.requiresApproval) {
                if (!approve(step, assessment)) {
                    results += AgentStepResult(step.id, false, error = "Approval required: ${assessment.reason}", approvalRequired = true)
                    return AgentRunResult(AgentRunState.WAITING_APPROVAL, plan, results)
                }
            }
            val r = executor?.execute(step.action) ?: when (val action = step.action) {
                is BoltAction.FileAction -> dispatcher.applyFileAction(action, approvalGranted = true)
                is BoltAction.ShellAction -> dispatcher.applyShellAction(action, "shell", approvalGranted = true).firstOrNull()
                is BoltAction.StartAction -> dispatcher.applyShellAction(action, "start", approvalGranted = true).firstOrNull()
                is BoltAction.ArtifactAction -> ActionResult(false, error = "Artifact actions require a packaging runtime.")
                is BoltAction.ToolAction -> ActionResult(false, error = "Tool actions run in chat sessions only.")
                is BoltAction.UnknownAction -> ActionResult(false, error = "Unsupported action type: ${action.type}")
            } ?: ActionResult(false, error = "No action result")
            val stepResult = AgentStepResult(step.id, r.success, r.output, r.error, r.changedFiles, r.error?.startsWith("Approval required") == true)
            results += stepResult
            if (r.success) onStepCompleted(index, step, stepResult)
            if (!r.success) return AgentRunResult(AgentRunState.FAILED, plan, results, r.error)
        }
        return AgentRunResult(AgentRunState.COMPLETED, plan, results)
    }
}
