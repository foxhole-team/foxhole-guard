package com.foxhole.guard.core.sentinel.scanner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

internal data class ScannerInputFile(val claim: ScannerDescriptorClaim, val file: File)

internal class IsolatedScannerClient(context: Context) {
    private val context = context.applicationContext

    suspend fun inspect(
        inputs: List<ScannerInputFile>,
        owner: ScannerRequestOwner,
        yaraSource: ScannerYaraSource? = null,
        maxArtifactBytes: Int = 16 * 1024 * 1024,
        currentOwner: () -> ScannerRequestOwner,
    ): ScannerStaticResult = coroutineScope {
        validateScannerDescriptorClaims(inputs.map { it.claim })
        requireScannerArtifactBudget(maxArtifactBytes)
        val session = ScannerBindingSession(context, owner, currentOwner, this)
        var primary: Throwable? = null
        try {
            session.start(inputs, yaraSource, maxArtifactBytes)
            session.awaitResult()
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            session.settle(primary)
        }
    }
}

private class ScannerBindingSession(
    private val context: Context,
    private val owner: ScannerRequestOwner,
    private val currentOwner: () -> ScannerRequestOwner,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private val publication = ScannerPublicationLease(owner)
    private val descriptors = mutableListOf<ParcelFileDescriptor>()
    private val result = CompletableDeferred<ScannerStaticResult>()
    private val remote = AtomicReference<Messenger?>()
    private var bound = false
    private lateinit var request: Bundle
    private val replies = Messenger(Handler(Looper.getMainLooper(), ::receive))
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) = connected(binder)
        override fun onServiceDisconnected(name: ComponentName) = unavailable("Scanner process stopped")
        override fun onNullBinding(name: ComponentName) = unavailable("Scanner process unavailable")
        override fun onBindingDied(name: ComponentName) = unavailable("Scanner binding stopped")
    }

    suspend fun start(inputs: List<ScannerInputFile>, yaraSource: ScannerYaraSource?, maxArtifactBytes: Int) {
        withContext(Dispatchers.IO) { openScannerInputs(inputs, descriptors) }
        request = scannerRequestBundle(inputs.map { it.claim }, descriptors, owner)
        request.putInt("max_artifact_bytes", maxArtifactBytes)
        if (yaraSource != null) {
            withContext(Dispatchers.IO) {
                val descriptor = ParcelFileDescriptor.open(yaraSource.file, ParcelFileDescriptor.MODE_READ_ONLY)
                descriptors += descriptor
                request.putParcelable("yara_source", descriptor)
            }
            request.putInt("yara_source_bytes", yaraSource.bytes)
            request.putString("yara_source_sha256", yaraSource.sha256)
        }
        withContext(Dispatchers.Main.immediate) {
            bound = context.bindService(Intent(context, IsolatedSentinelScannerService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
        if (!bound) throw IOException("Scanner binding unavailable")
    }

    suspend fun awaitResult(): ScannerStaticResult =
        withTimeout(ScannerIpcCodes.OWNER_WATCHDOG_MILLIS + 2000) { result.await() }

    private fun connected(binder: IBinder) {
        if (result.isCompleted) return
        try {
            if (!publication.mayPublish(owner, currentOwner())) {
                unavailable("Scanner owner changed")
                return
            }
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
            return
        }
        remote.set(Messenger(binder))
        scope.launch(Dispatchers.IO) {
            try {
                val message = Message.obtain(null, ScannerIpcCodes.SCAN).apply {
                    data = request
                    replyTo = replies
                }
                remote.get()?.send(message)
            } catch (_: RemoteException) {
                unavailable("Scanner request unavailable")
            }
        }
    }

    private fun receive(message: Message): Boolean {
        if (message.what != ScannerIpcCodes.RESULT || result.isCompleted) return true
        try {
            val decoded = decodeScannerStaticReply(message.data, message.sendingUid, owner)
            if (publication.mayPublish(owner, currentOwner())) {
                result.complete(decoded)
            } else {
                unavailable("Scanner owner changed")
            }
        } catch (cancelled: CancellationException) {
            result.completeExceptionally(cancelled)
        } catch (fatal: Error) {
            result.completeExceptionally(fatal)
        } catch (_: Exception) {
            unavailable("Invalid scanner response")
        }
        return true
    }

    private fun unavailable(message: String) { result.completeExceptionally(IOException(message)) }

    suspend fun settle(primary: Throwable?) {
        publication.cancel()
        result.cancel()
        val cleanup = mutableListOf<Throwable>()
        withContext(NonCancellable) {
            settleScannerCleanup(cleanup) { withContext(Dispatchers.IO) { shutdown() } }
            settleScannerCleanup(cleanup) {
                withContext(Dispatchers.Main.immediate) { if (bound) context.unbindService(connection) }
            }
            settleScannerCleanup(cleanup) { withContext(Dispatchers.IO) { closeInputs() } }
        }
        settleScannerFailures(primary, cleanup)
    }

    private fun shutdown() {
        try {
            remote.get()?.send(
                Message.obtain(null, ScannerIpcCodes.SHUTDOWN).apply {
                    data = Bundle().apply { putString("request_id", owner.requestId) }
                }
            )
        } catch (_: RemoteException) { }
    }

    private fun closeInputs() {
        var first: IOException? = null
        descriptors.forEach { descriptor ->
            try { descriptor.close() } catch (failure: IOException) {
                if (first == null) first = failure else first.addSuppressed(failure)
            }
        }
        first?.let { throw it }
    }
}

internal suspend fun settleScannerCleanup(failures: MutableList<Throwable>, action: suspend () -> Unit) {
    try { action() } catch (failure: Throwable) { failures += failure }
}

internal fun settleScannerFailures(primary: Throwable?, cleanup: List<Throwable>) {
    if (cleanup.isEmpty()) return
    val critical = cleanup.firstOrNull { it is Error } ?: cleanup.firstOrNull { it is CancellationException }
    val selected = when {
        primary is Error || primary is CancellationException -> primary
        critical != null -> critical
        primary != null -> primary
        else -> cleanup.first()
    }
    if (primary != null && selected !== primary) selected.addSuppressed(primary)
    cleanup.filter { it !== selected && it !== primary }.forEach(selected::addSuppressed)
    if (selected !== primary) throw selected
}

private fun openScannerInputs(inputs: List<ScannerInputFile>, opened: MutableList<ParcelFileDescriptor>) {
    inputs.forEach { input -> opened += ParcelFileDescriptor.open(input.file, ParcelFileDescriptor.MODE_READ_ONLY) }
}

private fun scannerRequestBundle(
    claims: List<ScannerDescriptorClaim>,
    descriptors: List<ParcelFileDescriptor>,
    owner: ScannerRequestOwner,
): Bundle = Bundle().apply {
    putInt("protocol", ScannerIpcCodes.PROTOCOL_VERSION)
    putString("request_id", owner.requestId)
    putString("installation_id", owner.installationId)
    putString("rule_epoch", owner.ruleEpoch)
    putStringArrayList("labels", ArrayList(claims.map { it.label }))
    putIntArray("kinds", claims.map { it.kind.ordinal }.toIntArray())
    putLongArray("sizes", claims.map { it.bytes }.toLongArray())
    putParcelableArrayList("descriptors", ArrayList(descriptors))
}
