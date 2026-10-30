package com.foxhole.guard.router.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.R
import com.foxhole.guard.router.RouterEngineControlState
import com.foxhole.guard.router.RouterEngineStatus
import com.foxhole.guard.router.RouterEngineEntry
import com.foxhole.guard.router.RouterEngineAvailability
import com.foxhole.guard.router.RouterEngineStorage
import com.foxhole.guard.router.RouterEnginePlan
import com.foxhole.guard.router.RouterEngineChange
import com.foxhole.guard.router.RouterEngineBudget
import com.foxhole.guard.router.RouterEngineOperation
import com.foxhole.guard.router.RouterEngineImpact
import com.foxhole.guard.router.RouterEngineJob
import com.foxhole.guard.router.RouterEngineJobState
import com.foxhole.guard.router.RouterEngineStep
import com.foxhole.guard.router.RouterEngineFailureReason
import com.foxhole.guard.router.RouterInstallProtocol
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuardRouterProtocolsAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun missingProtocolSelectionExcludesRecognizedUnreleasedItems() {
        var selected: Set<RouterInstallProtocol> = emptySet()
        compose.setContent { GuardRouterTestTheme {
            GuardRouterProtocolsList(RouterEngineControlState(true, status()), emptySet(), rememberLazyListState(), true,
                onSelect = { selected = it }, onUpdate = {}, onRemove = {}, onShowJob = {})
        } }
        compose.onNodeWithText(context.getString(R.string.guard_router_vpn_router_choose_protocols)).performClick()
        compose.onNodeWithText("VLESS").performClick()
        compose.onNodeWithText("TUIC").assertDoesNotExist()
        compose.runOnIdle { assertEquals(setOf(RouterInstallProtocol.VLESS), selected) }
    }

    @Test fun anActiveProtocolCannotBeRemovedByTheRowAction() {
        var removed = 0
        compose.setContent { GuardRouterTestTheme {
            GuardRouterProtocolsList(RouterEngineControlState(true, status()), emptySet(), rememberLazyListState(), true,
                onSelect = {}, onUpdate = {}, onRemove = { removed++ }, onShowJob = {})
        } }
        compose.onNodeWithContentDescription(context.getString(R.string.guard_router_router_engines_remove_protocol, "Hysteria2"))
            .assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, removed) }
    }

    @Test fun activeUpdatePlanShowsTheExplicitRouterReconnectImpact() {
        compose.setContent { GuardRouterTestTheme { Column { GuardRouterProtocolPlan(plan()) } } }
        compose.onNodeWithText(context.getString(R.string.guard_router_router_engines_reconnect_note)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_engines_keep_active)).assertDoesNotExist()
    }

    @Test fun failureDoesNotInventAConnectionRestoration() {
        compose.setContent { GuardRouterTestTheme { Column {
            GuardRouterProtocolJob(RouterEngineJob("router-demo", "job-demo", "plan-demo", RouterEngineJobState.FAILED,
                70, RouterEngineStep.FAILED, 1, RouterEngineFailureReason.RECONNECT_FAILED, null))
        } } }
        compose.onNodeWithText(context.getString(R.string.guard_router_router_engines_failed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.guard_router_router_engines_restored)).assertDoesNotExist()
    }

    private fun status() = RouterEngineStatus("router-demo", 1, "a".repeat(64), RouterInstallProtocol.HYSTERIA2, "server-demo",
        listOf(RouterEngineEntry(RouterInstallProtocol.HYSTERIA2, RouterEngineAvailability.AVAILABLE, true, RouterEngineStorage.FLASH,
            "1.0.0", "b".repeat(64), true, true, 1024, 2048),
            RouterEngineEntry(RouterInstallProtocol.VLESS, RouterEngineAvailability.AVAILABLE, false, null, null, null, false, false, 1024, null),
            RouterEngineEntry(RouterInstallProtocol.TUIC, RouterEngineAvailability.NOT_RELEASED, false, null, null, null, false, false, null, null)),
        false, null, null)

    private fun plan() = RouterEnginePlan("router-demo", "plan-demo", "c".repeat(64), 1, "a".repeat(64), "boot-demo", 300_000,
        RouterEngineOperation.UPDATE, setOf(RouterInstallProtocol.HYSTERIA2),
        listOf(RouterEngineChange(RouterInstallProtocol.HYSTERIA2, "1.0.0", "1.0.1", RouterEngineStorage.FLASH, 1024, 2048)),
        RouterEngineBudget(1024, 2048, 2048, 8192, 2048, 8192, 1024), RouterEngineImpact.RECONNECT_REQUIRED, emptySet())
}
