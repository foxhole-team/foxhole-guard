package com.foxhole.guard.router

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterManagementToggleStateTest {
    private val pairingId = "pair-" + "a".repeat(32)
    private val paired = RouterControlState(
        stage = RouterControlStage.DISABLED,
        preferences = RouterControlPreferences(pairingId),
        selectedRouterId = pairingId,
        savedRouters = listOf(RouterSavedSummary(pairingId, 1, null, "192.168.1.1")),
    )

    @Test fun unpairedAndUnknownStatesCannotAppearEnabled() {
        assertFalse(RouterControlState().managementEnabled)
        assertFalse(RouterControlState().canToggleManagement)
        listOf(RouterControlStage.LOADING, RouterControlStage.UNPAIRED, RouterControlStage.PAIRING_REQUIRED).forEach { stage ->
            val state = paired.copy(stage = stage, preferences = RouterControlPreferences(pairingId, enabled = true))
            assertFalse(state.managementEnabled)
            assertFalse(state.canToggleManagement)
        }
    }

    @Test fun onlyTheSelectedSavedPairingCanBeManaged() {
        listOf(
            paired.copy(preferences = null),
            paired.copy(selectedRouterId = null),
            paired.copy(selectedRouterId = "pair-" + "b".repeat(32)),
            paired.copy(savedRouters = emptyList()),
        ).forEach { state ->
            assertFalse(state.managementEnabled)
            assertFalse(state.canToggleManagement)
        }
    }

    @Test fun aSavedDisabledRouterCanBeEnabledWithoutClaimingAConnection() {
        assertTrue(paired.canToggleManagement)
        assertFalse(paired.managementEnabled)
        val offline = paired.copy(stage = RouterControlStage.OFFLINE,
            preferences = RouterControlPreferences(pairingId, enabled = true))
        assertTrue(offline.canToggleManagement)
        assertTrue(offline.managementEnabled)
        assertFalse(offline.controlsAvailable)
    }

    @Test fun connectingRetainsTheSavedManagementChoiceButCannotBeToggled() {
        val connecting = paired.copy(stage = RouterControlStage.CONNECTING, busy = false,
            preferences = RouterControlPreferences(pairingId, enabled = true))
        assertTrue(connecting.managementConfigured)
        assertTrue(connecting.managementEnabled)
        assertFalse(connecting.canToggleManagement)
        assertFalse(connecting.copy(preferences = RouterControlPreferences(pairingId)).managementEnabled)
    }

    @Test fun pendingControlOrPackageWorkBlocksChangingManagement() {
        assertFalse(paired.copy(busy = true).canToggleManagement)
        assertFalse(paired.copy(updates = RouterUpdateControlState(reconnecting = true)).canToggleManagement)
    }
}
