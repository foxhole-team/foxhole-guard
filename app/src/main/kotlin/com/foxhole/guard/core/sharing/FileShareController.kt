package com.foxhole.guard.core.sharing

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.runtime.FoxCoreShareRuntime
import com.foxhole.core.runtime.NativeRuntimeSnapshot
import com.foxhole.core.runtime.RuntimeInstanceStore
import com.foxhole.guard.R
import com.foxhole.guard.core.diagnostics.DiagnosticsLogger
import com.foxhole.guard.core.security.ShareVaultKeySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.SecureRandom

/** Application-scoped owner of session-only Tor file publications. */
class FileShareController internal constructor(
    context: Context,
    private val runtimeStore: RuntimeInstanceStore,
    private val connection: StateFlow<ConnectionSnapshot>,
    private val keySource: ShareVaultKeySource,
    private val diagnostics: DiagnosticsLogger,
) {
    private val appContext = context.applicationContext
    private val io = FileShareIo(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val sessions = linkedMapOf<String, ActiveFileShare>()
    private val mutableState = MutableStateFlow(FileShareUiState())
    private var startupCleanupDone = false
    private var vaultGeneration: Long? = null
    private var publicationHandle: Long? = null
    private var auditJob: Job? = null

    val state: StateFlow<FileShareUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            mutex.withLock { ensureStartupCleanup() }
        }
        scope.launch {
            connection
                .map(::torRouteReady)
                .distinctUntilChanged()
                .collect { ready ->
                    if (!ready) {
                        mutex.withLock {
                            if (hasShareStateLocked()) closeAllLocked("tor_route_stopped")
                        }
                    }
                }
        }
    }

    suspend fun publish(
        uri: Uri,
        lifetimeMs: Long,
        maxDownloads: Int,
        password: CharArray?,
    ): FileShareUiItem {
        acquirePublishLock(password)
        var source: PreparedShareSource? = null
        var shareHandle: Long? = null
        var invitation: File? = null
        try {
            mutableState.value = mutableState.value.copy(busy = true)
            validateRequest(lifetimeMs, maxDownloads, password)
            requireTorRoute()
            ensureStartupCleanup()
            source = io.prepareSource(uri)
            requireTorRoute()

            val runtime = currentRuntime()
            val generation = runtimeStore.nativeSnapshot().nativeGeneration
            ensureVaultOpen(runtime, generation)
            val now = System.currentTimeMillis()
            val expiresAt = now + lifetimeMs
            val passwordBytes = password?.let(::asciiBytes)
            shareHandle = try {
                runtime.createShare(now, expiresAt, maxDownloads, passwordBytes)
            } finally {
                passwordBytes?.fill(0)
            }.orShareFailure(FileShareFailureReason.SHARE_CREATE_FAILED)
            val fileId =
                runtime.addShareFile(
                    shareHandle = shareHandle,
                    nowMs = System.currentTimeMillis(),
                    displayName = source.displayName,
                    mediaType = source.mediaType,
                    sourcePath = source.file.absolutePath,
                ).orShareFailure(FileShareFailureReason.SHARE_CREATE_FAILED)

            val publication =
                publicationHandle ?: runtime.publishShare(randomOnionNickname())
                    ?.also {
                        publicationHandle = it
                    }
                    .orShareFailure(FileShareFailureReason.PUBLICATION_FAILED)
            invitation = io.freshInvitationFile()
            requireShareSuccess(
                runtime.writeShareInvitation(publication, shareHandle, fileId, invitation.absolutePath),
                FileShareFailureReason.INVITATION_FAILED,
            )

            val id = randomLocalId()
            val active =
                ActiveFileShare(
                    id = id,
                    shareHandle = shareHandle,
                    invitation = invitation,
                    displayName = source.displayName,
                    sizeBytes = source.sizeBytes,
                    expiresAtMs = expiresAt,
                    maxDownloads = maxDownloads,
                    passwordProtected = password != null,
                )
            sessions[id] = active
            publishState(busy = false)
            diagnostics.record("security", "file share event=created")
            startAuditLoop()
            return active.toUiItem()
        } catch (error: Exception) {
            cleanupFailedPublish(shareHandle, invitation)
            throw normalizedPublishFailure(error)
        } finally {
            source?.file?.let(io::delete)
            password?.fill('\u0000')
            if (mutableState.value.busy) publishState(busy = false)
            mutex.unlock()
        }
    }

    private fun acquirePublishLock(password: CharArray?) {
        if (!mutex.tryLock()) {
            password?.fill('\u0000')
            throw FileShareException(FileShareFailureReason.BUSY)
        }
    }

    suspend fun revoke(id: String): Boolean =
        mutex.withLock {
            val removed = closeOneLocked(id, "revoked")
            publishState(busy = false)
            removed
        }

    suspend fun invitationIntent(id: String): Intent? =
        mutex.withLock {
            val invitation = sessions[id]?.invitation?.takeIf(File::isFile) ?: return@withLock null
            val uri =
                FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    invitation,
                )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, appContext.getString(R.string.cli_file_share_invitation_subject))
                clipData = ClipData.newUri(appContext.contentResolver, "FoxHole share", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

    fun close() {
        scope.launch {
            mutex.withLock { closeAllLocked("controller_closed") }
            scope.cancel()
        }
    }

    private fun validateRequest(
        lifetimeMs: Long,
        maxDownloads: Int,
        password: CharArray?,
    ) {
        if (lifetimeMs !in FILE_SHARE_MIN_LIFETIME_MS..FILE_SHARE_MAX_LIFETIME_MS ||
            maxDownloads !in FILE_SHARE_ALLOWED_DOWNLOAD_LIMITS
        ) {
            throw FileShareException(FileShareFailureReason.SHARE_CREATE_FAILED)
        }
        if (!validFileSharePassword(password)) {
            throw FileShareException(FileShareFailureReason.PASSWORD_INVALID)
        }
    }

    private fun requireTorRoute() {
        if (!torRouteReady(connection.value)) {
            throw FileShareException(FileShareFailureReason.TOR_REQUIRED)
        }
    }

    private fun currentRuntime(): FoxCoreShareRuntime =
        (runtimeStore.current() as? FoxCoreShareRuntime)
            ?.takeIf { runtimeStore.nativeSnapshot().hasEngineHandle }
            ?: throw FileShareException(FileShareFailureReason.CORE_UNAVAILABLE)

    private fun ensureVaultOpen(
        runtime: FoxCoreShareRuntime,
        generation: Long,
    ) {
        if (vaultGeneration == generation) return
        if (vaultGeneration != null || publicationHandle != null || sessions.isNotEmpty()) {
            closeAllLocked("runtime_generation_changed")
        }
        io.resetVault()
        val key =
            try {
                keySource.acquireKey()
            } catch (error: Exception) {
                throw FileShareException(FileShareFailureReason.VAULT_UNAVAILABLE, error)
            }
        val opened =
            try {
                runtime.openShareVault(io.vaultRoot.absolutePath, key)
            } finally {
                key.fill(0)
            }
        if (!opened) throw FileShareException(FileShareFailureReason.VAULT_UNAVAILABLE)
        vaultGeneration = generation
    }

    private fun cleanupFailedPublish(
        shareHandle: Long?,
        invitation: File?,
    ) {
        val runtime = runtimeStore.current() as? FoxCoreShareRuntime
        val revoked = shareHandle == null || runtime?.revokeShare(shareHandle) == true
        io.delete(invitation)
        when {
            !revoked -> closeAllLocked("publish_cleanup_failed")
            sessions.isEmpty() -> closeAllLocked("publish_failed")
        }
    }

    private fun startAuditLoop() {
        if (auditJob?.isActive == true) return
        auditJob =
            scope.launch {
                while (isActive) {
                    delay(FILE_SHARE_AUDIT_INTERVAL_MS)
                    val keepRunning = mutex.withLock { auditAndExpireLocked() }
                    if (!keepRunning) break
                }
            }
    }

    private fun auditAndExpireLocked(): Boolean {
        if (sessions.isEmpty()) return false
        if (!torRouteReady(connection.value)) {
            closeAllLocked("tor_route_stopped")
            return false
        }
        val runtime = runtimeStore.current() as? FoxCoreShareRuntime
        val snapshot = runtimeStore.nativeSnapshot()
        if (runtime == null || !isCurrentShareRuntime(vaultGeneration, snapshot)) {
            closeAllLocked("runtime_unavailable")
            return false
        }
        val publication = publicationHandle
        if (publication == null || runtime.publicationAddress(publication) == null) {
            closeAllLocked("publication_unavailable")
            return false
        }
        val now = System.currentTimeMillis()
        val remove = linkedMapOf<String, Boolean>()
        sessions.values.forEach { session ->
            auditSession(runtime, session, now, remove)
        }
        remove.forEach { (id, strictRevoke) ->
            closeOneLocked(id, "expired_or_invalid", strictRevoke)
        }
        publishState(busy = false)
        return sessions.isNotEmpty()
    }

    private fun auditSession(
        runtime: FoxCoreShareRuntime,
        session: ActiveFileShare,
        now: Long,
        remove: MutableMap<String, Boolean>,
    ) {
        if (now >= session.expiresAtMs) {
            remove[session.id] = false
            return
        }
        val events = runtime.drainShareEventsJson(session.shareHandle, MAX_AUDIT_EVENTS).orEmpty()
        parseFileShareEventKinds(events).forEach { kind ->
            diagnostics.record("security", "file share event=$kind")
            when (kind) {
                "download_authorized" -> session.downloadsAuthorized += 1
                "download_limit_reached" -> session.downloadsAuthorized = session.maxDownloads
                "expired", "revoked" -> remove.putIfAbsent(session.id, false)
                "integrity_failure" -> remove[session.id] = true
            }
        }
    }

    private fun closeOneLocked(
        id: String,
        reason: String,
        strictRevoke: Boolean = true,
    ): Boolean {
        val session = sessions[id] ?: return false
        val runtime = runtimeStore.current() as? FoxCoreShareRuntime
        if (sessions.size == 1) {
            publicationHandle?.let { handle -> runtime?.withdrawShare(handle) }
            publicationHandle = null
        }
        val revoked = runtime?.revokeShare(session.shareHandle) == true
        if (strictRevoke && !revoked) {
            closeAllLocked("revoke_failed")
            return true
        }
        sessions.remove(id)
        io.delete(session.invitation)
        diagnostics.record("security", "file share event=$reason")
        if (sessions.isEmpty()) resetClosedVault()
        return true
    }

    private fun closeAllLocked(reason: String) {
        val runtime = runtimeStore.current() as? FoxCoreShareRuntime
        publicationHandle?.let { handle -> runtime?.withdrawShare(handle) }
        publicationHandle = null
        sessions.values.forEach { session ->
            runtime?.revokeShare(session.shareHandle)
            io.delete(session.invitation)
        }
        if (sessions.isNotEmpty()) diagnostics.record("security", "file shares closed reason=$reason")
        sessions.clear()
        resetClosedVault()
        publishState(busy = false)
    }

    private fun resetClosedVault() {
        vaultGeneration = null
        runCatching(io::resetVault)
            .onFailure { diagnostics.record("security", "file share vault cleanup failed") }
    }

    private fun hasShareStateLocked(): Boolean =
        vaultGeneration != null || publicationHandle != null || sessions.isNotEmpty()

    private fun ensureStartupCleanup() {
        if (startupCleanupDone) return
        io.cleanupStaleSessionFiles()
        startupCleanupDone = true
    }

    private fun publishState(busy: Boolean) {
        mutableState.value =
            FileShareUiState(
                busy = busy,
                active = sessions.values.map(ActiveFileShare::toUiItem),
            )
    }

    private data class ActiveFileShare(
        val id: String,
        val shareHandle: Long,
        val invitation: File,
        val displayName: String,
        val sizeBytes: Long,
        val expiresAtMs: Long,
        val maxDownloads: Int,
        val passwordProtected: Boolean,
        var downloadsAuthorized: Int = 0,
    ) {
        fun toUiItem(): FileShareUiItem =
            FileShareUiItem(
                id = id,
                displayName = displayName,
                sizeBytes = sizeBytes,
                expiresAtMs = expiresAtMs,
                maxDownloads = maxDownloads,
                downloadsAuthorized = downloadsAuthorized.coerceAtMost(maxDownloads),
                passwordProtected = passwordProtected,
            )
    }

    private companion object {
        const val MAX_AUDIT_EVENTS = 64
    }
}

/**
 * The only route a share may be published over.
 *
 * `internal` rather than private for the same reason as [isCurrentShareRuntime]
 * below: this is the predicate that keeps a publication from ever reaching
 * anything but an onion service, and a contract that decides that deserves a
 * test of its own rather than only being exercised through a live Tor session.
 */
internal fun torRouteReady(snapshot: ConnectionSnapshot): Boolean =
    snapshot.state == ConnectionState.CONNECTED && snapshot.torActive

internal fun isCurrentShareRuntime(
    vaultGeneration: Long?,
    snapshot: NativeRuntimeSnapshot,
): Boolean =
    vaultGeneration != null &&
        snapshot.hasEngineHandle &&
        snapshot.nativeGeneration == vaultGeneration

private fun asciiBytes(chars: CharArray): ByteArray =
    ByteArray(chars.size) { index -> chars[index].code.toByte() }

private fun randomOnionNickname(): String = "fhshare${randomHex(10)}"

private fun randomLocalId(): String = randomHex(12)

private fun randomHex(bytes: Int): String =
    ByteArray(bytes)
        .also(SecureRandom()::nextBytes)
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

private fun <T : Any> T?.orShareFailure(reason: FileShareFailureReason): T =
    this ?: throw FileShareException(reason)

private fun requireShareSuccess(
    success: Boolean,
    reason: FileShareFailureReason,
) {
    if (!success) throw FileShareException(reason)
}

private fun normalizedPublishFailure(error: Exception): Exception =
    when (error) {
        is CancellationException, is FileShareException -> error
        else -> FileShareException(FileShareFailureReason.CORE_UNAVAILABLE, error)
    }
