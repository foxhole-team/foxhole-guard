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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ScannerAcknowledgementTimeoutAndroidTest {
    @Test
    fun unacknowledgedResultCannotKeepAnIsolatedChildAlive() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "scanner-ack-${UUID.randomUUID()}").apply { mkdirs() }
        val apk = File(directory, "owned.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            instrumentation.context.assets.open("owned-scanner/classes.dex").use { it.copyTo(zip) }
            zip.closeEntry()
        }
        val connected = CompletableDeferred<IBinder>()
        val died = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Bundle>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                binder.linkToDeath({ died.complete(Unit) }, 0)
                connected.complete(binder)
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        var bound = false
        try {
            withContext(Dispatchers.Main) {
                bound = context.bindService(
                    Intent(context, IsolatedSentinelScannerService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            }
            assertTrue(bound)
            val binder = withTimeout(10_000) { connected.await() }
            ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val request = Bundle().apply {
                    putInt("protocol", ScannerIpcCodes.PROTOCOL_VERSION)
                    putString("request_id", UUID.randomUUID().toString())
                    putString("installation_id", "owned-fixture:1")
                    putString("rule_epoch", "dex:1")
                    putStringArrayList("labels", arrayListOf("base"))
                    putIntArray("kinds", intArrayOf(ScannerArtifactKind.BASE.ordinal))
                    putLongArray("sizes", longArrayOf(apk.length()))
                    putParcelableArrayList("descriptors", arrayListOf(descriptor))
                }
                Messenger(binder).send(
                    Message.obtain(null, ScannerIpcCodes.SCAN).apply {
                        data = request
                        replyTo = Messenger(
                            Handler(Looper.getMainLooper()) { message ->
                                if (message.what == ScannerIpcCodes.RESULT) reply.complete(message.data)
                                true
                            }
                        )
                    }
                )
                val result = withTimeout(ScannerIpcCodes.OWNER_WATCHDOG_MILLIS + 2000) { reply.await() }
                assertEquals(InspectionStatus.COMPLETE.name, result.getString("status"))
                assertTrue(isStandardIsolatedScannerUid(result.getInt("uid")))
                withTimeout(10_000) { died.await() }
                assertFalse(binder.isBinderAlive)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                if (bound) context.unbindService(connection)
            }
            directory.deleteRecursively()
        }
    }
}
