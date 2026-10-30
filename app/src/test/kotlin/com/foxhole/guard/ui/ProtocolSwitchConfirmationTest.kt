package com.foxhole.guard.ui

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolSwitchConfirmationTest {
    private val profile = Profile(
        id = 42L,
        name = "Imported profile",
        sourceType = ProfileSourceType.RAW_CONFIG_JSON,
        secretRef = "owned-synthetic",
        protocolHint = ProtocolHint.VLESS,
        lastUpdatedAt = 10L,
        lastEtag = null,
        protocolOptions = listOf(
            ProfileProtocolOption("vless", "First", ProtocolHint.VLESS, isSelected = true),
            ProfileProtocolOption("trojan", "Second", ProtocolHint.TROJAN),
        ),
        selectedProtocolOptionId = "vless",
        isActive = true,
    )
    private val connected = ConnectionSnapshot(
        state = ConnectionState.CONNECTED,
        profileId = profile.id,
        protocolOptionId = "vless",
        protocolHint = ProtocolHint.VLESS,
        lastChangeAt = 100L,
    )

    @Test
    fun differentActiveOptionRequiresConfirmationWithoutChangingTheProfile() {
        assertEquals(ProtocolOptionSelectionAction.CONFIRM, protocolOptionSelectionAction(connected, profile, "trojan"))
        assertEquals("vless", profile.selectedProtocolOptionId)
    }

    @Test
    fun currentRuntimeOptionDoesNothingEvenIfStoredSelectionDiffers() {
        val changed = profile.copy(selectedProtocolOptionId = "trojan")
        assertEquals(ProtocolOptionSelectionAction.IGNORE, protocolOptionSelectionAction(connected, changed, "vless"))
    }

    @Test
    fun disconnectedSelectionIsImmediateAndRepeatingItDoesNothing() {
        val idle = connected.copy(state = ConnectionState.IDLE, profileId = null)
        assertEquals(ProtocolOptionSelectionAction.SELECT, protocolOptionSelectionAction(idle, profile, "trojan"))
        assertEquals(ProtocolOptionSelectionAction.IGNORE, protocolOptionSelectionAction(idle, profile, "vless"))
    }

    @Test
    fun torOnlyRuntimeDoesNotCauseAVpnProtocolConfirmation() {
        val tor = connected.copy(profileId = -1L)
        assertEquals(ProtocolOptionSelectionAction.SELECT, protocolOptionSelectionAction(tor, profile, "trojan"))
    }

    @Test
    fun unknownRuntimeOptionStillRequiresConfirmationForADifferentStoredChoice() {
        val unknown = connected.copy(protocolOptionId = null, protocolHint = null)
        assertEquals(ProtocolOptionSelectionAction.CONFIRM, protocolOptionSelectionAction(unknown, profile, "trojan"))
        assertEquals(ProtocolOptionSelectionAction.IGNORE, protocolOptionSelectionAction(unknown, profile, "vless"))
    }

    @Test
    fun missingOrDisabledOptionsNeverChangeSelection() {
        val disabled = profile.copy(protocolOptions = profile.protocolOptions.map { it.copy(enabled = false) })
        assertEquals(ProtocolOptionSelectionAction.IGNORE, protocolOptionSelectionAction(connected, profile, "removed"))
        assertEquals(ProtocolOptionSelectionAction.IGNORE, protocolOptionSelectionAction(connected, disabled, "trojan"))
    }

    @Test
    fun staleRuntimeOrProfileInvalidatesTheConfirmation() {
        val prompt = protocolSwitchPrompt(profile, "trojan", connected)
        assertTrue(protocolSwitchConfirmationIsCurrent(prompt, connected, profile))
        listOf(
            connected.copy(state = ConnectionState.IDLE),
            connected.copy(profileId = 7L),
            connected.copy(protocolOptionId = "trojan"),
            connected.copy(lastChangeAt = 101L),
        ).forEach { assertFalse(protocolSwitchConfirmationIsCurrent(prompt, it, profile)) }
        listOf(
            null,
            profile.copy(lastUpdatedAt = 11L),
            profile.copy(isActive = false),
            profile.copy(protocolOptions = profile.protocolOptions.filterNot { it.id == "trojan" }),
        ).forEach { assertFalse(protocolSwitchConfirmationIsCurrent(prompt, connected, it)) }
    }

    @Test
    fun cancellationOrContinueConsumesOnlyTheSamePendingPromptOnce() {
        val old = protocolSwitchPrompt(profile, "trojan", connected)
        val state = MutableStateFlow<TorTransitionPrompt?>(old)
        assertTrue(state.consumeProtocolSwitchConfirmation(old))
        assertFalse(state.consumeProtocolSwitchConfirmation(old))
        val newer = protocolSwitchPrompt(profile, "trojan", connected.copy(lastChangeAt = 101L))
        state.value = newer
        assertFalse(state.consumeProtocolSwitchConfirmation(old))
        assertEquals(newer, state.value)
    }

    @Test
    fun confirmedCurrentChoiceCommitsAndReconnectsExactlyOnce() = runBlocking {
        var writes = 0
        var reconnects = 0
        applyConfirmedProtocolSwitch(
            protocolSwitchPrompt(profile, "trojan", connected),
            currentConnection = { connected },
            readProfile = { profile },
            select = {
                writes++
                profile.copy(selectedProtocolOptionId = "trojan")
            },
            onCommitted = { reconnects++ },
        )
        assertEquals(1, writes)
        assertEquals(1, reconnects)
    }

    @Test
    fun stopWhileWaitingForTheCommittedProfilePreventsSelectionAndReconnect() = runBlocking {
        val release = CompletableDeferred<Profile>()
        var current = connected
        var effects = 0
        val operation = launch(start = CoroutineStart.UNDISPATCHED) {
            applyConfirmedProtocolSwitch(
                protocolSwitchPrompt(profile, "trojan", connected),
                currentConnection = { current },
                readProfile = { release.await() },
                select = {
                    effects++
                    profile.copy(selectedProtocolOptionId = "trojan")
                },
                onCommitted = { effects++ },
            )
        }
        current = connected.copy(state = ConnectionState.IDLE, lastChangeAt = 101L)
        release.complete(profile)
        operation.join()
        assertEquals(0, effects)
    }

    @Test
    fun aNewRuntimeDuringAnAlreadyConfirmedWriteIsNeverReconnected() = runBlocking {
        var current = connected
        var writes = 0
        var reconnects = 0
        applyConfirmedProtocolSwitch(
            protocolSwitchPrompt(profile, "trojan", connected),
            currentConnection = { current },
            readProfile = { profile },
            select = {
                writes++
                current = connected.copy(lastChangeAt = 101L)
                profile.copy(selectedProtocolOptionId = "trojan")
            },
            onCommitted = { reconnects++ },
        )
        assertEquals(1, writes)
        assertEquals(0, reconnects)
    }
}
