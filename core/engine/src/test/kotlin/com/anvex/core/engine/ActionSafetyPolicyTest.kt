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
}
