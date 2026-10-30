package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorRuntimeAbiSelectionTest {
    @Test
    fun `ARM32 process on an ARM64 device selects only its 32 bit assets`() {
        val observed = mutableListOf<String>()
        val selected = selectTorRuntimeAssetAbi(false, listOf("arm64-v8a"), listOf("armeabi-v7a", "armeabi")) { abi ->
            observed += abi
            abi in setOf("arm64-v8a", "armeabi-v7a")
        }
        assertEquals("armeabi-v7a", selected)
        assertEquals(listOf("armeabi-v7a"), observed)
    }

    @Test
    fun `64 bit asset preference and same bitness fallback retain their order`() {
        val observed = mutableListOf<String>()
        val selected = selectTorRuntimeAssetAbi(true, listOf("arm64-v8a", "x86_64"), listOf("armeabi-v7a")) { abi ->
            observed += abi
            abi == "x86_64"
        }
        assertEquals("x86_64", selected)
        assertEquals(listOf("arm64-v8a", "x86_64"), observed)
    }

    @Test
    fun `32 bit asset fallback searches only the reported 32 bit ABI list`() {
        val observed = mutableListOf<String>()
        val selected = selectTorRuntimeAssetAbi(false, listOf("arm64-v8a"), listOf("armeabi-v7a", "armeabi")) { abi ->
            observed += abi
            abi == "armeabi"
        }
        assertEquals("armeabi", selected)
        assertEquals(listOf("armeabi-v7a", "armeabi"), observed)
    }

    @Test
    fun `unavailable process ABI never falls back across bitness`() {
        for (processIs64Bit in listOf(true, false)) {
            val observed = mutableListOf<String>()
            assertNull(selectTorRuntimeAssetAbi(processIs64Bit, listOf("arm64-v8a"), listOf("armeabi-v7a")) { abi ->
                observed += abi
                abi == if (processIs64Bit) "armeabi-v7a" else "arm64-v8a"
            })
            assertEquals(if (processIs64Bit) listOf("arm64-v8a") else listOf("armeabi-v7a"), observed)
        }
        assertNull(selectTorRuntimeAssetAbi(false, listOf("arm64-v8a"), emptyList()) { true })
    }
}
