package com.foxhole.guard.guardian

import android.content.Context
import android.content.ContextWrapper
import com.foxhole.guard.core.sentinel.FileCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GuardPackageBatchCostTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `two admitted removal edges do not each enumerate full installed inventory`() = runBlocking {
        val root = temporary.newFolder()
        val store = GuardInventorySnapshotStore(File(root, "inventory.bin"), OwnedCipher)
        val app = GuardInventoryApp("owned.removed", versionCode = 1)
        store.write(GuardInventorySnapshot(10, listOf(app)))
        var scans = 0
        val sentinel = GuardSentinel(
            context = OwnedContext(),
            clock = OwnedClock,
            inventoryStore = store,
            journal = { true },
            isActive = { true },
            lastJournalRecordAt = { null },
            packageWakeForTest = {},
            inventorySnapshotForTest = {
                scans++
                Result.success(listOf(app))
            },
        )
        try {
            sentinel.recordPackageChange(GuardEventType.PACKAGE_REMOVED, app.packageName)
            sentinel.recordPackageChange(GuardEventType.PACKAGE_REMOVED, app.packageName)
            assertEquals(0, scans)
        } finally { sentinel.clearHosts() }
    }

    private class OwnedContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
    private object OwnedClock : GuardClock {
        override fun wallClockMs(): Long = 100
        override fun elapsedRealtimeMs(): Long = 100
        override fun bootCount(): Int = 1
    }
    private object OwnedCipher : FileCipher {
        override fun readBytes(file: File): ByteArray = file.readBytes()
        override fun writeBytesAtomic(file: File, plaintext: ByteArray) { file.writeBytes(plaintext) }
    }
}
