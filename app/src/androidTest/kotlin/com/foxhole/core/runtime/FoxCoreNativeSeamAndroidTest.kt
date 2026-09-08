package com.foxhole.core.runtime

import com.foxhole.guard.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FoxCoreNativeSeamAndroidTest {
    @Test
    fun shippedElfExecutesThroughTheStableProductFacade() {
        val json = Json { ignoreUnknownKeys = false }

        assertFalse(FoxholeNativeEngine.nativeVersion().isBlank())
        assertEquals(BuildConfig.FOXCORE_SOURCE_VERSION, FoxholeNativeEngine.nativeVersion())
        assertEquals(FoxholeNativeEngine.ABI_VERSION, FoxholeNativeEngine.nativeAbiVersion())
        val capabilities = json.parseToJsonElement(FoxholeNativeEngine.nativeCapabilities()).jsonObject
        assertEquals(
            FoxholeNativeEngine.ABI_VERSION,
            capabilities.getValue("abi_version").jsonPrimitive.content.toInt(),
        )

        val trafficMap = json.parseToJsonElement(FoxholeNativeEngine.nativeTrafficMap(0L)).jsonObject
        assertEquals(0, trafficMap.getValue("generation").jsonPrimitive.content.toInt())
        assertEquals(0, trafficMap.getValue("connections").jsonArray.size)
    }
}
