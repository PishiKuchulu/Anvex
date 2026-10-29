package com.anvex.core.ai

import com.anvex.core.model.ProviderKind
import com.anvex.core.model.ThinkingLevel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FallbackProviderClientTest {
    private class Throwing : ProviderClient {
        override val kind = ProviderKind.OPENAI
        override fun streamChat(request: ChatRequest) = flow<StreamEvent> { throw java.io.IOException("down") }
        override suspend fun listModels() = emptyList<com.anvex.core.model.ModelInfo>()
        override suspend fun validateConnection() = ValidationResult(false, 0, "down")
    }
    private class Healthy : ProviderClient {
        override val kind = ProviderKind.OPENAI
        override fun streamChat(request: ChatRequest) = flow {
            emit(StreamEvent.Token("ok"))
            emit(StreamEvent.Completed("stop"))
        }
        override suspend fun listModels() = emptyList<com.anvex.core.model.ModelInfo>()
        override suspend fun validateConnection() = ValidationResult(true, 0, "ok")
    }

    @Test fun `listModels does not silently swallow provider errors`() = runTest {
        val failing = object : ProviderClient {
            override val kind = ProviderKind.OPENAI
            override fun streamChat(request: ChatRequest) = flow<StreamEvent> { }
            override suspend fun listModels(): List<com.anvex.core.model.ModelInfo> = throw AiHttpException(401, "bad key")
            override suspend fun validateConnection() = ValidationResult(false, 0, "bad key")
        }
        try {
            FallbackProviderClient(listOf(failing)).listModels()
            fail("Expected provider error")
        } catch (e: AiHttpException) {
            assertTrue(e.code == 401)
        }
    }

    @Test fun `reasoning from a failed attempt does not linger after failover`() = runTest {
        val request = ChatRequest("sys", emptyList(), thinkingLevel = ThinkingLevel.NORMAL)
        val reasoningThenFail = object : ProviderClient {
            override val kind = ProviderKind.OPENAI
            override fun streamChat(request: ChatRequest) = flow {
                emit(StreamEvent.Reasoning("thinking..."))
                emit(StreamEvent.Failed(AiError(AiError.Kind.NETWORK, "down")))
            }
            override suspend fun listModels() = emptyList<com.anvex.core.model.ModelInfo>()
            override suspend fun validateConnection() = ValidationResult(false, 0, "down")
        }
        val events = FallbackProviderClient(listOf(reasoningThenFail, Healthy())).streamChat(request).toList()
        assertTrue(events.none { it is StreamEvent.Reasoning })
        assertTrue(events.any { it is StreamEvent.Token && it.text == "ok" })
    }

    @Test fun `thrown first provider falls back before emitting tokens`() = runTest {
        val request = ChatRequest("sys", emptyList(), thinkingLevel = ThinkingLevel.NORMAL)
        val events = FallbackProviderClient(listOf(Throwing(), Healthy())).streamChat(request).toList()
        assertTrue(events.any { it is StreamEvent.Token && it.text == "ok" })
        assertTrue(events.any { it is StreamEvent.Completed })
    }
}
