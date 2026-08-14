package com.foxhole.guard.ui.cli

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnboardingDnsDatasetSelectionContractTest {
    @Test
    fun `enabling DNS filtering auto-selects its FoxHole DB data set`() {
        val wizard =
            sequenceOf(
                File("src/main/kotlin/com/foxhole/guard/ui/cli/onboarding/CliOnboardingWizard.kt"),
                File("app/src/main/kotlin/com/foxhole/guard/ui/cli/onboarding/CliOnboardingWizard.kt"),
            ).first(File::isFile).readText()
        val dnsChoice =
            wizard
                .substringAfter("onDnsFilterChange = { enabled ->")
                .substringBefore("},\n            )")

        assertTrue(dnsChoice.contains("dnsFilterEnabled = enabled"))
        assertTrue(dnsChoice.contains("dnsDownload = if (enabled) true else choices.dnsDownload"))
    }
}
