package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch1AgentRuntimeTest {
    @Test fun recursiveDeleteRequiresApproval() {
        assertTrue(ActionSafetyPolicy.assess("rm -rf src").requiresApproval)
        assertFalse(ActionSafetyPolicy.assess("ls src").requiresApproval)
    }

    @Test fun dispatcherBlocksDangerousActionWithoutApproval() {
        val dispatcher = ActionDispatcher(FakeFs())
        val result = dispatcher.applyShellAction(BoltAction.ShellAction("rm -rf src"), "shell").single()
        assertFalse(result.success)
        assertTrue(result.error!!.startsWith("Approval required"))
    }

    @Test fun dispatcherExecutesApprovedDangerousAction() {
        val dispatcher = ActionDispatcher(FakeFs(), approval = { it.requiresApproval })
        val result = dispatcher.applyShellAction(BoltAction.ShellAction("rm -rf src"), "shell").single()
        assertTrue(result.success)
    }

    @Test fun runtimeExecuteUsesDispatcherAndReportsFailure() = kotlinx.coroutines.test.runTest {
        val runtime = WebViewPreviewRuntime(FakeFs())
        val result = runtime.execute("rm -rf src")
        assertFalse(result.success)
        assertEquals(CommandCapability.UNSUPPORTED, result.capability)
    }

    @Test fun agentStopsForApprovalBeforeMutation() = kotlinx.coroutines.test.runTest {
        val fs = FakeFs()
        val planner = AgentPlanner { _, _ ->
            AgentPlan("p1", "delete", listOf(AgentStep("s1", "delete", BoltAction.ShellAction("rm -rf src"))))
        }
        val result = AgentOrchestrator(planner, ActionDispatcher(fs)).run("delete")
        assertEquals(AgentRunState.WAITING_APPROVAL, result.state)
        assertTrue(result.results.single().approvalRequired)
    }

    @Test fun workspaceRejectsTraversal() {
        val result = WorkspaceRuntime(FakeFs()).read("../secret")
        assertFalse(result.success)
    }

    @Test fun compoundShellCommandRequiresApproval() {
        assertTrue(ActionSafetyPolicy.assess("ls; rm -rf src").requiresApproval)
        assertTrue(ActionSafetyPolicy.assess("ls | rm -rf src").requiresApproval)
    }

    @Test fun recursiveWorkspaceDeleteRequiresApproval() {
        val dispatcher = ActionDispatcher(FakeFs())
        val result = dispatcher.deleteFile("src", recursive = true, source = com.anvex.core.model.MutationSource.AI_SHELL_COMMAND)
        assertFalse(result.success)
        assertTrue(result.error!!.startsWith("Approval required"))
    }
}

class AgentActionExecutorIntegrationTest {
    @org.junit.Test
    fun customExecutorReceivesShellAction() = kotlinx.coroutines.runBlocking {
        val fs = FakeFs()
        var received: BoltAction? = null
        val planner = AgentPlanner { _, _ ->
            AgentPlan("plan", "run", listOf(AgentStep("s1", "shell", BoltAction.ShellAction("echo hello"))))
        }
        val result = AgentOrchestrator(
            planner,
            ActionDispatcher(fs),
            AgentActionExecutor { action ->
                received = action
                ActionResult(true, output = "hello")
            },
        ).run("run")
        org.junit.Assert.assertEquals(AgentRunState.COMPLETED, result.state)
        org.junit.Assert.assertTrue(received is BoltAction.ShellAction)
    }
}
