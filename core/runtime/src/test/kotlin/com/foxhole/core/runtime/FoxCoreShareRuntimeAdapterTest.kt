package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxCoreShareRuntimeAdapterTest {
    @Test
    fun `engine-bound operations require a live attached generation`() {
        val native = RecordingShareNative()
        val adapter = FoxCoreShareRuntimeAdapter(native)

        assertFalse(adapter.openShareVault("/private/vault", ByteArray(32)))
        assertNull(adapter.createShare(1L, 2L, 1, null))
        assertNull(adapter.publishShare("session", 80))
        assertEquals(emptyList<Long>(), native.engineHandles)

        adapter.attach(41L)
        assertTrue(adapter.openShareVault("/private/vault", ByteArray(32)))
        assertEquals(71L, adapter.createShare(1L, 2L, 1, null))
        assertEquals(73L, adapter.publishShare("session", 80))
        assertEquals(listOf(41L, 41L, 41L), native.engineHandles)

        adapter.detach()
        assertFalse(adapter.openShareVault("/private/vault", ByteArray(32)))
        assertNull(adapter.publishShare("next", 80))
        assertEquals(listOf(41L, 41L, 41L), native.engineHandles)
    }

    @Test
    fun `event drain is bounded at the JNI edge`() {
        val native = RecordingShareNative()
        val adapter = FoxCoreShareRuntimeAdapter(native)

        assertEquals("{}", adapter.drainShareEventsJson(7L, Int.MAX_VALUE))
        assertEquals(512, native.lastEventLimit)
        assertEquals("{}", adapter.drainShareEventsJson(7L, Int.MIN_VALUE))
        assertEquals(1, native.lastEventLimit)
    }
}

private class RecordingShareNative : FoxCoreShareNativeApi {
    val engineHandles = mutableListOf<Long>()
    var lastEventLimit: Int? = null

    override fun openVault(handle: Long, root: String, key: ByteArray): Int {
        engineHandles += handle
        return FOXCORE_COMPONENT_RESULT_OK
    }

    override fun createShare(
        handle: Long,
        nowMs: Long,
        expiresAtMs: Long,
        maxDownloads: Int,
        password: ByteArray?,
    ): Long {
        engineHandles += handle
        return 71L
    }

    override fun addFile(
        shareHandle: Long,
        nowMs: Long,
        displayName: String,
        mediaType: String,
        sourcePath: String,
    ): String = "file-id"

    override fun revokeShare(shareHandle: Long): Int = FOXCORE_COMPONENT_RESULT_OK

    override fun drainEvents(shareHandle: Long, max: Int): String {
        lastEventLimit = max
        return "{}"
    }

    override fun publishShare(handle: Long, nickname: String, virtualPort: Int): Long {
        engineHandles += handle
        return 73L
    }

    override fun publicationAddress(publicationHandle: Long): String = "example.onion"

    override fun withdrawShare(publicationHandle: Long): Int = FOXCORE_COMPONENT_RESULT_OK

    override fun writeInvitation(
        publicationHandle: Long,
        shareHandle: Long,
        fileIdHex: String,
        destinationPath: String,
    ): Int = FOXCORE_COMPONENT_RESULT_OK
}
