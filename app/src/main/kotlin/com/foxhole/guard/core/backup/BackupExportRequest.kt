package com.foxhole.guard.core.backup

import com.foxhole.guard.core.security.GuardCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class BackupExportSelection(
    val includeProfiles: Boolean = true,
    val includeSettings: Boolean = true,
    val encrypted: Boolean = false,
) {
    init {
        require(includeProfiles || includeSettings) { "backup scope is empty" }
    }
}

internal class PendingBackupExport private constructor(
    val selection: BackupExportSelection,
    private var password: CharArray?,
) : AutoCloseable {
    private var transferred = false
    private var closed = false

    @Synchronized
    fun transfer(): OwnedBackupExportRequest {
        check(!closed && !transferred) { "backup request is unavailable" }
        transferred = true
        return OwnedBackupExportRequest(selection, password).also { password = null }
    }

    @Synchronized
    override fun close() {
        password?.fill('\u0000')
        password = null
        closed = true
    }

    companion object {
        fun create(
            selection: BackupExportSelection,
            password: CharArray? = null,
        ): PendingBackupExport {
            val owned = try {
                password?.copyOf()
            } finally {
                password?.fill('\u0000')
            }
            try {
                if (selection.encrypted) {
                    require(owned != null && owned.size >= BACKUP_PASSWORD_MIN_CHARS) {
                        "backup password is too short"
                    }
                } else {
                    require(owned == null) { "plaintext backup does not use a password" }
                }
                return PendingBackupExport(selection, owned)
            } catch (error: Throwable) {
                owned?.fill('\u0000')
                throw error
            }
        }
    }
}

internal class OwnedBackupExportRequest internal constructor(
    val selection: BackupExportSelection,
    private var password: CharArray?,
) : AutoCloseable {
    private var closed = false

    @Synchronized
    fun <T> withPassword(block: (CharArray?) -> T): T {
        check(!closed) { "backup request is unavailable" }
        return block(password)
    }

    fun encode(document: BackupDocument, crypto: GuardCrypto): String =
        withPassword { password ->
            if (selection.encrypted) {
                encryptBackupDocument(document, checkNotNull(password), crypto)
            } else {
                encodeBackupDocument(document).also { payload ->
                    val bytes = payload.toByteArray(Charsets.UTF_8)
                    try {
                        requirePortableBackupPlaintextSize(bytes.size)
                    } finally {
                        bytes.fill(0)
                    }
                }
            }
        }

    @Synchronized
    override fun close() {
        password?.fill('\u0000')
        password = null
        closed = true
    }
}

internal fun CoroutineScope.launchOwnedBackupExport(
    request: OwnedBackupExportRequest,
    block: suspend (OwnedBackupExportRequest) -> Unit,
): Job = launchOwnedBackupWork(request, block)

internal fun <T : AutoCloseable> CoroutineScope.launchOwnedBackupWork(
    resource: T,
    block: suspend (T) -> Unit,
): Job {
    val job = launch(start = CoroutineStart.LAZY) {
        try {
            block(resource)
        } finally {
            resource.close()
        }
    }
    // A cancelled parent can prevent the body and its finally from ever starting.
    job.invokeOnCompletion { resource.close() }
    job.start()
    return job
}
