package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeReconnectPolicyTest {
    @Test
    fun `profile reconnect prompt window is nine seconds`() {
        assertTrue(HomeViewModel.PROFILE_RECONNECT_PROMPT_WINDOW_MS == 9_000L)
    }

    @Test
    fun `home route carries reconnect prompt deadline`() {
        val routeState =
            HomeUiState(
                reconnectRequired = true,
                profileReconnectPromptUntilElapsedMs = 42_000L,
            ).toHomeRouteUiState()

        assertTrue(routeState.reconnectRequired)
        assertTrue(routeState.profileReconnectPromptUntilElapsedMs == 42_000L)
    }

    @Test
    fun `switching smart profile protocol while connected requires reconnect`() {
        assertTrue(
            isProfileReconnectRequired(
                activeProfile = profile(id = 7L, protocolHint = ProtocolHint.TROJAN),
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = 7L,
                        protocolHint = ProtocolHint.OUTLINE,
                    ),
            ),
        )
    }

    @Test
    fun `matching connected protocol keeps reconnect cleared`() {
        assertFalse(
            isProfileReconnectRequired(
                activeProfile = profile(id = 7L, protocolHint = ProtocolHint.OUTLINE),
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = 7L,
                        protocolHint = ProtocolHint.OUTLINE,
                    ),
            ),
        )
    }

    @Test
    fun `switching active profile still requires reconnect`() {
        assertTrue(
            isProfileReconnectRequired(
                activeProfile = profile(id = 8L, protocolHint = ProtocolHint.OUTLINE),
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = 7L,
                        protocolHint = ProtocolHint.OUTLINE,
                    ),
            ),
        )
    }

    @Test
    fun `switching selected option with same protocol requires reconnect`() {
        assertTrue(
            isProfileReconnectRequired(
                activeProfile =
                    profile(
                        id = 7L,
                        protocolHint = ProtocolHint.VLESS,
                        selectedProtocolOptionId = "vless-b",
                        protocolOptions =
                            listOf(
                                option("vless-a", ProtocolHint.VLESS),
                                option("vless-b", ProtocolHint.VLESS),
                            ),
                    ),
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = 7L,
                        protocolHint = ProtocolHint.VLESS,
                        protocolOptionId = "vless-a",
                    ),
            ),
        )
    }

    @Test
    fun `matching selected option keeps reconnect cleared`() {
        assertFalse(
            isProfileReconnectRequired(
                activeProfile =
                    profile(
                        id = 7L,
                        protocolHint = ProtocolHint.VLESS,
                        selectedProtocolOptionId = "vless-a",
                        protocolOptions = listOf(option("vless-a", ProtocolHint.VLESS)),
                    ),
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = 7L,
                        protocolHint = ProtocolHint.VLESS,
                        protocolOptionId = "vless-a",
                    ),
            ),
        )
    }

    @Test
    fun `reconnect in progress owns top dashboard status`() {
        assertEquals(
            ConnectionState.RECONNECTING,
            homeTopStatusState(
                HomeRouteUiState(
                    connection = ConnectionSnapshot(state = ConnectionState.IDLE),
                    reconnectInProgress = true,
                ),
            ),
        )
    }

    private fun profile(
        id: Long,
        protocolHint: ProtocolHint,
        selectedProtocolOptionId: String? = null,
        protocolOptions: List<ProfileProtocolOption> = emptyList(),
    ) = Profile(
        id = id,
        name = "Foxhole",
        sourceType = ProfileSourceType.SUBSCRIPTION_URL,
        secretRef = "secret-$id",
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
        displayName = id,
        protocolHint = protocolHint,
    )
}
