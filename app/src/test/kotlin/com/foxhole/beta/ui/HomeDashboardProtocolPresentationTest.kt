package com.foxhole.beta.ui

import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardProtocolPresentationTest {
    @Test
    fun `auto connect uses live candidate list so dashboard selector matches analysis status`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = "outline",
                protocolOptions =
                    listOf(
                        option("outline", ProtocolHint.OUTLINE),
                    ),
            )

        val resolved =
            resolveHomeDashboardProtocolPresentation(
                activeProfile = activeProfile,
                autoConnect =
                    AutoConnectUiState(
                        running = true,
                        currentOptionId = "trojan",
                        options =
                            listOf(
                                AutoConnectProbeOptionUiState(
                                    optionId = "trojan",
                                    displayName = "TROJAN",
                                    protocolHint = ProtocolHint.TROJAN,
                                ),
                                AutoConnectProbeOptionUiState(
                                    optionId = "outline",
                                    displayName = "OUTLINE",
                                    protocolHint = ProtocolHint.OUTLINE,
                                ),
                            ),
                    ),
            )

        assertEquals("trojan", resolved.selectedProtocolOptionId)
        assertEquals(ProtocolHint.TROJAN, resolved.protocolHint)
        assertEquals(listOf("trojan", "outline"), resolved.protocolOptions.map(ProfileProtocolOption::id))
        assertTrue(resolved.protocolOptions.first { option -> option.id == "trojan" }.isSelected)
    }

    @Test
    fun `idle dashboard keeps persisted protocol selection`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = "outline",
                protocolOptions =
                    listOf(
                        option("outline", ProtocolHint.OUTLINE),
                        option("trojan", ProtocolHint.TROJAN),
                    ),
            )

        val resolved =
            resolveHomeDashboardProtocolPresentation(
                activeProfile = activeProfile,
                autoConnect = AutoConnectUiState(),
            )

        assertEquals("outline", resolved.selectedProtocolOptionId)
        assertEquals(ProtocolHint.OUTLINE, resolved.protocolHint)
        assertEquals(listOf("outline", "trojan"), resolved.protocolOptions.map(ProfileProtocolOption::id))
        assertTrue(resolved.protocolOptions.first { option -> option.id == "outline" }.isSelected)
    }

    @Test
    fun `dashboard selected option falls back to the only supported protocol when selection is not persisted`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = null,
                protocolOptions =
                    listOf(
                        option("outline", ProtocolHint.OUTLINE),
                    ),
            )

        assertEquals("outline", resolveDashboardSelectedOptionId(activeProfile))
    }

    @Test
    fun `dashboard selected option keeps explicit saved selection`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = "trojan",
                protocolOptions =
                    listOf(
                        option("outline", ProtocolHint.OUTLINE),
                        option("trojan", ProtocolHint.TROJAN),
                    ),
            )

        assertEquals("trojan", resolveDashboardSelectedOptionId(activeProfile))
    }

    @Test
    fun `dashboard latency option falls back to protocol hint for single profile without protocol options`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = null,
                protocolHint = ProtocolHint.SHADOWSOCKS,
                protocolOptions = emptyList(),
            )

        assertEquals("shadowsocks", resolveDashboardLatencyOptionId(activeProfile))
    }

    @Test
    fun `dashboard auto connect latency ignores failed probe duration and keeps down state`() {
        val state =
            HomeRouteUiState(
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTING),
                autoConnect =
                    AutoConnectUiState(
                        running = true,
                        currentOptionId = "trojan",
                        options =
                            listOf(
                                AutoConnectProbeOptionUiState(
                                    optionId = "trojan",
                                    displayName = "TROJAN",
                                    protocolHint = ProtocolHint.TROJAN,
                                    status = AutoConnectProbeStatus.FAILED,
                                    latencyMs = 30_000L,
                                ),
                            ),
                    ),
            )

        assertNull(resolveDashboardSelectedLatencyMs(state))
        assertTrue(resolveDashboardSelectedLatencyDown(state))
    }

    @Test
    fun `dashboard auto connect does not fall back to stale success ping when current candidate is down`() {
        val state =
            HomeRouteUiState(
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTING),
                autoConnect =
                    AutoConnectUiState(
                        running = true,
                        currentOptionId = "trojan",
                        options =
                            listOf(
                                AutoConnectProbeOptionUiState(
                                    optionId = "outline",
                                    displayName = "OUTLINE",
                                    protocolHint = ProtocolHint.OUTLINE,
                                    status = AutoConnectProbeStatus.SUCCESS,
                                    latencyMs = 184L,
                                ),
                                AutoConnectProbeOptionUiState(
                                    optionId = "trojan",
                                    displayName = "TROJAN",
                                    protocolHint = ProtocolHint.TROJAN,
                                    status = AutoConnectProbeStatus.FAILED,
                                    latencyMs = 30_000L,
                                ),
                            ),
                    ),
            )

        assertNull(resolveDashboardSelectedLatencyMs(state))
        assertTrue(resolveDashboardSelectedLatencyDown(state))
    }

    @Test
    fun `dashboard auto connect latency keeps successful probe value`() {
        val state =
            HomeRouteUiState(
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTING),
                autoConnect =
                    AutoConnectUiState(
                        running = true,
                        currentOptionId = "outline",
                        options =
                            listOf(
                                AutoConnectProbeOptionUiState(
                                    optionId = "outline",
                                    displayName = "OUTLINE",
                                    protocolHint = ProtocolHint.OUTLINE,
                                    status = AutoConnectProbeStatus.SUCCESS,
                                    latencyMs = 184L,
                                ),
                            ),
                    ),
            )

        assertEquals(184L, resolveDashboardSelectedLatencyMs(state))
    }

    @Test
    fun `dashboard auto connect shows unavailable when current candidate has no request latency`() {
        val state =
            HomeRouteUiState(
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTING),
                autoConnect =
                    AutoConnectUiState(
                        running = true,
                        currentOptionId = "outline",
                        options =
                            listOf(
                                AutoConnectProbeOptionUiState(
                                    optionId = "outline",
                                    displayName = "OUTLINE",
                                    protocolHint = ProtocolHint.OUTLINE,
                                    status = AutoConnectProbeStatus.SUCCESS,
                                    latencyUnavailable = true,
                                ),
                            ),
                    ),
            )

        assertNull(resolveDashboardSelectedLatencyMs(state))
        assertFalse(resolveDashboardSelectedLatencyDown(state))
        assertTrue(resolveDashboardSelectedLatencyUnavailable(state))
    }

    @Test
    fun `connected dashboard shows unavailable when request latency refresh failed`() {
        val state =
            HomeRouteUiState(
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTED),
                selectedProtocolLatencyUnavailable = true,
            )

        assertNull(resolveDashboardSelectedLatencyMs(state))
        assertFalse(resolveDashboardSelectedLatencyDown(state))
        assertTrue(resolveDashboardSelectedLatencyUnavailable(state))
    }

    private fun profile(
        selectedProtocolOptionId: String?,
        protocolHint: ProtocolHint = ProtocolHint.OUTLINE,
        protocolOptions: List<ProfileProtocolOption>,
    ) = Profile(
        id = 1L,
        name = "Foxhole vpn direct",
        sourceType = ProfileSourceType.SUBSCRIPTION_URL,
        secretRef = "secret-1",
        protocolHint = protocolHint,
        lastUpdatedAt = null,
        lastEtag = null,
        subscriptionExpiresAt = null,
        protocolOptions = protocolOptions,
        selectedProtocolOptionId = selectedProtocolOptionId,
        isActive = true,
    )

    private fun option(
        id: String,
        protocolHint: ProtocolHint,
    ) = ProfileProtocolOption(
        id = id,
        displayName = protocolHint.name,
        protocolHint = protocolHint,
        isSelected = false,
    )
}
