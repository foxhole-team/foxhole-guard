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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.core.sentinel.apk.InspectionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ScannerReadonlyFdAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun nativeValidatorAcceptsReadonlyInputThroughRealChild() = runBlocking {
        withOwnedInputs { apk, _ ->
            val result = rawInspect(apk, ParcelFileDescriptor.MODE_READ_ONLY, null)
            assertEquals(InspectionStatus.COMPLETE, result.status)
        }
    }

    @Test fun writableApkDescriptorCannotBecomeCompleteCoverage() = runBlocking {
        withOwnedInputs { apk, _ ->
            val result = rawInspect(apk, ParcelFileDescriptor.MODE_READ_WRITE, null)
            assertEquals(InspectionStatus.UNAVAILABLE, result.status)
            assertFalse(result.coverageComplete)
        }
    }

    @Test fun writableRuleSourceCannotCompileOrClaimYaraCoverage() = runBlocking {
        withOwnedInputs { apk, rules ->
            val result = rawInspect(apk, ParcelFileDescriptor.MODE_READ_ONLY, rules)
            assertEquals(InspectionStatus.UNAVAILABLE, result.status)
            assertFalse(result.coverageComplete)
            assertFalse(result.yara?.verifiedDexComplete == true)
        }
    }

    private suspend fun withOwnedInputs(operation: suspend (File, File) -> Unit) {
        val directory = File(context.cacheDir, "owned-fd-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val apk = File(directory, "owned.apk")
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                instrumentation.context.assets.open("owned-scanner/classes.dex").use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val rules = File(directory, "owned.yar").apply { writeText("rule owned { condition: true }") }
            operation(apk, rules)
        } finally { directory.deleteRecursively() }
    }

    private suspend fun rawInspect(apk: File, mode: Int, rules: File?): ScannerStaticResult {
        val owner = ScannerRequestOwner(UUID.randomUUID().toString(), "owned-fd:1", "owned-rule:1")
        val connected = CompletableDeferred<Messenger>()
        val response = CompletableDeferred<ScannerStaticResult>()
        val replyHandler = Handler(Looper.getMainLooper()) { message ->
            try {
                response.complete(decodeScannerStaticReply(message.data, message.sendingUid, owner))
            } catch (failure: Throwable) {
                response.completeExceptionally(failure)
            }
            true
        }
        val replies = Messenger(replyHandler)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder
            ) {
                connected.complete(Messenger(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) {
                response.completeExceptionally(IllegalStateException("owned child disconnected"))
            }
        }
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        var bound = false
        var remote: Messenger? = null
        try {
            descriptors += ParcelFileDescriptor.open(apk, mode)
            val request = Bundle().apply {
                putInt("protocol", ScannerIpcCodes.PROTOCOL_VERSION)
                putString("request_id", owner.requestId)
                putString("installation_id", owner.installationId)
                putString("rule_epoch", owner.ruleEpoch)
                putStringArrayList("labels", arrayListOf("base"))
                putIntArray("kinds", intArrayOf(ScannerArtifactKind.BASE.ordinal))
                putLongArray("sizes", longArrayOf(apk.length()))
                putParcelableArrayList("descriptors", ArrayList(descriptors))
            }
            if (rules != null) {
                val descriptor = ParcelFileDescriptor.open(rules, ParcelFileDescriptor.MODE_READ_WRITE)
                descriptors += descriptor
                request.putParcelable("yara_source", descriptor)
                request.putInt("yara_source_bytes", rules.length().toInt())
                val hash = MessageDigest.getInstance("SHA-256").digest(rules.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                request.putString("yara_source_sha256", hash)
            }
            bound = withContext(Dispatchers.Main) {
                context.bindService(Intent(context, IsolatedSentinelScannerService::class.java), connection, Context.BIND_AUTO_CREATE)
            }
            check(bound)
            remote = withTimeout(10000) { connected.await() }
            val scanRequest = Message.obtain(null, ScannerIpcCodes.SCAN).apply {
                data = request
                replyTo = replies
            }
            remote.send(scanRequest)
            return withTimeout(20000) { response.await() }
        } finally {
            withContext(NonCancellable) {
                try {
                    val shutdown = Message.obtain(null, ScannerIpcCodes.SHUTDOWN).apply {
                        data = Bundle().apply { putString("request_id", owner.requestId) }
                    }
                    remote?.send(shutdown)
                } catch (_: RemoteException) { }
                try {
                    withContext(Dispatchers.Main) { if (bound) context.unbindService(connection) }
                } finally {
                    descriptors.forEach(ParcelFileDescriptor::close)
                }
            }
        }
    }
}
