package com.anvex.core.android.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Asks the user before the assistant calls an MCP tool. [request] suspends the chat turn until
 * the user answers in the UI, or returns false after [timeoutMs] (default: deny).
 */
object McpApprovalBroker {

    data class Request(val id: String, val server: String, val tool: String, val arguments: String)

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    suspend fun request(server: String, tool: String, arguments: String, timeoutMs: Long = 120_000L): Boolean {
        val id = UUID.randomUUID().toString()
        val answer = CompletableDeferred<Boolean>()
        waiters[id] = answer
        _pending.value = Request(id, server, tool, arguments.take(1_000))
        return try {
            withTimeoutOrNull(timeoutMs) { answer.await() } ?: false
        } finally {
            waiters.remove(id)
            if (_pending.value?.id == id) _pending.value = null
        }
    }

    fun answer(id: String, allow: Boolean) {
        waiters.remove(id)?.complete(allow)
        if (_pending.value?.id == id) _pending.value = null
    }
}
