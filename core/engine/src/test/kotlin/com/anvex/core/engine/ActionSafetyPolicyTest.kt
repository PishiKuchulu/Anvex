package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionSafetyPolicyTest {
    @Test fun recursiveDeleteRequiresApproval() {
        assertEquals(ActionSafetyPolicy.Risk.REQUIRES_APPROVAL, ActionSafetyPolicy.assess("rm -rf build").risk)
    }
    @Test fun normalFileCommandIsSafe() {
        assertEquals(ActionSafetyPolicy.Risk.SAFE, ActionSafetyPolicy.assess("ls src").risk)
    }
    @Test fun newlineInjectionRequiresApproval() {
        assertEquals(ActionSafetyPolicy.Risk.REQUIRES_APPROVAL, ActionSafetyPolicy.assess("ls\nrm -rf build").risk)
    }
    @Test fun shellEscapeRequiresApproval() {
        assertEquals(ActionSafetyPolicy.Risk.REQUIRES_APPROVAL, ActionSafetyPolicy.assess("ls \\; rm -rf build").risk)
    }
    @Test fun hardResetRequiresApproval() {
        assertEquals(ActionSafetyPolicy.Risk.REQUIRES_APPROVAL, ActionSafetyPolicy.assess("git reset --hard").risk)
    }

    // SEC-003 (forensic audit, 2026-11-07): a lone `&` must not hide a
    // second command behind an innocent first segment.
    @Test fun loneAmpersandHidingDeleteRequiresApproval() {
        assertEquals(ActionSafetyPolicy.Risk.REQUIRES_APPROVAL, ActionSafetyPolicy.assess("echo hi & rm -rf .").risk)
    }
    @Test fun benignBackgroundingStaysSafe() {
        assertEquals(ActionSafetyPolicy.Risk.SAFE, ActionSafetyPolicy.assess("sleep 1 & echo done").risk)
    }

    // SEC-001 (forensic audit, 2026-11-07): environment dumps and
    // variable expansion can read the Pi process's API key.
    @Test fun printenvRequiresApproval() {
        assertEquals(ActionSafetyPolicy.Risk.REQUIRES_APPROVAL, ActionSafetyPolicy.assess("printenv").risk)
        assertEquals(ActionSafetyPolicy.Risk.REQUIRES_APPROVAL, ActionSafetyPolicy.assess("printenv HOME").risk)
    }
    @Test fun bareEnvRequiresApproval() {
        assertEquals(ActionSafetyPolicy.Risk.REQUIRES_APPROVAL, ActionSafetyPolicy.assess("env").risk)
    }
    @Test fun variableExpansionRequiresApproval() {
        assertEquals(ActionSafetyPolicy.Risk.REQUIRES_APPROVAL, ActionSafetyPolicy.assess("echo \$ANVEX_PI_API_KEY").risk)
        assertEquals(ActionSafetyPolicy.Risk.REQUIRES_APPROVAL, ActionSafetyPolicy.assess("cat \${HOME}/x").risk)
    }
    @Test fun envWrapperWithCommandIsJudgedByCommand() {
        assertEquals(ActionSafetyPolicy.Risk.SAFE, ActionSafetyPolicy.assess("env FOO=1 ls src").risk)
        assertEquals(ActionSafetyPolicy.Risk.REQUIRES_APPROVAL, ActionSafetyPolicy.assess("env rm -rf build").risk)
    }

    // SEC-003: interpreter flag clusters and node's -p are code execution.
    @Test fun interpreterFlagClustersRequireApproval() {
        assertEquals(ActionSafetyPolicy.Risk.REQUIRES_APPROVAL, ActionSafetyPolicy.assess("node -p 1+1").risk)
        assertEquals(ActionSafetyPolicy.Risk.REQUIRES_APPROVAL, ActionSafetyPolicy.assess("python3 -Bc pass").risk)
        assertEquals(ActionSafetyPolicy.Risk.REQUIRES_APPROVAL, ActionSafetyPolicy.assess("perl -le print").risk)
    }
    @Test fun interpreterScriptRunsStaySafe() {
        assertEquals(ActionSafetyPolicy.Risk.SAFE, ActionSafetyPolicy.assess("node server.js").risk)
        assertEquals(ActionSafetyPolicy.Risk.SAFE, ActionSafetyPolicy.assess("python3 -u script.py").risk)
        assertEquals(ActionSafetyPolicy.Risk.SAFE, ActionSafetyPolicy.assess("python3 -m http.server").risk)
    }
}
