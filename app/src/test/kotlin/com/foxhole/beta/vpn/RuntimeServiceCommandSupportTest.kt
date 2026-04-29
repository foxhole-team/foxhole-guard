package com.foxhole.beta.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeServiceCommandSupportTest {
    @Test
    fun `disconnect command is dispatched as priority teardown`() {
        assertTrue(isPriorityRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_DISCONNECT))
    }

    @Test
    fun `connect reload and restore commands stay serialized`() {
        assertFalse(isPriorityRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_CONNECT))
        assertFalse(isPriorityRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_RELOAD))
        assertFalse(isPriorityRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_RESTORE))
        assertFalse(isPriorityRuntimeServiceCommand(null))
    }
}
