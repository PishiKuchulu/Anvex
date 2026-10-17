package com.anvex.core.android.mcp

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpApprovalBrokerTest {
    @Test fun `an unanswered request is denied after the timeout`() = runBlocking {
        assertFalse(McpApprovalBroker.request("docs", "search", "{}", timeoutMs = 50))
        assertNull(McpApprovalBroker.pending.value)
    }

    @Test fun `an answer from the user allows the waiting request`() = runBlocking {
        val result = async { McpApprovalBroker.request("docs", "search", "{}", timeoutMs = 5_000) }
        delay(20)
        val id = McpApprovalBroker.pending.value!!.id
        McpApprovalBroker.answer(id, true)
        assertTrue(result.await())
        assertNull(McpApprovalBroker.pending.value)
    }
}
