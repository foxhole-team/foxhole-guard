package com.foxhole.guard.router.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.R
import com.foxhole.guard.router.RouterInstallCatalog
import com.foxhole.guard.router.RouterInstallProtocol
import com.foxhole.guard.router.RouterWizardOptions
import com.foxhole.guard.router.RouterWizardPath
import com.foxhole.guard.router.RouterWizardStage
import com.foxhole.guard.router.RouterWizardState
import com.foxhole.guard.router.routerReleaseSha256
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic catalog UI only; no router, network, installation, profile or secret access. */
@RunWith(AndroidJUnit4::class)
class GuardRouterCompactWizardAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun incomingServerIsOffByDefaultAndExplicitSelectionPrecedesLogin() {
        val catalog = catalog("hysteria2", server = true)
        var selected: Set<RouterInstallProtocol>? = null
        var includeServer: Boolean? = null
        var confirmed = 0
        val actions = actions({ protocols, server -> selected = protocols; includeServer = server }, { confirmed++ })
        compose.setContent { GuardRouterTestTheme {
            GuardRouterWizardSheet(RouterWizardState(stage = RouterWizardStage.PROTOCOLS, installSelection = catalog.defaultSelection),
                emptyList(), false, false, catalog, actions)
        } }
        val toggle = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
        toggle.assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText(context.getString(R.string.guard_router_vpn_continue)).performClick()
        compose.runOnIdle {
            assertEquals(setOf(RouterInstallProtocol.HYSTERIA2), selected)
            assertEquals(true, includeServer)
            assertEquals(1, confirmed)
        }
    }

    @Test fun aVlessOnlyReleaseRequiresChoosingVlessWithoutInsertingHysteria() {
        val catalog = catalog("vless", server = false)
        var selected: Set<RouterInstallProtocol>? = null
        var includeServer: Boolean? = null
        val actions = actions({ protocols, server -> selected = protocols; includeServer = server }, {})
        compose.setContent { GuardRouterTestTheme {
            GuardRouterWizardSheet(RouterWizardState(stage = RouterWizardStage.PROTOCOLS), emptyList(), false, false, catalog, actions)
        } }
        compose.onNodeWithText(context.getString(R.string.guard_router_vpn_continue)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.guard_router_vpn_router_choose_protocols)).performClick()
        compose.onNodeWithText("VLESS").performClick()
        compose.onNodeWithText("Hysteria2").assertDoesNotExist()
        Espresso.pressBack()
        compose.onNodeWithText(context.getString(R.string.guard_router_vpn_continue)).performClick()
        compose.runOnIdle {
            assertEquals(setOf(RouterInstallProtocol.VLESS), selected)
            assertEquals(false, includeServer)
        }
    }

    @Test fun newSetupShowsAutomaticUpdatesOnAndKeepsAnExplicitOptOut() {
        val catalog = catalog("hysteria2", server = false)
        val state = mutableStateOf(RouterWizardState(stage = RouterWizardStage.PROTOCOLS, path = RouterWizardPath.SETUP,
            installSelection = catalog.defaultSelection))
        val actions = actions({ _, _ -> }, {}, onOptions = { state.value = state.value.copy(options = it) })
        compose.setContent { GuardRouterTestTheme {
            GuardRouterWizardSheet(state.value, emptyList(), false, false, catalog, actions)
        } }
        compose.onNodeWithText(context.getString(R.string.guard_router_vpn_router_setup_auto_updates)).assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertFalse(state.value.options.autoUpdateEnabled) }
    }

    @Test fun installReviewIncludesTheExplicitAutomaticUpdateChoice() {
        compose.setContent { GuardRouterTestTheme {
            GuardRouterWizardSheet(RouterWizardState(stage = RouterWizardStage.INSTALL, path = RouterWizardPath.SETUP,
                options = RouterWizardOptions(autoUpdateEnabled = false)), emptyList(), false, false, null, actions({ _, _ -> }, {}))
        } }
        compose.onNodeWithText(context.getString(R.string.guard_router_vpn_router_setup_auto_updates)).assertExists()
        compose.onNodeWithText(context.getString(R.string.guard_router_vpn_router_setup_auto_off)).assertExists()
    }

    @Test fun ordinaryPairingDoesNotOfferASetupDefaultOrWriteItOnOpen() {
        var changed = false
        compose.setContent { GuardRouterTestTheme {
            GuardRouterWizardSheet(RouterWizardState(stage = RouterWizardStage.OPTIONS), emptyList(), false, false, null,
                actions({ _, _ -> }, {}, onOptions = { changed = true }))
        } }
        compose.onNodeWithText(context.getString(R.string.guard_router_vpn_router_setup_auto_updates)).assertDoesNotExist()
        compose.runOnIdle { assertFalse(changed) }
    }

    @Test fun anUncertainPolicyWriteShowsStatusActionAndDisablesTheChoice() {
        var statuses = 0
        val actions = actions({ _, _ -> }, {}).copy(finish = { statuses++ })
        compose.setContent { GuardRouterTestTheme {
            GuardRouterWizardSheet(RouterWizardState(stage = RouterWizardStage.OPTIONS, installedInThisWizard = true,
                autoUpdateUnconfirmed = true), emptyList(), false, false, null, actions)
        } }
        compose.onNodeWithText(context.getString(R.string.guard_router_vpn_router_setup_auto_updates)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.guard_router_vpn_router_setup_auto_unknown)).assertExists()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_updates_status)).performClick()
        compose.runOnIdle { assertEquals(1, statuses) }
    }

    private fun actions(onSelect: (Set<RouterInstallProtocol>, Boolean) -> Unit, confirm: () -> Unit,
        onOptions: (RouterWizardOptions) -> Unit = {}) = RouterWizardActions(
        inspect = {}, trust = {}, installProtocols = {}, confirmInstallProtocols = confirm,
        login = {}, newPin = {}, install = {}, options = onOptions, finish = {}, failure = {}, installCompactSelection = onSelect,
    )

    private fun catalog(id: String, server: Boolean): RouterInstallCatalog {
        val hash = "a".repeat(64); val slot = "b".repeat(64)
        val name = "foxcore-engine-$id-flash-$slot"
        fun asset(file: String, sha: String = hash) = buildJsonObject { put("file", file); put("sha256", sha) }
        fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
        val document = buildJsonObject {
            put("schema", 2); put("kind", "compact-client-engines-v1"); put("development", false)
            put("defaultProtocols", strings(listOf("hysteria2"))); put("allProtocols", strings(RouterInstallProtocol.entries.map { it.canonical }.sorted()))
            put("platforms", JsonArray(listOf(buildJsonObject {
                put("id", "synthetic-x86"); put("architecture", "x86_64"); put("supportedTargets", strings(listOf("x86/64"))); put("supportedOpenWrt", strings(listOf("25.12.5")))
                put("basePackage", buildJsonObject { put("name", "foxhole-openwrt-client"); put("file", "panel.apk"); put("sha256", hash); put("packageIdentity", hash) })
                put("engineCatalog", asset("engines.json")); put("packageUniverse", asset("universe.json"))
                put("clients", JsonArray(listOf(buildJsonObject {
                    put("id", id); put("storage", "flash")
                    put("producer", asset("producer.json")); put("capabilities", asset("caps.json")); put("slotManifest", asset("slot.json", slot))
                    put("entry", buildJsonObject {
                        put("id", id); put("storage", "FLASH"); put("version", "1.0.0"); put("architecture", "x86_64"); put("sourceCommit", "e".repeat(40))
                        for (field in listOf("binarySha256", "executableSha256", "producerSha256", "capabilitiesSha256", "legalProvenanceSha256", "packageSha256", "indexSha256")) put(field, hash)
                        put("manifestSha256", slot); put("binaryBytes", 8192); put("archiveBytes", 1024); put("indexBytes", 512); put("installedBytes", 12288)
                        put("runtimeMemoryBytes", 33554432); put("reserveBytes", 16777216); put("packageName", name); put("packageVersion", "1.0.0-r1")
                        put("packageUrl", "https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.0/$name-1.0.0-r1.apk")
                        put("indexUrl", "https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.0/engines.adb")
                    })
                })))
                put("server", if (server) buildJsonObject { put("producer", asset("server.json")); put("modes", buildJsonObject {
                    put("flash", buildJsonObject { put("name", "hysteria"); put("file", "server.apk"); put("sha256", hash) })
                }) } else JsonNull)
            })))
        }
        val bytes = document.toString().toByteArray()
        return RouterInstallCatalog.parseVerified(bytes, routerReleaseSha256(bytes), hash)
    }
}
