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
    fun `expanded smart table pins one v2raytun header above lazy option rows`() {
        val selector = source("home/CliProfileQuickSelector.kt")
        val protocolTable = source("profiles/CliProtocolDropdown.kt")

        assertTrue(selector.contains("stickyHeader(key = \"proto-header-${'$'}{profile.id}\")"))
        assertTrue(selector.contains("stickyHeader(key = \"proto-boundary-${'$'}{profile.id}\")"))
        assertTrue(selector.contains("PROTOCOL_STICKY_BOUNDARY_HEIGHT"))
        assertTrue(selector.contains("nameLabel = \"v2raytun\""))
        assertTrue(selector.contains("itemsIndexed("))
        assertTrue(selector.contains("key = { _, option -> \"proto-${'$'}{profile.id}-${'$'}{option.id}\" }"))
        assertFalse(selector.contains("nameLabel = stringResource(R.string.cli_prof_table_name)"))
        assertFalse(selector.contains("item(key = \"proto-block-${'$'}{profile.id}\")"))
        assertTrue(protocolTable.contains("nameLabel: String? = null"))
        assertTrue(protocolTable.contains("text = nameLabel"))
    }

    @Test
    fun `the plain selector stays at facts height while smart expansion may grow`() {
        assertTrue(cliProfileSelectorUsesFixedHeight(measuredHeightPx = 240, smartHeightActive = false))
        assertFalse(cliProfileSelectorUsesFixedHeight(measuredHeightPx = 240, smartHeightActive = true))
        assertFalse(cliProfileSelectorUsesFixedHeight(measuredHeightPx = 0, smartHeightActive = false))

        val home = source("home/CliHomeScreen.kt")
        val selector = source("home/CliProfileQuickSelector.kt")
        assertTrue(home.contains("Modifier.height(profileAreaMeasuredHeight)"))
        assertTrue(home.contains("Modifier.heightIn(min = profileAreaMeasuredHeight)"))
        assertTrue(selector.contains(".heightIn(max = 300.dp)"))
    }

    @Test
    fun `back collapses a smart selector before the profile area slides home`() {
        val home = source("home/CliHomeScreen.kt")
        val selector = source("home/CliProfileQuickSelector.kt")
        val close = home
            .substringAfter("val closeSelector: () -> Unit =")
            .substringBefore("CliHomeNarrationEffects(")
        val profileArea = home
            .substringAfter("private fun CliHomeProfileArea(")
            .substringBefore("internal fun cliProfileSelectorUsesFixedHeight")

        assertTrue(close.contains("selectorOpen = false"))
        val back = home
            .substringAfter("private fun CliSelectorBackHandler(")
            .substringBefore("private fun CliHomeConfirmationSlot(")
        assertTrue(home.contains("expandedSmartId = null"))
        assertTrue(home.contains("backCollapsePending = true"))
        assertTrue(back.contains("BackHandler(enabled = selectorOpen)"))
        assertFalse(back.contains("delay("))
        assertFalse(home.contains("CLI_SELECTOR_RESIZE_MS"))
        assertFalse(home.contains("SELECTOR_COLLAPSE_FALLBACK_MS"))
        assertTrue(
            profileArea.contains(
                "val smartSelectorHeightActive = expandedSmartId != null",
            ),
        )
        assertTrue(profileArea.contains(".animateContentSize("))
        assertTrue(profileArea.contains("finishedListener = { _, _ ->"))
        assertTrue(profileArea.contains("if (selectorCollapsePending) onSelectorCollapseFinished()"))
        assertTrue(profileArea.contains(".then(selectorHeightConstraint)"))
        assertTrue(profileArea.contains("updateTransition(targetState = surface"))
        assertTrue(profileArea.contains("CliProfileAreaTransitionEffects("))
        assertTrue(profileArea.contains("transition.currentState == CliProfileAreaSurface.FACTS"))
        assertTrue(profileArea.contains("transition.targetState == CliProfileAreaSurface.FACTS"))
        assertTrue(profileArea.contains("onExpandedSmartChange(null)"))
        assertFalse(profileArea.contains("LaunchedEffect(selectorOpen, expandedSmartId)"))
        assertTrue(profileArea.contains("animationSpec = cliProfileSelectorSizeSpec()"))
        assertTrue(profileArea.contains("alignment = Alignment.BottomStart"))
        assertFalse(selector.contains("animateContentSize"))
        assertTrue(selector.contains("stickyHeader(key = \"proto-header-${'$'}{profile.id}\")"))
        assertTrue(selector.contains("stickyHeader(key = \"proto-boundary-${'$'}{profile.id}\")"))
    }

    @Test
    fun `selection collapses down then slides left before applying or opening confirmation`() {
        val home = source("home/CliHomeScreen.kt")
        val coordinator = home
            .substringAfter("private fun rememberCliProfileSelectorCoordinator(")
            .substringBefore("private fun CliHomeProfileArea(")
        val profileArea = home
            .substringAfter("private fun CliHomeProfileArea(")
            .substringBefore("internal fun cliProfileSelectorUsesFixedHeight")
        val selectionSequence = coordinator
            .substringAfter("LaunchedEffect(pendingSelection)")
            .substringBefore("CliSelectorBackHandler(")

        assertTrue(
            selectionSequence.indexOf("backCollapsePending = true") <
                selectionSequence.indexOf("expandedSmartId = null"),
        )
        assertFalse(selectionSequence.contains("delay("))
        assertTrue(selectionSequence.contains("else if (!backCollapsePending)"))
        assertTrue(profileArea.contains("selectionCommitPending"))
        assertTrue(
            profileArea.contains(
                "targetState == CliProfileAreaSurface.SELECTOR ||\n                        selectionCommitPending",
            ),
        )
        assertTrue(profileArea.contains("onSelectionSlideFinished()"))
        assertTrue(coordinator.contains("viewModel.onQuickSelectorSingleProfileSelected"))
        assertTrue(coordinator.contains("viewModel.onSelectProfileProtocolOption"))
    }

    @Test
    fun `facts shrink smoothly and release their height back to the weighted console`() {
        val home = source("home/CliHomeScreen.kt")
        val routeIdentity = source("home/CliRouteIdentityRows.kt")
        val factsSurface = home
            .substringAfter("CliProfileAreaSurface.FACTS ->")
            .substringBefore("internal fun cliProfileSelectorUsesFixedHeight")

        assertFalse(factsSurface.contains(".animateContentSize("))
        assertTrue(home.contains("cliBootstrapSwap()"))
        assertTrue(routeIdentity.contains("enter = cliVerticalEnter()"))
        assertTrue(routeIdentity.contains("exit = cliVerticalExit()"))
        assertTrue(factsSurface.contains("profileAreaMeasuredHeightPx = size.height"))
        assertFalse(factsSurface.contains(".heightIn(min = profileAreaMeasuredHeight)"))
        assertFalse(factsSurface.contains("maxOf(profileAreaMeasuredHeightPx"))
        assertTrue(home.contains("CliTerminalPanel("))
        assertTrue(home.contains(".weight(1f)"))
    }

    private fun source(relative: String): String =
        File("src/main/kotlin/com/foxhole/guard/ui/cli/$relative").readText()
}
