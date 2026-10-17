package com.anvex.core.android.runtime

import com.anvex.core.ai.ChatRequest
import com.anvex.core.ai.ProviderClient
import com.anvex.core.ai.StreamEvent
import com.anvex.core.model.ModelInfo
import com.anvex.core.model.ProviderKind
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAgentPlannerTest {
    @Test fun parsesProviderBoltActionsIntoPlan() = runTest {
        val client = object : ProviderClient {
            override val kind = ProviderKind.OPENAI
            override fun streamChat(request: ChatRequest) = flow {
                emit(StreamEvent.Started)
                emit(StreamEvent.Token("<boltArtifact id=\"p\" title=\"Plan\"><boltAction type=\"file\" filePath=\"src/Main.kt\">hello</boltAction></boltArtifact>"))
                emit(StreamEvent.Completed())
            }
            override suspend fun listModels(): List<ModelInfo> = emptyList()
            override suspend fun validateConnection() = com.anvex.core.ai.ValidationResult(true, 0, "ok")
        }
        val plan = ProviderAgentPlanner { client }.plan("create file", "")
        assertEquals(1, plan.steps.size)
        assertTrue(plan.steps.first().action is com.anvex.core.engine.BoltAction.FileAction)
    }
}
