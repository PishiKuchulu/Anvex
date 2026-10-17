package com.anvex.core.ai

import com.anvex.core.model.ModelInfo
import com.anvex.core.model.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect

/** Failover adapter. It only switches providers before any token is emitted. */
class FallbackProviderClient(private val clients: List<ProviderClient>) : ProviderClient {
    init { require(clients.isNotEmpty()) }
    override val kind: ProviderKind get() = clients.first().kind

    override fun streamChat(request: ChatRequest): Flow<StreamEvent> = flow {
        var lastFailure: AiError? = null
        for ((index, client) in clients.withIndex()) {
            var emittedToken = false
            var completed = false
            var failed: AiError? = null
            try {
                client.streamChat(request).collect { event ->
                    when (event) {
                        is StreamEvent.Token -> { emittedToken = true; emit(event) }
                        is StreamEvent.Reasoning -> emit(event)
                        is StreamEvent.Completed -> { completed = true; emit(event) }
                        is StreamEvent.Failed -> failed = event.error
                        is StreamEvent.Started -> Unit
                        is StreamEvent.Sources -> emit(event)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = AiError(AiError.Kind.NETWORK, e.message?.take(500) ?: "Provider request failed")
            }
            if (completed) return@flow
            lastFailure = failed ?: AiError(AiError.Kind.UNKNOWN, "Provider failed without an error.")
            val failure: AiError = requireNotNull(lastFailure) { "lastFailure must be set before retry evaluation" }
            val retryable = failure.kind == AiError.Kind.RATE_LIMIT ||
                failure.kind == AiError.Kind.NETWORK ||
                failure.kind == AiError.Kind.NOT_FOUND
            if (!retryable || emittedToken || index == clients.lastIndex) {
                emit(StreamEvent.Failed(failure))
                return@flow
            }
        }
    }

    override suspend fun listModels(): List<ModelInfo> {
        var lastError: Throwable? = null
        for (client in clients) {
            try {
                val models = client.listModels()
                if (models.isNotEmpty()) return models
            } catch (t: Exception) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                lastError = t
            }
        }
        if (lastError != null) throw lastError
        return emptyList()
    }

    override suspend fun validateConnection(): ValidationResult = clients.first().validateConnection()
}
