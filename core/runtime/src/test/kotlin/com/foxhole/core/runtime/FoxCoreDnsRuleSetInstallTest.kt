package com.foxhole.core.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FoxCoreDnsRuleSetInstallTest {
    @Test
    fun `no running generation defers activation`() =
        runBlocking {
            var invoked = false
            val outcome =
                installDnsRuleSetGenerationSafe(
                    target = null,
                    install = {
                        invoked = true
                        1L
                    },
                    commit = { _, _ -> true },
                )

            assertEquals(RuntimeDnsRuleSetInstallOutcome.Deferred, outcome)
            assertFalse(invoked)
        }

    @Test
    fun `positive revision is committed to the captured generation`() =
        runBlocking {
            var committedRevision = 0L
            val target = DnsRuleSetInstallTarget(handle = 11L, generation = 3L)
            val outcome =
                installDnsRuleSetGenerationSafe(
                    target = target,
                    install = { handle ->
                        assertEquals(target.handle, handle)
                        9L
                    },
                    commit = { committedTarget, revision ->
                        assertEquals(target, committedTarget)
                        committedRevision = revision
                        true
                    },
                )

            assertEquals(RuntimeDnsRuleSetInstallOutcome.Installed(9L), outcome)
            assertEquals(9L, committedRevision)
        }

    @Test
    fun `native refusal and exception are rejected`() =
        runBlocking {
            val target = DnsRuleSetInstallTarget(handle = 11L, generation = 3L)
            assertEquals(
                RuntimeDnsRuleSetInstallOutcome.Rejected,
                installDnsRuleSetGenerationSafe(target, install = { 0L }, commit = { _, _ -> true }),
            )
            assertEquals(
                RuntimeDnsRuleSetInstallOutcome.Rejected,
                installDnsRuleSetGenerationSafe(
                    target,
                    install = { error("native refusal") },
                    commit = { _, _ -> true },
                ),
            )
        }

    @Test
    fun `generation replaced during native call is superseded`() =
        runBlocking {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            var generationCurrent = true
            val target = DnsRuleSetInstallTarget(handle = 11L, generation = 3L)
            val pending =
                async(Dispatchers.Default) {
                    installDnsRuleSetGenerationSafe(
                        target,
                        install = {
                            entered.countDown()
                            release.await()
                            12L
                        },
                        commit = { _, _ -> generationCurrent },
                    )
                }

            assertTrue(entered.await(1, TimeUnit.SECONDS))
            generationCurrent = false
            release.countDown()
            assertEquals(RuntimeDnsRuleSetInstallOutcome.Superseded, pending.await())
        }
}
