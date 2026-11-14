package com.foxhole.guard.core.sentinel

import android.content.pm.PackageManager
import com.foxhole.core.model.CapabilityState
import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledAppCapabilityStateTest {
    @Test fun declaredDisabledComponentIsNotEnabledOrGranted() {
        assertEquals(CapabilityState.NO, resolveComponentEnabled(true, false, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT))
        assertEquals(CapabilityState.NO, resolveComponentEnabled(true, true, PackageManager.COMPONENT_ENABLED_STATE_DISABLED))
        assertEquals(CapabilityState.NO, resolveComponentEnabled(false, true, PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
    }

    @Test fun explicitEnableAndUnknownOverrideAreDifferentStates() {
        assertEquals(CapabilityState.YES, resolveComponentEnabled(true, false, PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
        assertEquals(CapabilityState.UNKNOWN, resolveComponentEnabled(true, true, -1))
    }
}
