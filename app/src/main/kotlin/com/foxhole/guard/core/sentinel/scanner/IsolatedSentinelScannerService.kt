package com.foxhole.guard.core.sentinel.scanner

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import androidx.core.os.BundleCompat
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.core.sentinel.apk.ScanCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class IsolatedSentinelScannerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelled = AtomicBoolean()
    private val activeYara = AtomicReference<IsolatedYaraInspection?>()
    private val handler = Handler(Looper.getMainLooper()) { message ->
        handleControl(message)
        true
    }
    private val messenger = Messenger(handler)
    private var owner: ScannerRequestOwner? = null
    private var reply: Messenger? = null
    private var worker: Job? = null
    private var finished = false
    private var yaraRequested = false
    private val watchdog = Runnable {
        cancelled.set(true)
        owner?.let { captured ->
            publish(
                scannerUnavailableReply(
                    captured,
                    "OWNER_TIMEOUT"
                ).also { it.putBoolean("yara_requested", yaraRequested) }
            )
        }
        terminateOwnedProcess()
    }

    override fun onCreate() {
        super.onCreate()
        if (isStandardIsolatedScannerUid(Process.myUid())) {
            check(
                !(application as FoxholeApplication).runtimeOwnersInitialized
            ) { "Scanner runtime owner isolation failed" }
        }
    }

    override fun onBind(intent: Intent): IBinder? =
        messenger.binder.takeIf { isStandardIsolatedScannerUid(Process.myUid()) }

    override fun onUnbind(intent: Intent): Boolean {
        terminateOwnedProcess()
        return false
    }

    override fun onDestroy() {
        cancelled.set(true)
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    private fun handleControl(message: Message) {
        if (!scannerSenderIsOwner(message.sendingUid, applicationInfo.uid)) return
        when (message.what) {
            ScannerIpcCodes.SCAN -> begin(message)
            ScannerIpcCodes.CANCEL -> if (matchesOwner(message.data)) {
                cancelled.set(true)
                activeYara.get()?.cancel(close = false)
            }
            ScannerIpcCodes.SHUTDOWN -> if (matchesOwner(message.data)) terminateOwnedProcess()
        }
    }

    private fun matchesOwner(data: Bundle): Boolean = owner?.requestId == data.getString("request_id")

    private fun begin(message: Message) {
        val data = message.data
        val descriptors = BundleCompat.getParcelableArrayList(data, "descriptors", ParcelFileDescriptor::class.java)
        val source = BundleCompat.getParcelable(data, "yara_source", ParcelFileDescriptor::class.java)
        val allDescriptors = descriptors.orEmpty() + listOfNotNull(source)
        if (worker != null || finished) {
            closeDescriptors(allDescriptors)
            return
        }
        try {
            val expectedKeys = if (data.containsKey("yara_source")) REQUEST_KEYS + YARA_KEYS else REQUEST_KEYS
            val allowedKeys = if (data.containsKey(
                    "max_artifact_bytes"
                )
            ) {
                expectedKeys + "max_artifact_bytes"
            } else {
                expectedKeys
            }
            require(data.keySet() == allowedKeys && data.getInt("protocol", -1) == ScannerIpcCodes.PROTOCOL_VERSION)
            val maxArtifactBytes = data.getInt("max_artifact_bytes", 16 * 1024 * 1024)
            requireScannerArtifactBudget(maxArtifactBytes)
            val claimedOwner = ScannerRequestOwner(
                requireNotNull(data.getString("request_id")),
                requireNotNull(data.getString("installation_id")),
                requireNotNull(data.getString("rule_epoch")),
            )
            val labels = requireNotNull(data.getStringArrayList("labels"))
            val kinds = requireNotNull(data.getIntArray("kinds"))
            val sizes = requireNotNull(data.getLongArray("sizes"))
            require(labels.size in 1..32 && kinds.size == labels.size && sizes.size == labels.size)
            val claims = labels.mapIndexed { index, label ->
                ScannerDescriptorClaim(label, ScannerArtifactKind.entries[kinds[index]], sizes[index])
            }
            validateScannerDescriptorClaims(claims)
            require(descriptors != null && descriptors.size == claims.size)
            owner = claimedOwner
            yaraRequested = source != null
            reply = requireNotNull(message.replyTo)
            handler.postDelayed(watchdog, ScannerIpcCodes.OWNER_WATCHDOG_MILLIS)
            require(!data.containsKey("yara_source") || source != null)
            val sourceBytes = data.getInt("yara_source_bytes", 0)
            val sourceSha256 = data.getString("yara_source_sha256")
            worker = scope.launch { scan(claimedOwner, claims, descriptors, source, sourceBytes, sourceSha256, maxArtifactBytes) }
        } catch (cancelled: CancellationException) {
            closeDescriptors(allDescriptors)
            throw cancelled
        } catch (fatal: Error) {
            closeDescriptors(allDescriptors)
            throw fatal
        } catch (_: Exception) {
            closeDescriptors(allDescriptors)
            terminateOwnedProcess()
        }
    }

    private fun scan(
        captured: ScannerRequestOwner,
        claims: List<ScannerDescriptorClaim>,
        descriptors: List<ParcelFileDescriptor>,
        source: ParcelFileDescriptor?,
        sourceBytes: Int,
        sourceSha256: String?,
        maxArtifactBytes: Int,
    ) {
        val response = try {
            evaluateIsolatedInputs(captured, claims, descriptors, source, sourceBytes, sourceSha256, maxArtifactBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unavailable: ScannerYaraCompileUnavailable) {
            scannerUnavailableReply(captured, "INPUT_UNAVAILABLE").also {
                it.putBoolean("yara_requested", true)
                it.putStringArrayList("yara_statuses", arrayListOf(unavailable.status.name))
                it.putStringArrayList("yara_reasons", arrayListOf(unavailable.reason.name))
            }
        } catch (_: Exception) {
            scannerUnavailableReply(
                captured,
                "INPUT_UNAVAILABLE"
            ).also { it.putBoolean("yara_requested", source != null) }
        } finally {
            closeDescriptors(descriptors + listOfNotNull(source))
        }
        handler.post {
            finished = true
            handler.removeCallbacks(watchdog)
            publish(response)
            handler.postDelayed(::terminateOwnedProcess, TERMINAL_ACK_TIMEOUT_MILLIS)
        }
    }

    private fun evaluateIsolatedInputs(
        captured: ScannerRequestOwner,
        claims: List<ScannerDescriptorClaim>,
        descriptors: List<ParcelFileDescriptor>,
        source: ParcelFileDescriptor?,
        sourceBytes: Int,
        sourceSha256: String?,
        maxArtifactBytes: Int,
    ): Bundle {
        ensureScannerCurrent()
        val yara = source?.let {
            IsolatedYaraInspection(
                readOwnedScannerYaraSource(it, sourceBytes, requireNotNull(sourceSha256))
            )
        }
        activeYara.set(yara)
        try {
            val callback = yara?.let { engine ->
                {
                        bytes: ByteArray ->
                    ensureScannerCurrent()
                    engine.inspectVerifiedDex(
                        bytes
                    )
                }
            }
            val inspection = inspectIsolatedApkDescriptors(
                claims,
                descriptors,
                ScanCancellation { cancelled.get() },
                callback,
                maxArtifactBytes,
            )
            if (cancelled.get()) {
                return scannerUnavailableReply(
                    captured,
                    "CANCELLED"
                ).also { it.putBoolean("yara_requested", yara != null) }
            }
            val response = scannerInspectionReply(captured, inspection)
            if (yara != null) {
                addScannerYaraReply(
                    response,
                    inspection.coverage.complete,
                    inspection.dexEntries.size,
                    yara.snapshot()
                )
            }
            return response
        } finally {
            activeYara.set(null)
            yara?.cancel(close = true)
        }
    }

    private fun ensureScannerCurrent() {
        if (cancelled.get()) throw CancellationException("Scanner request cancelled")
    }

    private fun publish(data: Bundle) {
        data.putInt("pid", Process.myPid())
        data.putInt("uid", Process.myUid())
        try {
            reply?.send(Message.obtain(null, ScannerIpcCodes.RESULT).apply { this.data = data })
        } catch (_: RemoteException) {
            terminateOwnedProcess()
        }
    }

    private fun terminateOwnedProcess() {
        if (!isStandardIsolatedScannerUid(Process.myUid())) return
        cancelled.set(true)
        scope.cancel()
        Process.killProcess(Process.myPid())
    }

    private companion object {
        const val TERMINAL_ACK_TIMEOUT_MILLIS = 5000L
        val YARA_KEYS = setOf("yara_source", "yara_source_bytes", "yara_source_sha256")
        val REQUEST_KEYS = setOf(
            "protocol",
            "request_id",
            "installation_id",
            "rule_epoch",
            "labels",
            "kinds",
            "sizes",
            "descriptors"
        )
    }
}

private fun closeDescriptors(descriptors: List<ParcelFileDescriptor>?) {
    descriptors.orEmpty().forEach { descriptor ->
        try { descriptor.close() } catch (_: java.io.IOException) { }
    }
}
