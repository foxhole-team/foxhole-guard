package com.foxhole.guard.core.webapps

import com.foxhole.guard.core.security.LockState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class WebAppOpenCommandRequestsTest {
    @Test
    fun `capability rejects missing forged and retargeted tokens before accepting once`() {
        val requests = WebAppOpenCommandRequests(now = { 1_000L })
        val command = requests.issue(41L)

        assertNull(requests.consume(null, 41L))
        assertNull(requests.consume("forged", 41L))
        assertNull(requests.consume(command.token, 42L))
        assertNotNull(requests.consume(command.token, 41L))
        assertNull(requests.consume(command.token, 41L))
    }

    @Test
    fun `expired and clock rolled back requests fail closed`() {
        var now = 1_000L
        val requests = WebAppOpenCommandRequests(now = { now })
        val command = requests.issue(41L)
        now = 999L
        assertNull(requests.consume(command.token, 41L))
        now = 1_000L
        assertNull(requests.consume(command.token, 41L))
        val expiring = requests.issue(41L)
        now = 1_000L + WebAppOpenCommand.EXPIRY_MS
        assertNull(requests.consume(expiring.token, 41L))
    }

    @Test
    fun `a new process cannot consume a previous process capability`() {
        val previousProcess = WebAppOpenCommandRequests(now = { 1_000L })
        val command = previousProcess.issue(41L)
        val newProcess = WebAppOpenCommandRequests(now = { 1_001L })

        assertNull(newProcess.consume(command.token, 41L))
    }

    @Test
    fun `latest trusted tap supersedes a pending launch and each new tap works once`() {
        val requests = WebAppOpenCommandRequests(now = { 1_000L })
        val old = requests.issue(41L)
        val latest = requests.issue(42L)
        assertNull(requests.consume(old.token, 41L))
        assertNotNull(requests.consume(latest.token, 42L))
        val repeatedTap = requests.issue(42L)
        assertNotNull(requests.consume(repeatedTap.token, 42L))
        assertNull(requests.consume(repeatedTap.token, 42L))
    }

    @Test
    fun `a trusted request waits for unlock before it can open a web app`() = runBlocking {
        val command = WebAppOpenCommand("trusted", 41L, 1_000L)
        val lock = MutableStateFlow(LockState.LOCKED_PASSWORD)
        var opened = false
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            if (awaitWebAppCommandUnlock(command, lock, { 1_000L }, { true })) {
                opened = true
            }
        }

        assertFalse(opened)
        assertFalse(pending.isCompleted)
        lock.value = LockState.UNLOCKED
        pending.await()
        assertTrue(opened)
    }

    @Test
    fun `unlocking after expiry cannot open a web app`() = runBlocking {
        var now = 1_000L
        val command = WebAppOpenCommand("trusted", 41L, now)
        val lock = MutableStateFlow(LockState.LOCKED_SYSTEM)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            awaitWebAppCommandUnlock(command, lock, { now }, { true })
        }
        assertFalse(pending.isCompleted)
        now += WebAppOpenCommand.EXPIRY_MS
        lock.value = LockState.UNLOCKED

        assertFalse(pending.await())
    }

    @Test
    fun `waiting for unlock does not renew a nearly expired capability`() = runBlocking {
        val command = WebAppOpenCommand("trusted", 41L, 0L)
        val age = WebAppOpenCommand.EXPIRY_MS - 1L
        val lock = MutableStateFlow(LockState.LOCKED_PASSWORD)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            awaitWebAppCommandUnlock(command, lock, { age }, { true })
        }
        val accepted = pending.await()
        lock.value = LockState.UNLOCKED

        assertFalse(accepted)
    }

    @Test
    fun `a dismissed or superseded request cannot open after unlock`() = runBlocking {
        val command = WebAppOpenCommand("trusted", 41L, 1_000L)
        val requests = WebAppOpenRequests()
        val request = requests.next()
        val lock = MutableStateFlow(LockState.LOCKED_PASSWORD)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            awaitWebAppCommandUnlock(command, lock, { 1_000L }, { requests.owns(request) })
        }
        assertFalse(pending.isCompleted)
        requests.next()
        lock.value = LockState.UNLOCKED

        assertFalse(pending.await())
    }
}
