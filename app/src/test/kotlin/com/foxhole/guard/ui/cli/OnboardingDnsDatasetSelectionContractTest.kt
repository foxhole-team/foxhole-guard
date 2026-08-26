package com.foxhole.guard.ui.cli

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnboardingDnsDatasetSelectionContractTest {
    @Test
    fun `first run hides DNS and Sentinel enable switches but keeps data downloads`() {
        val wizard =
            sequenceOf(
                File("src/main/kotlin/com/foxhole/guard/ui/cli/onboarding/CliOnboardingWizard.kt"),
                File("app/src/main/kotlin/com/foxhole/guard/ui/cli/onboarding/CliOnboardingWizard.kt"),
            ).first(File::isFile).readText()
        val components =
            wizard
                .substringAfter("private fun WizardComponentsStep")
                .substringBefore("private fun WizardAppearancePanel")

        assertTrue(components.contains("cli_wizard_component_tor"))
        assertTrue(components.contains("cli_wizard_component_i2p"))
        assertTrue(!components.contains("cli_wizard_component_dns"))
        assertTrue(!components.contains("cli_cfg_more_anomaly"))
        assertTrue(wizard.contains("label = stringResource(R.string.cli_wizard_download_dns)"))
        assertTrue(wizard.contains("label = stringResource(R.string.cli_wizard_download_sentinel)"))
        assertTrue(wizard.contains("OnboardingDownload.DNS_FILTER"))
        assertTrue(wizard.contains("OnboardingDownload.THREAT_INTEL"))
    }
}
