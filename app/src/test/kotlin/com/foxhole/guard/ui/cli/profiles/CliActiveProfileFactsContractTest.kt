package com.foxhole.guard.ui.cli.profiles

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliActiveProfileFactsContractTest {
    @Test
    fun `profile facts show protocol type while geography still uses the node name`() {
        val screen = source("profiles/CliProfilesScreen.kt")
        val facts = screen
            .substringAfter("private fun CliActiveProfileFactsPanel(")
            .substringBefore("private fun CliProfilesContent(")
        val profileFact = facts
            .substringAfter("key = stringResource(R.string.cli_prof_facts_profile)")
            .substringBefore("key = stringResource(R.string.cli_prof_facts_protocol)")
        val protocolFact = facts
            .substringAfter("key = stringResource(R.string.cli_prof_facts_protocol)")
            .substringBefore("profileCountryCode(")

        assertTrue(facts.contains("val activeOption = profile.protocolOptionOrDefault("))
        assertTrue(facts.contains("(activeOption?.protocolHint ?: profile.protocolHint).name"))
        assertTrue(facts.contains("profileCountryCode(activeOption?.displayName, profile.name)"))
        assertTrue(profileFact.contains("valueColor = colors.fg"))
        assertTrue(protocolFact.contains("valueColor = colors.fg"))
    }

    @Test
    fun `profiles use scoped panel and table labels without changing home labels`() {
        val screen = source("profiles/CliProfilesScreen.kt")
        val profilesPanel = screen
            .substringAfter("private fun CliProfilesPanel(")
            .substringBefore("private fun CliProfilesEmptyState(")
        val tableHeader = source("profiles/CliProfileListItem.kt")
            .substringAfter("internal fun CliProfileTableHeader()")
            .substringBefore("internal fun cliProfileTableHeaderStyle()")
        val home = source("home/CliProfileQuickSelector.kt")

        assertTrue(profilesPanel.contains("title = stringResource(R.string.cli_prof_available_title)"))
        assertTrue(tableHeader.contains("R.string.cli_prof_table_profile_name"))
        assertTrue(tableHeader.contains("R.string.cli_prof_table_vpn"))
        assertTrue(!tableHeader.contains("R.string.cli_home_key_profile"))
        assertTrue(!tableHeader.contains("R.string.cli_home_key_protocol"))
        assertTrue(home.contains("R.string.cli_prof_title"))
        assertTrue(home.contains("R.string.cli_home_key_profile"))
        assertTrue(home.contains("R.string.cli_home_key_protocol"))
    }

    private fun source(relative: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
        ).first(File::isFile).readText()
}
