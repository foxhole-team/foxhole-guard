package com.foxhole.guard.ui.cli.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliProfileSelectorLeadingSlotTest {

    @Test
    fun `the active row keeps its flag beside the dot`() {
        val selector = source("home/CliProfileQuickSelector.kt")
        val slot = selector
            .substringAfter("private fun ProfileSelectorLeadingSlot(")
            .substringBefore("@Composable")

        assertTrue(slot.contains("CliActiveDot(active = active)"))
        assertTrue(slot.contains("CliFlagIcon(countryCode = country"))
        assertFalse(slot.contains("if (active)"))
        assertFalse(slot.contains("return@Box"))
    }

    @Test
    fun `the icon column is one fixed width for active and inactive rows alike`() {
        val selector = source("home/CliProfileQuickSelector.kt")

        assertTrue(
            selector.contains(
                "PROFILE_SELECTOR_LEADING_SLOT = CLI_PROFILE_TABLE_RIM + CLI_ACTIVE_DOT_SLOT_WIDTH",
            ),
        )
        assertTrue(selector.contains("Modifier.width(PROFILE_SELECTOR_LEADING_SLOT)"))
        assertTrue(selector.contains("Spacer(modifier = Modifier.width(PROFILE_SELECTOR_LEADING_SLOT))"))
    }

    @Test
    fun `the profile name starts flush in its own column`() {
        val selector = source("home/CliProfileQuickSelector.kt")
        val row = selector
            .substringAfter("private fun CliProfileSelectorRow(")
            .substringBefore("private fun ProfileSelectorLeadingSlot(")

        assertTrue(row.contains("ProfileSelectorLeadingSlot(profile = profile, active = active)"))
        assertTrue(row.contains(".weight(PROFILE_SELECTOR_NAME_WEIGHT)"))
        assertFalse(row.contains("CliActiveDot("))
    }

    @Test
    fun `the plain selector stays at facts height while smart expansion may grow`() {
        assertTrue(cliProfileSelectorUsesFixedHeight(measuredHeightPx = 240, smartHeightActive = false))
        assertFalse(cliProfileSelectorUsesFixedHeight(measuredHeightPx = 240, smartHeightActive = true))
        assertFalse(cliProfileSelectorUsesFixedHeight(measuredHeightPx = 0, smartHeightActive = false))

        val home = source("home/CliHomeScreen.kt")
        val selector = source("home/CliProfileQuickSelector.kt")
        assertTrue(home.contains("Modifier.height(profileAreaMinHeight)"))
        assertTrue(home.contains("Modifier.heightIn(min = profileAreaMinHeight)"))
        assertTrue(selector.contains(".heightIn(max = 300.dp)"))
    }

    private fun source(relative: String): String =
        File("src/main/kotlin/com/foxhole/guard/ui/cli/$relative").readText()
}
