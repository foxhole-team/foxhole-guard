package com.foxhole.guard.runtime

import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StoredProfileProtocolOption
import com.foxhole.core.model.StoredProfileSecret
import com.foxhole.guard.core.data.PreparedProfileConnection
import com.foxhole.guard.core.data.PreparedProfileSessionSnapshot
import com.foxhole.guard.core.data.SubscriptionFetchConnectivityFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class CachedSubscriptionStartupTest {
    @Test
    fun `marked fetch connection failure uses identical validated current cache and fresh settings`() = runBlocking {
        val initial = snapshot()
        val expected = anchor(initial)
        val fresh = initial.copy(
            settings = initial.settings.copy(ui = initial.settings.ui.copy(monochromeEnabled = true))
        )
        val failure = SubscriptionFetchConnectivityFailure(ConnectException("owned fetch connect"))
        var reads = 0
        var used = 0
        val result = prepareSubscriptionStartupWithCache(true, { expected }, { throw failure }, { received ->
            reads++
            assertTrue(expected.matches(received))
            fresh.startupCacheIfCurrent(received, nowMs)
        }, { used++ })
        assertEquals(initial.profile, result.connection.profile)
        assertTrue(result.settings.ui.monochromeEnabled)
        assertEquals(1, reads)
        assertEquals(1, used)
    }

    @Test
    fun `unmarked parsing DNS timeout TLS HTTP and generic failures never read fallback cache`() = runBlocking {
        val expected = anchor(snapshot())
        val errors = listOf(
            UnknownHostException("owned parsing DNS"),
            SocketTimeoutException("owned unphased"),
            SSLHandshakeException("owned certificate"),
            IllegalStateException("owned 401 auth"),
            IllegalArgumentException("owned invalid payload"),
            IOException("owned generic IO")
        )
        errors.forEach { error ->
            var reads = 0
            val failure = runCatching {
                prepareSubscriptionStartupWithCache(true, { expected }, { throw error }, {
                    reads++
                    null
                })
            }.exceptionOrNull()
            assertSame(error, failure)
            assertEquals(0, reads)
        }
    }

    @Test
    fun `initial unknown placeholder and missing config cannot become fallback anchors`() {
        val valid = snapshot()
        assertNull(
            SubscriptionStartupCacheAnchor.capture(
                valid.profile.copy(protocolHint = ProtocolHint.UNKNOWN),
                valid.secret,
                valid.settings,
                null,
                nowMs
            )
        )
        assertNull(
            SubscriptionStartupCacheAnchor.capture(
                valid.profile,
                valid.secret.copy(
                    resolvedConfigJson = "",
                    protocolOptions = emptyList(),
                    selectedProtocolOptionId = null
                ),
                valid.settings,
                null,
                nowMs
            )
        )
    }

    @Test
    fun `marked fetch error without initial valid cache retains original failure`() = runBlocking {
        val primary = SubscriptionFetchConnectivityFailure(ConnectException("owned first import"))
        var reads = 0
        val failure = runCatching {
            prepareSubscriptionStartupWithCache(true, { null }, { throw primary }, {
                reads++
                null
            })
        }.exceptionOrNull()
        assertSame(primary, failure)
        assertEquals(0, reads)
    }

    @Test
    fun `disabled unsupported expired and revoked consent cache stay failclosed`() {
        val valid = snapshot()
        val base = anchor(valid)
        val disabled = valid.copy(secret = valid.secret.copy(protocolOptions = listOf(option.copy(enabled = false))))
        val unsupported = valid.copy(
            secret = valid.secret.copy(protocolOptions = listOf(option.copy(protocolHint = ProtocolHint.UNKNOWN)))
        )
        val expired = valid.copy(profile = valid.profile.copy(subscriptionExpiresAt = nowMs))
        val revoked = valid.copy(
            profile = valid.profile.copy(requiresInsecureTls = true),
            secret = valid.secret.copy(insecureTlsConsentGranted = false)
        )
        listOf(disabled, unsupported, expired, revoked).forEach { assertNull(it.startupCacheIfCurrent(base, nowMs)) }
    }

    @Test
    fun `changed current identity source selection and config cannot reuse earlier cache`() {
        val valid = snapshot()
        val base = anchor(valid)
        val changed = listOf(
            valid.copy(profile = valid.profile.copy(id = 2L)),
            valid.copy(profile = valid.profile.copy(secretRef = "different-owned-ref")),
            valid.copy(secret = valid.secret.copy(subscriptionUrl = "https://different.example/owned")),
            valid.copy(
                secret = valid.secret.copy(
                    protocolOptions = listOf(option.copy(normalizedConfigJson = "owned changed config"))
                )
            ),
            valid.copy(profile = valid.profile.copy(lastUpdatedAt = 2L)),
        )
        changed.forEach { assertNull(it.startupCacheIfCurrent(base, nowMs)) }
    }

    @Test
    fun `current cached validator ordinary rejection does not hide fetch error`() = runBlocking {
        val primary = SubscriptionFetchConnectivityFailure(ConnectException("owned fetch"))
        val failure = runCatching {
            prepareSubscriptionStartupWithCache(
                true,
                { anchor(snapshot()) },
                { throw primary },
                { error("owned invalid cached config") }
            )
        }.exceptionOrNull()
        assertSame(primary, failure)
    }

    @Test
    fun `cancellation and fatal refresh errors preserve identity without fallback`() = runBlocking {
        listOf(
            CancellationException("owned canceled refresh"),
            AssertionError("owned fatal refresh")
        ).forEach { primary ->
            var reads = 0
            val failure = runCatching {
                prepareSubscriptionStartupWithCache(true, { anchor(snapshot()) }, { throw primary }, {
                    reads++
                    null
                })
            }.exceptionOrNull()
            assertSame(primary, failure)
            assertEquals(0, reads)
        }
    }

    @Test
    fun `cancellation and fatal cache validation preserve identity after marked fetch error`() = runBlocking {
        listOf(
            CancellationException("owned canceled validation"),
            AssertionError("owned fatal validation")
        ).forEach { primary ->
            val failure = runCatching {
                prepareSubscriptionStartupWithCache(
                    true,
                    { anchor(snapshot()) },
                    { throw SubscriptionFetchConnectivityFailure(ConnectException("owned fetch")) },
                    { throw primary }
                )
            }.exceptionOrNull()
            assertSame(primary, failure)
        }
    }

    @Test
    fun `successful refresh and prepared refresh mode preserve original result`() = runBlocking {
        val expected = PreparedVpnStartupProfile(PreparedProfileConnection(snapshot().profile, null), Settings())
        var captures = 0
        val result = prepareSubscriptionStartupWithCache(
            false,
            {
                captures++
                null
            },
            { expected },
            { error("must not fallback") }
        )
        assertSame(expected, result)
        assertEquals(0, captures)
    }

    private fun anchor(value: PreparedProfileSessionSnapshot): SubscriptionStartupCacheAnchor =
        checkNotNull(
            SubscriptionStartupCacheAnchor.capture(
                value.profile,
                value.secret,
                value.settings,
                value.protocolOptionId,
                nowMs
            )
        )

    private fun snapshot(): PreparedProfileSessionSnapshot {
        val profile = Profile(
            id = 1L,
            name = "owned",
            sourceType = ProfileSourceType.SUBSCRIPTION_URL,
            secretRef = "owned-ref",
            protocolHint = ProtocolHint.VLESS,
            lastUpdatedAt = 1L,
            lastEtag = null,
            isActive = true
        )
        val secret = StoredProfileSecret(
            subscriptionUrl = "https://subscription.example/owned",
            resolvedConfigJson = validConfig,
            protocolOptions = listOf(option),
            selectedProtocolOptionId = option.id
        )
        return PreparedProfileSessionSnapshot(profile, secret, Settings(), option.id, validConfig)
    }

    private companion object {
        const val nowMs = 1000L
        const val validConfig = "{\"outbounds\":[{\"type\":\"vless\",\"tag\":\"proxy\",\"server\":\"1.1.1.1\",\"server_port\":443,\"uuid\":\"11111111-1111-1111-1111-111111111111\"}],\"route\":{\"final\":\"proxy\"}}"
        val option = StoredProfileProtocolOption(
            id = "owned-option",
            displayName = "owned",
            protocolHint = ProtocolHint.VLESS,
            normalizedConfigJson = validConfig,
            enabled = true
        )
    }
}
