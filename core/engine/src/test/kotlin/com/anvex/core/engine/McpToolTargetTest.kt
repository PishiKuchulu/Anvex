package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class McpToolTargetTest {
    @Test fun `pack and unpack round trip trims both parts`() {
        val packed = McpToolTarget.pack(" docs ", " search ")
        assertEquals("docs" to "search", McpToolTarget.unpack(packed))
    }

    @Test fun `a target without a separator yields an empty tool name`() {
        assertEquals("docs" to "", McpToolTarget.unpack("docs"))
    }
}
