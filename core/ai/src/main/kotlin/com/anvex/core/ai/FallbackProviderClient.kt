package com.anvex.core.ai

import com.anvex.core.model.ModelInfo
import com.anvex.core.model.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect

/** Failover adapter. It only switches providers before any token is emitted. */
class FallbackProviderClient(
    private val clients: List<ProviderClient>,
    /**
     * (providerId, model) per client, parallel to [clients]. The
     * answering client's identity is stamped onto its
     * [StreamEvent.Completed] so callers persist and price the reply as
     * the provider that actually produced it, not the first in the
     * chain (audit 2026-11-03). Empty/absent entries leave the event
     * unstamped.
     */
    private val clientIdentities: List<Pair<String, String>> = emptyList(),
) : ProviderClient {
    init { require(clients.isNotEmpty()) }
    override val kind: ProviderKind get() = clients.first().kind

    override fun streamChat(request: ChatRequest): Flow<StreamEvent> = flow {
        var lastFailure: AiError? = null
        for ((index, client) in clients.withIndex()) {
            var emittedToken = false
            var completed = false
            var failed: AiError? = null
            // M-16: Reasoning/Sources from a FAILED attempt used to linger
            // in the UI even after failover. Buffer them; they are only
            // emitted once a Token proves this attempt is the live one.
            val pending = mutableListOf<StreamEvent>()
            try {
                client.streamChat(request).collect { event ->
                    when (event) {
                        is StreamEvent.Token -> {
                            emittedToken = true
                            pending.forEach { emit(it) }
                            pending.clear()
                            emit(event)
                        }
                        is StreamEvent.Reasoning -> pending.add(event)
                        is StreamEvent.Completed -> {
                            completed = true
                            pending.forEach { emit(it) }
                            pending.clear()
                            emit(event.withIdentity(index))
                        }
                        is StreamEvent.Failed -> failed = event.error
                        is StreamEvent.Started -> Unit
                        is StreamEvent.Sources -> pending.add(event)
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
            // M-16: back off before failover instead of hammering the next
            // provider immediately (honors Retry-After, capped at 10 s).
            kotlinx.coroutines.delay(
                minOf((failure.retryAfterSeconds ?: 1L) * 1_000L, 10_000L)
            )
        }
    }

    /** Stamp the answering client's identity onto its completion event. */
    private fun StreamEvent.Completed.withIdentity(index: Int): StreamEvent.Completed {
        val identity = clientIdentities.getOrNull(index) ?: return this
        return copy(providerId = identity.first, model = identity.second)
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
