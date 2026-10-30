package com.foxhole.guard

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InstalledAppChangeReceiverTest {
    @Test
    fun `pending broadcast finishes after successful work`() =
        runBlocking {
            var finished = 0
            finishPendingBroadcast(
                timeoutMs = 1_000L,
                finish = { finished += 1 },
            ) {
            }

            assertEquals(1, finished)
        }

    @Test
    fun `pending broadcast finishes after failed work`() =
        runBlocking {
            var finished = 0
            runCatching {
                finishPendingBroadcast(
                    timeoutMs = 1_000L,
                    finish = { finished += 1 },
                ) {
                    error("boom")
                }
            }

            assertEquals(1, finished)
        }

    @Test
    fun `pending broadcast finishes after timeout`() =
        runBlocking {
            var finished = 0
            var timedOut = 0
            finishPendingBroadcast(
                timeoutMs = 1L,
                finish = { finished += 1 },
                onTimeout = { timedOut += 1 },
            ) {
                delay(50L)
            }

            assertEquals(1, timedOut)
            assertEquals(1, finished)
        }

    @Test
    fun `quarantine is persisted and applied before optional enrichment`() {
        val source = receiverSource().readText()
        val receiveBody =
            source.substringAfter("finishPendingBroadcast(")
                .substringBefore("private companion object")
        val preparationBody =
            source.substringAfter("private suspend fun FoxholeApplication.preparePackageInventoryChange")
                .substringBefore("private suspend fun FoxholeApplication.preflightPackageChange")
        val preflightBody =
            source.substringAfter("private suspend fun FoxholeApplication.preflightPackageChange")
                .substringBefore("private suspend fun FoxholeApplication.enrichPackageInventoryChange")

        assertTrue(receiveBody.indexOf("preparePackageInventoryChange(it)") >= 0)
        assertTrue(receiveBody.indexOf("guardSentinel.enqueuePackageChange") >= 0)
        assertTrue(receiveBody.indexOf("enrichPackageInventoryChange(preparedChange)") >= 0)

        assertTrue(
            "The fail-closed package path must run before the sealed journal can consume the deadline",
            receiveBody.indexOf("preparePackageInventoryChange(it)") <
                receiveBody.indexOf("guardSentinel.enqueuePackageChange"),
        )
        assertTrue(
            "Durable Guard package admission must precede optional enrichment",
            receiveBody.indexOf("guardSentinel.enqueuePackageChange") <
                receiveBody.indexOf("enrichPackageInventoryChange(preparedChange)"),
        )
        assertTrue(
            "A failed durable preflight must stop the Guard snapshot from advancing",
            receiveBody.substringAfter("is PackageChangePreparation.Failed")
                .substringBefore("is PackageChangePreparation.Persisted")
                .contains("return@finishPendingBroadcast"),
        )
        assertTrue(preparationBody.contains("catch (error: CancellationException)"))
        assertTrue(preparationBody.contains("catch (error: Exception)"))
        assertTrue(!preparationBody.contains("catch (error: Throwable)"))
        assertTrue(!source.contains("runCatching"))
        assertTrue(preflightBody.contains("sentinelAttempt { applyQuarantineToActiveRuntime() }"))

        assertTrue(
            "Preparation must return only after the durable preflight",
            preparationBody.contains("preflight = preflightPackageChange(change, relevance)"),
        )
        assertTrue(
            "The BLOCK rule must be durable before the active runtime is reloaded",
            preflightBody.indexOf("recordInstalledAppChange(") <
                preflightBody.indexOf("applyQuarantineToActiveRuntime()"),
        )
    }

    @Test
    fun `install monitoring is part of the Sentinel master while quarantine stays independent`() {
        val source = receiverSource().readText()
        val relevance =
            source
                .substringAfter("private fun Settings.packageChangeRelevance")
                .substringBefore("private suspend fun FoxholeApplication.preparePackageInventoryChange")

        assertTrue(
            relevance.contains(
                "monitoringEnabled = anomaly.enabled && statistics.enabled && statistics.appChangesEnabled",
            ),
        )
        assertTrue(relevance.contains("val quarantineEnabled = expert.newAppQuarantineEnabled"))
    }

    @Test
    fun `work completed before an enrichment timeout stays completed`() =
        runBlocking {
            var persistedAndApplied = false
            finishPendingBroadcast(
                timeoutMs = 1L,
                finish = {},
            ) {
                persistedAndApplied = true
                delay(50L)
            }

            assertTrue(persistedAndApplied)
        }

    private fun receiverSource(): File =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/InstalledAppChangeReceiver.kt"),
            File("app/src/main/kotlin/com/foxhole/guard/InstalledAppChangeReceiver.kt"),
        ).first(File::isFile)
}
