package com.foxhole.guard.ui.cli.settings

import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.guard.TOR_BRIDGE_UPDATE_INTERVAL_HOURS
import com.foxhole.guard.runtime.RemoteDownloadProgress
import com.foxhole.guard.ui.FoxholeUpdatePhase
import com.foxhole.guard.ui.torBridgePersistenceMarker
import com.foxhole.guard.ui.torBridgeRefreshRequired
import com.foxhole.guard.ui.torBridgeVerifiedSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class CliTorBridgeAndDnsPromptTest {
    @Test
    fun `FoxHole DB progress uses one inline action and progress contract`() {
        assertEquals(1, FoxholeUpdatePhase.CHECKING.foxholeUpdateStage())
        assertEquals(2, FoxholeUpdatePhase.DOWNLOADING.foxholeUpdateStage())
        assertEquals(3, FoxholeUpdatePhase.VERIFYING.foxholeUpdateStage())
        assertEquals(4, FoxholeUpdatePhase.DONE.foxholeUpdateStage())
        assertEquals(null, FoxholeUpdatePhase.FAILED.foxholeUpdateStage())
        assertEquals(
            0.25f,
            verifiedUpdateProgressFraction(FoxholeUpdatePhase.CHECKING, null, verifiedSuccess = false),
            0f,
        )
        assertEquals(
            0.5f,
            verifiedUpdateProgressFraction(
                FoxholeUpdatePhase.DOWNLOADING,
                RemoteDownloadProgress(downloadedBytes = 1L, totalBytes = 2L),
                verifiedSuccess = false,
            ),
            0f,
        )
        assertEquals(
            1f,
            verifiedUpdateProgressFraction(FoxholeUpdatePhase.DONE, null, verifiedSuccess = true),
            0f,
        )

        val wizard = source("src/main/kotlin/com/foxhole/guard/ui/cli/onboarding/CliOnboardingWizard.kt")
        val dns = source("src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliSettingsDnsSection.kt")
        val updates = source("src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliUpdatesSubScreen.kt")
        val sheet = source("src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliFoxholeDbUpdateSheet.kt")
        val dataset = source("src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliDatasetActivationSheet.kt")
        val progress = source("src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliFoxholeUpdateProgress.kt")
        val pixel = source("src/main/kotlin/com/foxhole/guard/ui/cli/components/CliStageProgress.kt")

        assertTrue(wizard.contains("color = if (verified) colors.ok else colors.accent"))
        assertFalse(dns.contains("CliFoxholeUpdateProgress("))
        assertTrue(dns.contains("CliVerifiedUpdateProgress("))
        assertFalse(updates.contains("CliFoxholeUpdateProgress(phase = phase)"))
        assertTrue(updates.contains("foxholeRefreshButtonLabel"))
        assertTrue(updates.contains("animatedLabel = refreshRunning"))
        assertTrue(updates.contains("R.string.cli_updates_progress"))
        assertTrue(sheet.contains("CliFoxholeUpdateProgress(phase = phase)"))
        assertTrue(dataset.contains("CliVerifiedUpdateProgress("))
        assertTrue(progress.contains("CliPixelProgressBar("))
        assertTrue(progress.contains("verifiedUpdateProgressFraction("))
        assertTrue(pixel.contains("fillMaxWidth(safeCompleted.toFloat() / safeTotal)"))
        assertTrue(pixel.contains("CliShimmerText("))
        assertFalse(wizard.contains("LinearProgressIndicator"))
        assertFalse(pixel.contains("LinearProgressIndicator"))
    }

    @Test
    fun `bridge action follows Tor eligibility and the canonical refresh interval`() {
        val intervalMs = TimeUnit.HOURS.toMillis(TOR_BRIDGE_UPDATE_INTERVAL_HOURS)
        val fresh = enabledBridges(
            bridgesCheckedAt = NOW_MS - intervalMs + 1L,
            bridgesLastUpdateSuccess = true,
        )

        assertFalse(torBridgeRefreshRequired(fresh, NOW_MS))
        assertFalse(torBridgeRefreshActionVisible(fresh, FoxholeUpdatePhase.IDLE, NOW_MS))
        assertTrue(
            torBridgeRefreshRequired(
                fresh.copy(bridgesCheckedAt = NOW_MS - intervalMs),
                NOW_MS,
            ),
        )
        assertTrue(torBridgeRefreshRequired(fresh.copy(bridgesCheckedAt = null), NOW_MS))
        assertTrue(torBridgeRefreshRequired(fresh.copy(bridgesLastUpdateSuccess = false), NOW_MS))
        assertFalse(torBridgeRefreshRequired(fresh.copy(permitted = false), NOW_MS))
        assertFalse(torBridgeRefreshRequired(fresh.copy(bridgesEnabled = false), NOW_MS))

        assertTrue(torBridgeRefreshActionVisible(fresh, FoxholeUpdatePhase.CHECKING, NOW_MS))
        assertFalse(
            torBridgeRefreshActionVisible(
                fresh.copy(permitted = false),
                FoxholeUpdatePhase.CHECKING,
                NOW_MS,
            ),
        )
    }

    @Test
    fun `bridge success is never shown before verified state is persisted`() {
        val checked = enabledBridges(
            bridgesCheckedAt = NOW_MS,
            bridgesLastUpdateSuccess = true,
        )
        val baseline = checked.torBridgePersistenceMarker()

        assertFalse(torBridgeVerifiedSuccess(FoxholeUpdatePhase.DONE, checked, baseline))
        assertTrue(
            torBridgeVerifiedSuccess(
                FoxholeUpdatePhase.DONE,
                checked.copy(bridgesUpdatedAt = NOW_MS + 1L),
                baseline,
            ),
        )
        assertTrue(
            torBridgeVerifiedSuccess(
                FoxholeUpdatePhase.NO_UPDATE,
                checked.copy(bridgesCheckedAt = NOW_MS + 1L),
                baseline,
            ),
        )
        assertFalse(
            torBridgeVerifiedSuccess(
                FoxholeUpdatePhase.NO_UPDATE,
                checked.copy(
                    bridgesCheckedAt = NOW_MS + 1L,
                    bridgesLastUpdateSuccess = false,
                ),
                baseline,
            ),
        )
        assertFalse(
            torBridgeVerifiedSuccess(
                FoxholeUpdatePhase.FAILED,
                checked.copy(bridgesCheckedAt = NOW_MS + 1L),
                baseline,
            ),
        )
    }

    @Test
    fun `bridge progress is discrete and reaches four only after verified persistence`() {
        assertEquals(1, torBridgeStageProgress(FoxholeUpdatePhase.CHECKING, verifiedSuccess = false))
        assertEquals(
            2,
            torBridgeStageProgress(FoxholeUpdatePhase.DOWNLOADING, verifiedSuccess = false),
        )
        assertEquals(
            3,
            torBridgeStageProgress(FoxholeUpdatePhase.VERIFYING, verifiedSuccess = false),
        )

        assertEquals(3, torBridgeStageProgress(FoxholeUpdatePhase.DONE, verifiedSuccess = false))
        assertEquals(3, torBridgeStageProgress(FoxholeUpdatePhase.NO_UPDATE, verifiedSuccess = false))
        assertEquals(4, torBridgeStageProgress(FoxholeUpdatePhase.DONE, verifiedSuccess = true))
        assertEquals(4, torBridgeStageProgress(FoxholeUpdatePhase.NO_UPDATE, verifiedSuccess = true))
        assertEquals(1, torBridgeStageProgress(FoxholeUpdatePhase.CHECKING, verifiedSuccess = true))
        assertEquals(null, torBridgeStageProgress(FoxholeUpdatePhase.FAILED, verifiedSuccess = true))
    }

    @Test
    fun `DNS asks on every off to on edge including cancel and a second enable cycle`() {
        var enabled = false
        var promptCount = 0
        fun request(requestedEnabled: Boolean) {
            if (dnsFilteringEnableConfirmationRequired(enabled, requestedEnabled)) {
                promptCount++
            } else {
                enabled = requestedEnabled
            }
        }

        request(requestedEnabled = true)
        assertEquals(1, promptCount)
        assertFalse(enabled)
        request(requestedEnabled = true)
        assertEquals(2, promptCount)
        assertFalse(enabled)

        enabled = true
        request(requestedEnabled = false)
        assertFalse(enabled)
        assertEquals(2, promptCount)

        request(requestedEnabled = true)
        assertEquals(3, promptCount)
        assertFalse(enabled)
        assertFalse(dnsFilteringEnableConfirmationRequired(enabled, requestedEnabled = false))
    }

    @Test
    fun `Tor sheet reuses update state API and bridge copy has RU EN parity`() {
        val torScreen = source("src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliTorSubScreen.kt")
        val torSupport = source("src/main/kotlin/com/foxhole/guard/ui/HomeViewModelTorBridgeSupport.kt")
        val dnsScreen = source("src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliSettingsDnsSection.kt")
        val stageProgress = source("src/main/kotlin/com/foxhole/guard/ui/cli/components/CliStageProgress.kt")

        assertTrue(torScreen.contains("CliBottomSheet("))
        assertTrue(torScreen.contains("CliVerifiedUpdateProgress("))
        assertTrue(torScreen.contains("torBridgeDownloadProgress.collectAsStateWithLifecycle()"))
        assertTrue(torScreen.contains("viewModel.onTorBridgeManualRefresh()"))
        assertTrue(torScreen.contains("torBridgeVerifiedSuccess(phase, privacyRoute, baseline)"))
        assertFalse(torScreen.contains("CliFoxholeDbUpdateSheet("))
        assertFalse(torScreen.contains("LinearProgressIndicator"))
        assertTrue(stageProgress.contains("fillMaxWidth(safeCompleted.toFloat() / safeTotal)"))
        assertTrue(stageProgress.contains("\"\$stageLabel · \$safeCompleted/\$safeTotal\""))
        assertTrue(torSupport.contains("TOR_BRIDGE_UPDATE_INTERVAL_HOURS"))
        assertTrue(torSupport.contains("terminalPhase == FoxholeUpdatePhase.FAILED"))
        assertFalse(dnsScreen.contains("rememberSaveable(dns.filteringEnabled)"))
        assertTrue(dnsScreen.contains("rememberSaveable { mutableStateOf(false) }"))
        assertTrue(dnsScreen.contains("downloadProgress = refreshProgress"))
        assertTrue(
            dnsScreen.contains(
                "autoDismissAfterMillis = DNS_FILTER_SUCCESS_AUTO_DISMISS_MS.takeIf { verifiedSuccess }",
            ),
        )
        assertTrue(dnsScreen.contains("viewModel.onDnsFilterEnablePreflightCancelled()"))
        assertFalse(dnsScreen.contains("AnimatedContent("))
        assertFalse(dnsScreen.contains("CliFoxholeUpdateProgress("))

        val keys = listOf(
            "cli_tor_bridges_update_title",
            "cli_tor_bridges_update_body",
            "cli_tor_bridges_update_run",
            "cli_tor_bridges_update_retry",
            "cli_tor_bridges_update_failed",
        )
        val english = source("src/main/res/values/strings.xml")
        val russian = source("src/main/res/values-ru/strings.xml")
        keys.forEach { key ->
            assertEquals(1, Regex("""<string name="$key">.+</string>""").findAll(english).count())
            assertEquals(1, Regex("""<string name="$key">.+</string>""").findAll(russian).count())
        }
    }

    private fun enabledBridges(
        bridgesCheckedAt: Long? = null,
        bridgesLastUpdateSuccess: Boolean? = null,
    ): PrivacyRouteSettings =
        PrivacyRouteSettings(
            permitted = true,
            bridgesEnabled = true,
            bridgesCheckedAt = bridgesCheckedAt,
            bridgesLastUpdateSuccess = bridgesLastUpdateSuccess,
        )

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"), File("../app/$relative"))
            .first(File::isFile)
            .readText()

    private companion object {
        const val NOW_MS = 200L * 60L * 60L * 1000L
    }
}
