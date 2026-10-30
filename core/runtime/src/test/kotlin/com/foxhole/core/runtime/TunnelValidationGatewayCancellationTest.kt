package com.foxhole.core.runtime

import android.content.ContextWrapper
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.core.runtime.network.IpInfoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TunnelValidationGatewayCancellationTest {
    @Test
    fun `profile resolution cancellation stops the IP refresh before network lookup`() =
        runBlocking {
            val cancellation = CancellationException("profile lookup cancelled")
            var networkLookups = 0
            val gateway =
                TunnelValidationGateway(
                    context = ContextWrapper(null),
                    profileRepository = object : RuntimeProfiles {
                        override suspend fun getResolvedConfig(profileId: Long): String = throw cancellation
                    },
                    settingsRepository = object : RuntimeSettings {
                        override suspend fun current(): Settings = Settings()

                        override suspend fun markDnsFiltersUpdated(timestamp: Long) = Unit

                        override suspend fun markDnsFiltersChecked(timestamp: Long) = Unit
                    },
                    ipInfoRepository = IpInfoRepository(OkHttpClient(), Json),
                    diagnosticsLogger = object : RuntimeDiagnosticsSink {
                        override fun record(tag: String, message: String) = Unit

                        override fun recordStructured(tag: String, headline: String, vararg details: String?) = Unit
                    },
                    snapshot = MutableStateFlow(
                        ConnectionSnapshot(
                            state = ConnectionState.CONNECTED,
                            trafficMode = TrafficMode.TUNNEL,
                            profileId = 42L,
                        ),
                    ),
                    currentVpnNetwork = {
                        networkLookups += 1
                        null
                    },
                    currentUpstreamNetwork = {
                        networkLookups += 1
                        null
                    },
                )

            val failure = runCatching { gateway.refreshIpInfo(IpInfoFetchMode.FULL) }.exceptionOrNull()

            assertSame(cancellation, failure)
            assertEquals(0, networkLookups)
        }
}
