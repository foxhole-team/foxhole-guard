package com.foxhole.guard.runtime

import com.foxhole.core.model.ConnectionSettings
import com.foxhole.guard.ui.FoxholeUpdatePhase
import com.foxhole.guard.ui.tlsFingerprintDownloadFailed
import com.foxhole.guard.ui.tlsFingerprintTerminalPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TlsFingerprintUpdatePolicyContractTest {
    @Test
    fun `automatic fingerprint updates are on by default`() {
        assertTrue(ConnectionSettings().tlsFingerprintAutoUpdate)
    }

    @Test
    fun `an already current fingerprint publication stays successful in update flows`() {
        assertEquals(
            FoxholeUpdatePhase.NO_UPDATE,
            tlsFingerprintTerminalPhase(TlsFingerprintUpdateStatus.UP_TO_DATE),
        )
        assertFalse(tlsFingerprintDownloadFailed(TlsFingerprintUpdateStatus.UP_TO_DATE))
        assertTrue(tlsFingerprintDownloadFailed(TlsFingerprintUpdateStatus.FAILED))
        assertTrue(tlsFingerprintDownloadFailed(TlsFingerprintUpdateStatus.SKIPPED))
    }

    @Test
    fun `the wizard ticks the fingerprint tables on a fresh install`() {
        val source = source("app/src/main/kotlin/com/foxhole/guard/ui/cli/onboarding/CliOnboardingWizard.kt")

        assertTrue(
            "the wizard's fingerprint download must default to true",
            Regex("""val tlsFingerprintDownload: Boolean = true""").containsMatchIn(source),
        )
        assertTrue(
            "the fingerprint toggle must be on the data-sets step",
            source.contains("R.string.cli_wizard_download_tls"),
        )
        assertTrue(
            "the toggle must carry the short line saying why it matters",
            source.contains("R.string.cli_wizard_download_tls_note"),
        )
        assertTrue(
            "skipping downloads must clear the fingerprint choice with the others",
            Regex("""withoutDownloads[\s\S]{0,240}tlsFingerprintDownload = false""").containsMatchIn(source),
        )
    }

    @Test
    fun `the scheduled refresh is gated on the component-updates master switch`() {
        val application = source("app/src/main/kotlin/com/foxhole/guard/FoxholeApplication.kt")
        val viewModel = source("app/src/main/kotlin/com/foxhole/guard/ui/HomeViewModelComponentUpdatesSupport.kt")
        val gate = Regex("""applyTlsFingerprintUpdateSchedule\([\s\S]{0,200}?tlsFingerprintAutoUpdate""")

        listOf("FoxholeApplication.kt" to application, "HomeViewModelComponentUpdatesSupport.kt" to viewModel)
            .forEach { (name, text) ->
                val call = gate.find(text)?.value
                assertTrue("$name does not schedule the fingerprint refresh", call != null)
                assertTrue(
                    "$name schedules the fingerprint refresh without the master switch",
                    call.orEmpty().contains("componentUpdatesPermitted") || call.orEmpty().contains("permitted &&"),
                )
            }
    }

    @Test
    fun `the master auto switch mirrors into the fingerprint flag`() {
        val source = source("app/src/main/kotlin/com/foxhole/guard/core/settings/SettingsRepositoryComponentUpdates.kt")

        assertTrue(
            "updateComponentAutoUpdate must carry the fingerprint flag with the others",
            Regex("""updateComponentAutoUpdate[\s\S]{0,400}tlsFingerprintAutoUpdate = value""")
                .containsMatchIn(source),
        )
    }

    @Test
    fun `the worker re-checks the flag before spending network`() {
        val source = source("app/src/main/kotlin/com/foxhole/guard/runtime/TlsFingerprintUpdateWorker.kt")

        assertTrue(
            "the worker must re-read tlsFingerprintAutoUpdate, not trust the schedule alone",
            source.contains("tlsFingerprintAutoUpdate"),
        )
        assertEquals(
            "tls-fingerprint-update",
            Regex("""WORK_NAME = "([^"]+)"""").find(source)?.groupValues?.get(1),
        )
    }

    @Test
    fun `the settings entry sits in the panel the master switch governs`() {
        val source = source("app/src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliUpdatesSubScreen.kt")
        val panel =
            source
                .substringAfter("private fun CliFoxholeDbPanel")
                .substringBefore("private fun CliFoxholeDbGroupRow")

        assertTrue("the FoxHole DB panel must render a fingerprint row", panel.contains("R.string.cli_foxdb_group_tls"))
        assertTrue("the panel must carry the check master switch", panel.contains("componentUpdateCheckEnabled"))
        assertTrue("the panel must carry the auto master switch", panel.contains("componentAutoUpdateEnabled"))
        assertTrue(
            "the manual check must refresh every group at once",
            panel.contains("onFoxholeDbRefreshAll"),
        )
        assertTrue(
            "the manual refresh must include the fingerprint tables",
            source("app/src/main/kotlin/com/foxhole/guard/ui/HomeViewModelFoxholeDbSupport.kt")
                .contains("onTlsFingerprintManualRefresh()"),
        )
    }

    private fun source(path: String): String =
        sequenceOf(File(path), File(path.removePrefix("app/")))
            .first(File::isFile)
            .readText()
}
