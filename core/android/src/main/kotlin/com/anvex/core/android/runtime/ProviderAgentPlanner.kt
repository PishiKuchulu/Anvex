package com.anvex.core.android.runtime

import com.anvex.core.ai.ChatRequest
import com.anvex.core.ai.ChatTurn
import com.anvex.core.ai.ProviderClient
import com.anvex.core.ai.StreamEvent
import com.anvex.core.engine.ActionSafetyPolicy
import com.anvex.core.engine.AgentPlan
import com.anvex.core.engine.AgentPlanner
import com.anvex.core.engine.AgentStep
import com.anvex.core.engine.BoltAction
import com.anvex.core.engine.BoltStreamParser
import com.anvex.core.engine.ParserCallbacks
import com.anvex.core.engine.ActionCallbackData
import com.anvex.core.engine.SystemPromptFactory
import java.util.UUID
import kotlinx.coroutines.flow.collect

/** Provider-backed planner that uses the same boltAction protocol as ChatSession. */
class ProviderAgentPlanner(private val provider: suspend () -> ProviderClient?) : AgentPlanner {
    override suspend fun plan(goal: String, context: String): AgentPlan {
        val client = provider() ?: error("No configured AI provider is available.")
        val actions = mutableListOf<BoltAction>()
        val parser = BoltStreamParser(object : ParserCallbacks {
            override fun onActionClose(data: ActionCallbackData) { if (actions.size < 100) actions += data.action }
        })
        val request = ChatRequest(
            systemPrompt = SystemPromptFactory.buildAgentSystemPrompt(),
            turns = listOf(ChatTurn(com.anvex.core.model.MessageRole.USER, "Create an executable project plan for this goal. Return boltArtifact/boltAction tags only when an action is required.\n\nGoal:\n$goal\n\nContext:\n$context")),
        )
        var failed: String? = null
        val messageId = "planner-${UUID.randomUUID()}"
        val fullText = StringBuilder()
        client.streamChat(request).collect { event ->
            when (event) {
                is StreamEvent.Token -> { fullText.append(event.text); parser.parse(messageId, fullText.toString()) }
                is StreamEvent.Failed -> failed = event.error.message
                else -> Unit
            }
        }
        parser.parse(messageId, fullText.toString())
        parser.flush(messageId)
        val failure = failed
        if (failure != null) error(failure)
        val steps = actions.mapIndexed { i, action -> AgentStep("step-$i", "Agent action ${i + 1}", action, ActionSafetyPolicy.assess(action).requiresApproval) }
        return AgentPlan("plan-${UUID.randomUUID()}", goal.take(8_000), steps)
    }
}
