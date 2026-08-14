package com.foxhole.guard.core.security

import com.foxhole.core.model.AppLockMode
import com.foxhole.core.model.AppLockTimeout
import com.foxhole.guard.guardian.GuardEvent
import com.foxhole.guard.guardian.GuardEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppLockManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var keybox: SecureKeybox
    private lateinit var session: SecureSessionHolder
    private val journaled = mutableListOf<GuardEvent>()
    private var mode = AppLockMode.PASSWORD
    private var timeout = AppLockTimeout.MIN_15
    private var elapsed = 0L

    private val password = "hunter2hunter2".encodeToByteArray()
    private val dataKey = ByteArray(SecureKeybox.DATA_KEY_BYTES) { (it + 3).toByte() }
    private val kdf = GuardKdfParams(memKib = 1024, ops = 1)

    @Before
    fun setUp() {
        val root = temporaryFolder.newFolder("secure")
        keybox =
            SecureKeybox(
                keyboxFile = File(root, "keybox.bin"),
                attemptsFile = File(root, "attempts.bin"),
                attemptsCipher = FakeFileCipher(),
                keyboxCipher = FakeFileCipher(),
                crypto = FakeGuardCrypto(),
            )
        session = SecureSessionHolder()
    }

    private fun manager() =
        AppLockManager(
            keybox = keybox,
            session = session,
            appLockMode = { mode },
            lockTimeout = { timeout },
            journal = { journaled += it },
            elapsedRealtime = { elapsed },
        )

    @Test
    fun `password mode with a keybox starts locked`() {
        keybox.create(password, dataKey, kdf).destroy()

        assertEquals(LockState.LOCKED_PASSWORD, manager().currentLockState())
    }

    @Test
    fun `off mode starts unlocked`() {
        mode = AppLockMode.OFF
        assertEquals(LockState.UNLOCKED, manager().currentLockState())
    }

    @Test
    fun `system mode starts locked and unlocks without touching the keybox`() {
        mode = AppLockMode.SYSTEM
        val manager = manager()

        assertEquals(LockState.LOCKED_SYSTEM, manager.currentLockState())
        manager.unlockWithSystemAuth()
        assertEquals(LockState.UNLOCKED, manager.currentLockState())
        assertFalse(session.isUnlocked)
    }

    @Test
    fun `correct password unlocks and installs the data key`() {
        keybox.create(password, dataKey, kdf).destroy()
        val manager = manager()

        val outcome = manager.unlockWithPassword(password)

        assertTrue(outcome is UnlockOutcome.Success)
        assertEquals(LockState.UNLOCKED, manager.currentLockState())
        assertTrue(session.isUnlocked)
        assertTrue(journaled.any { it.type == GuardEventType.UNLOCK_OK })
    }

    @Test
    fun `wrong password journals a failure and stays locked`() {
        keybox.create(password, dataKey, kdf).destroy()
        val manager = manager()

        val outcome = manager.unlockWithPassword("nope".encodeToByteArray())

        assertTrue(outcome is UnlockOutcome.WrongPassword)
        assertEquals(LockState.LOCKED_PASSWORD, manager.currentLockState())
        assertFalse(session.isUnlocked)
        assertTrue(journaled.any { it.type == GuardEventType.UNLOCK_FAILED })
    }

    @Test
    fun `reset offer appears only after the tenth failed attempt`() {
        keybox.create(password, dataKey, kdf).destroy()
        val manager = manager()

        var lastResetOffered = false
        repeat(AppLockManager.RESET_OFFER_THRESHOLD) {
            val outcome = manager.unlockWithPassword("wrong".encodeToByteArray())
            lastResetOffered = (outcome as UnlockOutcome.WrongPassword).resetOffered
        }

        assertTrue(lastResetOffered)
    }

    @Test
    fun `staying backgrounded past the timeout re-locks on foreground`() {
        keybox.create(password, dataKey, kdf).destroy()
        val manager = manager()
        manager.unlockWithPassword(password)

        elapsed = 1_000_000L
        manager.onAppBackgrounded()
        elapsed += 15L * 60_000L + 1
        manager.onAppForegrounded()

        assertEquals(LockState.LOCKED_PASSWORD, manager.currentLockState())
        assertFalse(session.isUnlocked)
    }

    @Test
    fun `short background time keeps the app unlocked`() {
        keybox.create(password, dataKey, kdf).destroy()
        val manager = manager()
        manager.unlockWithPassword(password)

        elapsed = 1_000_000L
        manager.onAppBackgrounded()
        elapsed += 15L * 60_000L - 1
        manager.onAppForegrounded()

        assertEquals(LockState.UNLOCKED, manager.currentLockState())
        assertTrue(session.isUnlocked)
    }

    @Test
    fun `timeout boundary respects the configured minutes`() {
        keybox.create(password, dataKey, kdf).destroy()
        timeout = AppLockTimeout.HOUR_1
        val manager = manager()
        manager.unlockWithPassword(password)

        elapsed = 5_000_000L
        manager.onAppBackgrounded()
        elapsed += 30L * 60_000L
        manager.onAppForegrounded()

        // 30 min away with a 60-min timeout must NOT re-lock.
        assertEquals(LockState.UNLOCKED, manager.currentLockState())
    }

    @Test
    fun `after-reboot timeout never relocks a live process`() {
        keybox.create(password, dataKey, kdf).destroy()
        timeout = AppLockTimeout.AFTER_REBOOT
        val manager = manager()
        manager.unlockWithPassword(password)

        elapsed = 5_000_000L
        manager.onAppBackgrounded()
        elapsed += 14L * 24 * 60 * 60_000L
        manager.onAppForegrounded()

        assertEquals(LockState.UNLOCKED, manager.currentLockState())
        assertTrue(session.isUnlocked)
    }

    @Test
    fun `screen off zeroizes the in-memory keys and relocks`() {
        keybox.create(password, dataKey, kdf).destroy()
        val manager = manager()
        manager.unlockWithPassword(password)
        assertTrue(session.isUnlocked)

        manager.onScreenOff()

        assertEquals(LockState.LOCKED_PASSWORD, manager.currentLockState())
        assertFalse(session.isUnlocked)
    }

    @Test
    fun `screen off keeps the session under the after-reboot opt-out`() {
        keybox.create(password, dataKey, kdf).destroy()
        timeout = AppLockTimeout.AFTER_REBOOT
        val manager = manager()
        manager.unlockWithPassword(password)

        manager.onScreenOff()

        // The user asked to keep the session for the whole process life; screen-off must not wipe.
        assertEquals(LockState.UNLOCKED, manager.currentLockState())
        assertTrue(session.isUnlocked)
    }

    @Test
    fun `disabling protection zeroizes the manager and holder sessions immediately`() {
        keybox.create(password, dataKey, kdf).destroy()
        val manager = manager()
        manager.unlockWithPassword(password)
        assertTrue(session.isUnlocked)

        mode = AppLockMode.OFF
        manager.refreshFromSettings()

        assertEquals(LockState.UNLOCKED, manager.currentLockState())
        assertFalse(session.isUnlocked)
        assertEquals(null, manager.copyMasterKeyOrNull())
    }

    @Test
    fun `unlock after failures publishes the auth notice once`() {
        keybox.create(password, dataKey, kdf).destroy()
        val manager = manager()

        manager.unlockWithPassword("wrong".encodeToByteArray())
        manager.unlockWithPassword("wrong again".encodeToByteArray())
        assertEquals(null, manager.pendingAuthNotice.value)

        manager.unlockWithPassword(password)
        val notice = manager.pendingAuthNotice.value
        assertEquals(2, notice?.failedAttempts)

        manager.consumeAuthNotice()
        assertEquals(null, manager.pendingAuthNotice.value)
    }

    @Test
    fun `clean unlock publishes no auth notice`() {
        keybox.create(password, dataKey, kdf).destroy()
        val manager = manager()

        manager.unlockWithPassword(password)

        assertEquals(null, manager.pendingAuthNotice.value)
    }
}
