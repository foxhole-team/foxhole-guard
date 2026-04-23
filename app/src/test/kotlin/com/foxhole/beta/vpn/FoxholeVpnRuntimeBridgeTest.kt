package com.foxhole.beta.vpn

import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxholeVpnRuntimeBridgeTest {
    @Test
    fun `immediate traffic sample requests are emitted without waiting for polling cadence`() =
        runBlocking {
            val request =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(250L) {
                        FoxholeVpnRuntimeBridge.immediateTrafficSampleRequests.first()
                    }
                    true
                }

            FoxholeVpnRuntimeBridge.requestImmediateTrafficSample()

            assertTrue(request.await())
        }
}
