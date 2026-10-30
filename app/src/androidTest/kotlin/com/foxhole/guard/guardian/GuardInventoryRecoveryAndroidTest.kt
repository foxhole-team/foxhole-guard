package com.foxhole.guard.guardian

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.core.sentinel.AndroidKeystoreFileCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GuardInventoryRecoveryAndroidTest {
    @Test fun encryptedRecoveryAndResetPreserveOnlyTheirOwnCommittedData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "owned-inventory-${UUID.randomUUID()}")
        check(directory.mkdir())
        val alias = "owned-inventory-${UUID.randomUUID()}"
        try {
            val cipher = AndroidKeystoreFileCipher(alias)
            val file = File(directory, "inventory.bin")
            val store = GuardInventorySnapshotStore(file, cipher)
            val prior = GuardInventorySnapshot(lastAuthoritativeFullRepairAt = 1000)
            store.write(prior)
            val encrypted = file.readBytes()
            assertFalse(encrypted.decodeToString().contains("lastAuthoritativeFullRepairAt"))
            store.write(prior.copy(lastAuthoritativeFullRepairAt = 1001))
            val stage = File(directory, ".inventory.bin.stage-${UUID.randomUUID()}")
            check(stage.mkdir())
            File(stage, "inventory.bin.previous").writeBytes(encrypted)
            File(stage, "rollback").writeText("present")
            assertEquals(prior, GuardInventorySnapshotStore(file, cipher).read())
            assertEquals(prior, GuardInventorySnapshotStore(file, cipher).read())
            assertTrue(encrypted.contentEquals(file.readBytes()))
            assertFalse(stage.exists())
            val resetStage = File(directory, ".inventory.bin.stage-${UUID.randomUUID()}")
            check(resetStage.mkdir())
            File(resetStage, "inventory.bin.previous").writeBytes(encrypted)
            File(resetStage, "rollback").writeText("present")
            store.delete()
            assertEquals(GuardInventorySnapshot(), GuardInventorySnapshotStore(file, cipher).read())
            assertFalse(resetStage.exists())
            assertFalse(file.exists())
        } finally {
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }
}
