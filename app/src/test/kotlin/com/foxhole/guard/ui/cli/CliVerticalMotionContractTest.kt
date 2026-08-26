package com.foxhole.guard.ui.cli

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

internal class CliVerticalMotionContractTest {
    private fun cli(path: String): String =
        File("src/main/kotlin/com/foxhole/guard/ui/cli/$path").readText()

    @Test
    fun `vertical bounds use an interruptible native spring with a restrained symmetric fade`() {
        val motion = cli("CliMotion.kt")
        val sizeSpec = cliVerticalSizeSpec()
        val scalarSpec = cliVerticalScalarSpec()
        val selectorSpec = cliProfileSelectorSizeSpec()

        assertTrue(sizeSpec is SpringSpec<*>)
        assertTrue(scalarSpec is SpringSpec<*>)
        assertEquals(Spring.DampingRatioNoBouncy, (sizeSpec as SpringSpec<*>).dampingRatio)
        assertEquals(Spring.StiffnessLow, sizeSpec.stiffness)
        assertEquals(Spring.DampingRatioNoBouncy, (scalarSpec as SpringSpec<*>).dampingRatio)
        assertEquals(Spring.StiffnessLow, scalarSpec.stiffness)
        assertTrue(selectorSpec is SpringSpec<*>)
        assertEquals(Spring.StiffnessMedium, (selectorSpec as SpringSpec<*>).stiffness)
        assertTrue(motion.contains("internal fun cliVerticalEnter("))
        assertTrue(motion.contains("internal fun cliVerticalExit("))
        assertTrue(motion.contains("internal fun cliVerticalSwap(): ContentTransform"))
        assertFalse(motion.contains("CliVerticalBoundsSpring"))
        assertFalse(motion.contains("CliVerticalScalarSpring"))
        val bootstrap = motion
            .substringAfter("internal fun cliBootstrapSwap(): ContentTransform")
            .substringBefore("internal fun cliBootstrapFade(): ContentTransform")
        assertTrue(bootstrap.contains("cliVerticalSizeSpec()"))
        val enter = motion
            .substringAfter("internal fun cliVerticalEnter(")
            .substringBefore("internal fun cliVerticalExit(")
        val exit = motion
            .substringAfter("internal fun cliVerticalExit(")
            .substringBefore("internal fun cliVerticalSizeSpec()")
        assertTrue(enter.contains("+ fadeIn()"))
        assertTrue(exit.contains("+ fadeOut()"))
    }

    @Test
    fun `direct vertical call sites consume the shared law without local timings`() {
        val consumers = listOf(
            cli("components/CliPanel.kt"),
            cli("home/CliHomeScreen.kt"),
            cli("home/CliRouteIdentityRows.kt"),
            cli("profiles/CliProfileListItem.kt"),
            cli("settings/CliHelpSubScreen.kt"),
        )

        consumers.forEach { source ->
            assertFalse(source.contains("expandVertically("))
            assertFalse(source.contains("shrinkVertically("))
        }
        assertTrue(consumers[0].contains("enter = cliVerticalEnter()"))
        assertTrue(consumers[1].contains("animationSpec = cliVerticalSizeSpec()"))
        assertTrue(consumers[2].contains("enter = cliVerticalEnter()"))
        assertTrue(consumers[3].contains("transitionSpec = { cliVerticalSwap() }"))
        assertTrue(consumers[4].contains("exit = cliVerticalExit()"))
    }

    @Test
    fun `dependent settings rows share one expansion law`() {
        val animation = cli("settings/CliSettingsAnimatedRows.kt")
        val consumers = listOf(
            cli("settings/CliSettingsScreen.kt"),
            cli("settings/CliSettingsDnsSection.kt"),
            cli("settings/CliNetworkRulesSection.kt"),
            cli("settings/CliRoutingModeSection.kt"),
            cli("settings/CliTorSubScreen.kt"),
            cli("settings/CliI2pSubScreen.kt"),
            cli("settings/CliLanProxySubScreen.kt"),
            cli("settings/CliLockSection.kt"),
            cli("settings/CliWebAppsSubScreen.kt"),
        )

        assertTrue(animation.contains("enter = cliVerticalEnter()"))
        assertTrue(animation.contains("exit = cliVerticalExit()"))
        assertFalse(animation.contains("expandVertically("))
        assertFalse(animation.contains("shrinkVertically("))
        consumers.forEach { source -> assertTrue(source.contains("CliSettingsAnimatedRows(")) }
    }

    @Test
    fun `modal surfaces avoid composing custom motion over platform motion`() {
        val sheet = cli("components/CliBottomSheet.kt")
        val frame = cli("components/CliModalFrame.kt")
        val sheetMotion = File("src/main/java/com/foxhole/guard/ui/cli/components/MaterialSheetMotion.java").readText()
        val platformModalConsumers = listOf(
            cli("components/CliInputModal.kt"),
            cli("onboarding/CliOnboardingWizard.kt"),
            cli("profiles/CliProfileEditorScreen.kt"),
            cli("profiles/CliProfileTransfer.kt"),
            cli("profiles/CliQrScannerOverlay.kt"),
        )

        assertFalse(sheet.contains("surfaceMotion"))
        assertFalse(sheet.contains("cliBottomSheetSurfaceMotion"))
        assertFalse(sheet.contains("animateContentSize("))
        assertFalse(frame.contains("cliModalContentEnter"))
        platformModalConsumers.forEach { source -> assertFalse(source.contains("cliModalContentEnter")) }
        assertTrue(sheet.contains("MaterialSheetMotion.slowSpatialSpec()"))
        assertTrue(sheet.contains("MaterialSheetMotion.applySlowSpatialSpec(sheetState, sheetMotionSpec)"))
        assertTrue(sheet.substringAfter("ModalBottomSheet(").contains("SideEffect"))
        assertTrue(sheetMotion.contains("setShowMotionSpec\$material3"))
        assertTrue(sheetMotion.contains("setHideMotionSpec\$material3"))
        assertTrue(sheetMotion.contains("setAnchoredDraggableMotionSpec\$material3"))
        assertTrue(sheet.contains("sheetState.hide()"))
        assertTrue(sheet.contains("afterHidden()"))
    }

    @Test
    fun `smart selector closes on the collapse completion without an extra serial delay`() {
        val motion = cli("CliMotion.kt")
        val home = cli("home/CliHomeScreen.kt")
        val selectorAnimation = home
            .substringAfter("val selectorHeightModifier")
            .substringBefore("val surface =")

        assertTrue(selectorAnimation.contains("animationSpec = cliProfileSelectorSizeSpec()"))
        assertTrue(motion.contains("internal fun cliProfileSelectorSizeSpec()"))
        assertTrue(selectorAnimation.contains("finishedListener = { _, _ ->"))
        assertTrue(selectorAnimation.contains("onSelectorCollapseFinished()"))
        assertFalse(selectorAnimation.contains("delay("))
        assertFalse(home.contains("CLI_SELECTOR_RESIZE_MS"))
        assertFalse(home.contains("SELECTOR_COLLAPSE_FALLBACK_MS"))
    }

    @Test
    fun `template reveal has no per item dead time`() {
        val template = cli("profiles/CliProfileTemplateSheet.kt")

        assertTrue(template.contains("animationSpec = cliVerticalScalarSpec()"))
        assertFalse(template.contains("TEMPLATE_REVEAL_STAGGER_MS"))
        assertFalse(template.contains("delayMillis = order"))
    }
}
