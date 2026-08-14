package com.foxhole.core.runtime

import com.foxhole.core.component.FoxholeNativeShares

/** Share operations remain tied to the one currently running native engine generation. */
interface FoxCoreShareRuntime {
    fun openShareVault(root: String, key: ByteArray): Boolean

    fun createShare(nowMs: Long, expiresAtMs: Long, maxDownloads: Int, password: ByteArray?): Long?

    fun addShareFile(
        shareHandle: Long,
        nowMs: Long,
        displayName: String,
        mediaType: String,
        sourcePath: String,
    ): String?

    fun revokeShare(shareHandle: Long): Boolean

    fun drainShareEventsJson(shareHandle: Long, max: Int): String?

    fun publishShare(nickname: String, virtualPort: Int = 80): Long?

    fun publicationAddress(publicationHandle: Long): String?

    fun withdrawShare(publicationHandle: Long): Boolean

    fun writeShareInvitation(
        publicationHandle: Long,
        shareHandle: Long,
        fileIdHex: String,
        destinationPath: String,
    ): Boolean
}

internal interface FoxCoreShareNativeApi {
    fun openVault(handle: Long, root: String, key: ByteArray): Int

    fun createShare(
        handle: Long,
        nowMs: Long,
        expiresAtMs: Long,
        maxDownloads: Int,
        password: ByteArray?,
    ): Long

    fun addFile(
        shareHandle: Long,
        nowMs: Long,
        displayName: String,
        mediaType: String,
        sourcePath: String,
    ): String?

    fun revokeShare(shareHandle: Long): Int

    fun drainEvents(shareHandle: Long, max: Int): String?

    fun publishShare(handle: Long, nickname: String, virtualPort: Int): Long

    fun publicationAddress(publicationHandle: Long): String?

    fun withdrawShare(publicationHandle: Long): Int

    fun writeInvitation(
        publicationHandle: Long,
        shareHandle: Long,
        fileIdHex: String,
        destinationPath: String,
    ): Int
}

internal object JniFoxCoreShareNativeApi : FoxCoreShareNativeApi {
    override fun openVault(handle: Long, root: String, key: ByteArray): Int =
        FoxholeNativeShares.nativeOpenVault(handle, root, key)

    override fun createShare(
        handle: Long,
        nowMs: Long,
        expiresAtMs: Long,
        maxDownloads: Int,
        password: ByteArray?,
    ): Long = FoxholeNativeShares.nativeCreateShare(handle, nowMs, expiresAtMs, maxDownloads, password)

    override fun addFile(
        shareHandle: Long,
        nowMs: Long,
        displayName: String,
        mediaType: String,
        sourcePath: String,
    ): String? = FoxholeNativeShares.nativeAddFile(shareHandle, nowMs, displayName, mediaType, sourcePath)

    override fun revokeShare(shareHandle: Long): Int = FoxholeNativeShares.nativeRevokeShare(shareHandle)

    override fun drainEvents(shareHandle: Long, max: Int): String? =
        FoxholeNativeShares.nativeDrainShareEvents(shareHandle, max)

    override fun publishShare(handle: Long, nickname: String, virtualPort: Int): Long =
        FoxholeNativeShares.nativePublishShare(handle, nickname, virtualPort)

    override fun publicationAddress(publicationHandle: Long): String? =
        FoxholeNativeShares.nativePublicationAddress(publicationHandle)

    override fun withdrawShare(publicationHandle: Long): Int =
        FoxholeNativeShares.nativeWithdrawShare(publicationHandle)

    override fun writeInvitation(
        publicationHandle: Long,
        shareHandle: Long,
        fileIdHex: String,
        destinationPath: String,
    ): Int =
        FoxholeNativeShares.nativeWriteInvitation(
            publicationHandle,
            shareHandle,
            fileIdHex,
            destinationPath,
        )
}

internal class FoxCoreShareRuntimeAdapter(
    private val native: FoxCoreShareNativeApi,
) : FoxCoreShareRuntime {
    @Volatile
    private var engineHandle: Long? = null

    fun attach(handle: Long) {
        engineHandle = handle.takeIf { it > 0L }
    }

    fun detach() {
        engineHandle = null
    }

    override fun openShareVault(root: String, key: ByteArray): Boolean =
        engineHandle?.let { handle ->
            runCatching { native.openVault(handle, root, key) == FOXCORE_COMPONENT_RESULT_OK }
                .getOrDefault(false)
        } ?: false

    override fun createShare(
        nowMs: Long,
        expiresAtMs: Long,
        maxDownloads: Int,
        password: ByteArray?,
    ): Long? =
        engineHandle
            ?.let { handle ->
                runCatching { native.createShare(handle, nowMs, expiresAtMs, maxDownloads, password) }
                    .getOrNull()
            }
            ?.takeIf { handle -> handle > 0L }

    override fun addShareFile(
        shareHandle: Long,
        nowMs: Long,
        displayName: String,
        mediaType: String,
        sourcePath: String,
    ): String? =
        runCatching { native.addFile(shareHandle, nowMs, displayName, mediaType, sourcePath) }
            .getOrNull()

    override fun revokeShare(shareHandle: Long): Boolean =
        runCatching { native.revokeShare(shareHandle) == FOXCORE_COMPONENT_RESULT_OK }
            .getOrDefault(false)

    override fun drainShareEventsJson(shareHandle: Long, max: Int): String? =
        runCatching { native.drainEvents(shareHandle, max.coerceIn(1, MAX_EVENT_BATCH)) }
            .getOrNull()

    override fun publishShare(nickname: String, virtualPort: Int): Long? =
        engineHandle
            ?.let { handle -> runCatching { native.publishShare(handle, nickname, virtualPort) }.getOrNull() }
            ?.takeIf { publication -> publication > 0L }

    override fun publicationAddress(publicationHandle: Long): String? =
        runCatching { native.publicationAddress(publicationHandle) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)

    override fun withdrawShare(publicationHandle: Long): Boolean =
        runCatching { native.withdrawShare(publicationHandle) == FOXCORE_COMPONENT_RESULT_OK }
            .getOrDefault(false)

    override fun writeShareInvitation(
        publicationHandle: Long,
        shareHandle: Long,
        fileIdHex: String,
        destinationPath: String,
    ): Boolean =
        runCatching {
            native.writeInvitation(publicationHandle, shareHandle, fileIdHex, destinationPath) ==
                FOXCORE_COMPONENT_RESULT_OK
        }.getOrDefault(false)

    private companion object {
        const val MAX_EVENT_BATCH = 512
    }
}

internal const val FOXCORE_COMPONENT_RESULT_OK = 0
