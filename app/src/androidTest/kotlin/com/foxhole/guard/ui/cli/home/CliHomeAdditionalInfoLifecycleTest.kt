package com.foxhole.guard.ui.cli.home

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.foxhole.core.model.HomeAdditionalInfoCategory
import com.foxhole.guard.ui.cli.map.CliPixelMapAssetPrewarmEffect
import com.foxhole.guard.ui.trafficmap.TrafficMapAssetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CliHomeAdditionalInfoLifecycleTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mapColdStartPrewarmsAssetsWhileTheReadinessGateIsClosed() {
        val assetState = mutableStateOf<TrafficMapAssetState>(TrafficMapAssetState.Idle)
        val prewarmCalls = AtomicInteger()
        val gateReady = AtomicBoolean(true)

        composeRule.setContent {
            val ready = rememberCliHomeMapAssetsReady(
                category = HomeAdditionalInfoCategory.MAP,
                assetState = assetState.value,
                prewarm = { prewarmCalls.incrementAndGet() },
            )
            SideEffect { gateReady.set(ready) }
        }

        composeRule.waitUntil { prewarmCalls.get() == 1 }
        assertFalse(gateReady.get())

        composeRule.runOnIdle {
            assetState.value = TrafficMapAssetState.Ready(emptyList())
        }
        composeRule.waitUntil { gateReady.get() }
        assertTrue(gateReady.get())
    }

    @Test
    fun mapErrorFallbackDoesNotRetryWhenThePixelMapRemounts() {
        val assetState = mutableStateOf<TrafficMapAssetState>(TrafficMapAssetState.Error)
        val mapMounted = mutableStateOf(true)
        val prewarmCalls = AtomicInteger()

        composeRule.setContent {
            if (mapMounted.value) {
                CliPixelMapAssetPrewarmEffect(
                    assetState = assetState.value,
                    prewarm = { prewarmCalls.incrementAndGet() },
                )
            }
        }

        composeRule.runOnIdle { assertEquals(0, prewarmCalls.get()) }
        composeRule.runOnIdle { mapMounted.value = false }
        composeRule.runOnIdle { mapMounted.value = true }
        composeRule.runOnIdle { assertEquals(0, prewarmCalls.get()) }

        composeRule.runOnIdle { assetState.value = TrafficMapAssetState.Idle }
        composeRule.waitUntil { prewarmCalls.get() == 1 }
        assertEquals(1, prewarmCalls.get())
    }

    @Test
    fun mapErrorFallbackDoesNotRetryWhenTheHomeGateRemounts() {
        val assetState = mutableStateOf<TrafficMapAssetState>(TrafficMapAssetState.Error)
        val homeMounted = mutableStateOf(true)
        val prewarmCalls = AtomicInteger()
        val gateReady = AtomicBoolean(false)

        composeRule.setContent {
            if (homeMounted.value) {
                val ready = rememberCliHomeMapAssetsReady(
                    category = HomeAdditionalInfoCategory.MAP,
                    assetState = assetState.value,
                    prewarm = { prewarmCalls.incrementAndGet() },
                )
                SideEffect { gateReady.set(ready) }
            }
        }

        composeRule.runOnIdle {
            assertTrue(gateReady.get())
            assertEquals(0, prewarmCalls.get())
            homeMounted.value = false
        }
        composeRule.runOnIdle { homeMounted.value = true }
        composeRule.runOnIdle { assertEquals(0, prewarmCalls.get()) }

        composeRule.runOnIdle { assetState.value = TrafficMapAssetState.Idle }
        composeRule.waitUntil { prewarmCalls.get() == 1 }
        assertEquals(1, prewarmCalls.get())
    }
}
