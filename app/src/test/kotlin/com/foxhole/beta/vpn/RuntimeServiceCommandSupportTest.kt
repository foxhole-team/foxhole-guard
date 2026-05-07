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

    @Test
    fun `null and unknown commands fail closed`() {
        assertTrue(isFailClosedRuntimeServiceCommand(null))
        assertTrue(isFailClosedRuntimeServiceCommand("com.foxhole.beta.action.UNKNOWN"))

        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_CONNECT))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_DISCONNECT))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_RELOAD))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_RESTORE))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD))
    }
}
