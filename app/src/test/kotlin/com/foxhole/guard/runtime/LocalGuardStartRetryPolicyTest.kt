package com.foxhole.guard.runtime

import com.foxhole.core.runtime.FoxCoreRuntimeException
import com.foxhole.core.runtime.FoxCoreRuntimeFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

internal class LocalGuardStartRetryPolicyTest {
    @Test
    fun `unsupported app routing cannot schedule an automatic heal`() {
        val constructor = FoxCoreRuntimeException::class.java
            .getDeclaredConstructor(FoxCoreRuntimeFailure::class.java)
            .apply { isAccessible = true }
        val failure = constructor.newInstance(FoxCoreRuntimeFailure.FLOW_ATTRIBUTION_UNAVAILABLE)

        assertFalse(shouldRetryLocalGuardStart(failure))
        assertFalse(shouldRetryLocalGuardStart(IllegalStateException("wrapper", failure)))
    }

    @Test
    fun `transient native and network failures retain the bounded heal path`() {
        val constructor = FoxCoreRuntimeException::class.java
            .getDeclaredConstructor(FoxCoreRuntimeFailure::class.java)
            .apply { isAccessible = true }

        assertTrue(shouldRetryLocalGuardStart(constructor.newInstance(FoxCoreRuntimeFailure.NATIVE_START_FAILED)))
        assertTrue(shouldRetryLocalGuardStart(SocketTimeoutException()))
        assertTrue(shouldRetryLocalGuardStart(null))
    }
}
