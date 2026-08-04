package com.foxhole.guard.core.data
import android.util.Log
import com.foxhole.core.model.DiagnosticSanitizer
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StoredProfileSecret
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.VpnSession
import com.foxhole.core.model.disableUnverifiedRuleSetRuntimeDns
import com.foxhole.core.model.isUdpTransport
import com.foxhole.core.runtime.DnsFilterRuntimePaths
import com.foxhole.core.runtime.FoxCoreConfigTranslator
import com.foxhole.core.runtime.I2pdManager
import com.foxhole.core.runtime.PrivateDnsMode
import com.foxhole.core.runtime.PrivateDnsState
import com.foxhole.core.runtime.RuntimeConfigAssembler
import com.foxhole.core.runtime.TorRuntimeInstaller
import com.foxhole.core.runtime.i2pRuntimeActive
import com.foxhole.core.runtime.isTorPrivacyRouteActive
import com.foxhole.core.runtime.torBridgePolicy
import com.foxhole.core.runtime.withIdentityVersion
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.core.diagnostics.DiagnosticsLogger
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.runtime.DnsFilterAssetInstaller
import com.foxhole.guard.runtime.FoxholeVpnService
import kotlinx.coroutines.CancellationException
import java.util.UUID

internal class ProfileSessionFactory(
    private val settingsRepository: SettingsRepository,
    private val routingRepository: RoutingRepository,
    private val runtimeConfigAssembler: RuntimeConfigAssembler,
    private val torRuntimeInstaller: TorRuntimeInstaller,
    private val i2pdManager: I2pdManager,
    private val dnsFilterAssetInstaller: DnsFilterAssetInstaller,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val profileProvider: suspend (Long) -> Profile,
    private val secretProvider: suspend (String) -> StoredProfileSecret?,
    private val resolvedConfigProvider: suspend (Long, String?) -> String,
) {
    private val foxCoreConfigTranslator = FoxCoreConfigTranslator()

    suspend fun getSession(
        profileId: Long,
        protocolOptionIdOverride: String? = null,
        privateDnsMode: PrivateDnsMode? = null,
        privateDnsState: PrivateDnsState? = null,
        deferTorRoute: Boolean = false,
    ): VpnSession {
        val profile = profileProvider(profileId)
        val secret = secretProvider(profile.secretRef) ?: error("profile secret is missing")
        val selectedOption = secret.selectedStoredProtocolOptionForRuntime(protocolOptionIdOverride)
        val selectedProtocolHint = selectedOption?.protocolHint ?: profile.protocolHint
        val correlationId = newRuntimeCorrelationId()
        val settings = settingsRepository.current()
        val dnsFilterRuntimePaths = settings.prepareVerifiedDnsFilterRuntimePaths()
        val baseRuntimeSettings = settings.disableUnverifiedDnsRuleSetFiltering(dnsFilterRuntimePaths)
        // VPN-first startup ordering: the first config of a Tor-in-VPN session is assembled with
        // the Tor route switched off; the service hot-reloads the full config once the tunnel
        // validates. The persisted settings are untouched - only this session build is stripped.
        val runtimeSettings =
            if (deferTorRoute && baseRuntimeSettings.privacyRoute.enabled) {
                diagnosticsLogger.record(
                    "runtime",
                    "tor route deferred for vpn-first startup sessionId=$correlationId",
                )
                baseRuntimeSettings.copy(
                    privacyRoute = baseRuntimeSettings.privacyRoute.copy(mode = PrivacyRouteMode.OFF),
                )
            } else {
                baseRuntimeSettings
            }
        val torRuntimePaths =
            if (runtimeSettings.shouldPrepareTorRuntime(selectedProtocolHint)) {
                torRuntimeInstaller
                    .prepare(runtimeSettings.privacyRoute.torBridgePolicy())
                    .withIdentityVersion(runtimeSettings.privacyRoute.identityVersion)
            } else {
                null
            }
        val i2pSocksPort = startI2pProxyPortOrNull(runtimeSettings)
        val assembled =
            runCatching {
                runtimeConfigAssembler.assemble(
                    baseConfigJson = resolvedConfigProvider(profileId, protocolOptionIdOverride),
                    settings = runtimeSettings,
                    activePreset = routingRepository.currentPresetForRuntime(),
                    privateDnsMode = privateDnsMode,
                    privateDnsState = privateDnsState,
                    torRuntimePaths = torRuntimePaths,
                    dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                    vpnProtocolHint = selectedProtocolHint,
                    i2pSocksPort = i2pSocksPort,
                )
            }.onFailure { error ->
                // A cancelled build (mode switch, user stop mid-start) is not a failure; an
                // E-level "build failed" here sends debugging down the wrong trail.
                if (error is CancellationException) {
                    diagnosticsLogger.record("profile", "session build cancelled sessionId=$correlationId")
                    throw error
                }
                diagnosticsLogger.record(
                    "profile",
                    "session build failed sessionId=$correlationId error=${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
                val logMessage = "session build failed sessionId=$correlationId error=${error.javaClass.simpleName}"
                if (BuildConfig.DEBUG) {
                    Log.e(LOG_TAG, "$logMessage\n${sanitizedDiagnosticStackTrace(error)}")
                } else {
                    Log.e(LOG_TAG, DiagnosticSanitizer.sanitizeForExport(logMessage))
                }
            }.getOrThrow()
        diagnosticsLogger.record("runtime", runtimeConfigAssembler.redactedRuntimeShape(assembled))
        val session = VpnSession(
            profileId = profile.id,
            profileName = profile.name,
            protocolHint = selectedProtocolHint,
            protocolOptionId = selectedOption?.id,
            configJson = assembled,
            correlationId = correlationId,
            torActive = runtimeSettings.isTorPrivacyRouteActive(selectedProtocolHint),
        )
        return session.copy(
            foxCoreConfig =
            foxCoreConfigTranslator.translate(
                session = session,
                dnsRuleSetBootstrap = dnsFilterRuntimePaths?.foxCoreBootstrap,
            ),
        )
    }

    suspend fun getTorOnlySession(
        privateDnsMode: PrivateDnsMode? = null,
        privateDnsState: PrivateDnsState? = null,
    ): VpnSession {
        val settings = settingsRepository.current()
        require(settings.privacyRoute.enabled) { "TOR route is disabled" }
        val correlationId = newRuntimeCorrelationId()
        val dnsFilterRuntimePaths = settings.prepareVerifiedDnsFilterRuntimePaths()
        val runtimeSettings = settings.disableUnverifiedDnsRuleSetFiltering(dnsFilterRuntimePaths)
        val i2pSocksPort = startI2pProxyPortOrNull(runtimeSettings)
        val assembled =
            runCatching {
                val torRuntimePaths =
                    torRuntimeInstaller
                        .prepare(runtimeSettings.privacyRoute.torBridgePolicy())
                        .withIdentityVersion(runtimeSettings.privacyRoute.identityVersion)
                runtimeConfigAssembler.assembleTorOnly(
                    settings = runtimeSettings,
                    activePreset = routingRepository.currentPresetForRuntime(),
                    privateDnsMode = privateDnsMode,
                    privateDnsState = privateDnsState,
                    torRuntimePaths = torRuntimePaths,
                    dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                    i2pSocksPort = i2pSocksPort,
                )
            }.onFailure { error ->
                // Same cancellation transparency as getSession: a JobCancellationException here
                // is a torn-down start, not a broken Tor runtime.
                if (error is CancellationException) {
                    diagnosticsLogger.record("profile", "tor-only session build cancelled sessionId=$correlationId")
                    throw error
                }
                diagnosticsLogger.record(
                    "profile",
                    "tor-only session build failed sessionId=$correlationId error=${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
                val logMessage = "tor-only session build failed sessionId=$correlationId error=${error.javaClass.simpleName}"
                if (BuildConfig.DEBUG) {
                    Log.e(LOG_TAG, "$logMessage\n${sanitizedDiagnosticStackTrace(error)}")
                } else {
                    Log.e(LOG_TAG, DiagnosticSanitizer.sanitizeForExport(logMessage))
                }
            }.getOrThrow()
        diagnosticsLogger.record("runtime", runtimeConfigAssembler.redactedRuntimeShape(assembled))
        val session = VpnSession(
            profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
            profileName = "TOR",
            protocolHint = ProtocolHint.TOR,
            configJson = assembled,
            correlationId = correlationId,
            torActive = true,
        )
        return session.copy(
            foxCoreConfig =
            foxCoreConfigTranslator.translate(
                session = session,
                dnsRuleSetBootstrap = dnsFilterRuntimePaths?.foxCoreBootstrap,
            ),
        )
    }

    // Starts i2pd when the independent I2P toggle is on and returns its local SOCKS port for the
    // `.i2p` outbound; a failed start never fails the VPN — the session just assembles without i2p.
    private suspend fun startI2pProxyPortOrNull(settings: Settings): Int? {
        if (!settings.i2pRuntimeActive()) {
            // Turning I2P off (or moving to a mode that does not need it) rebuilds the config
            // WITHOUT the I2P leg, but nothing used to tear the already-running child down — so
            // libi2pd.so kept running long after the switch, visible only as a stray process. The
            // full teardown reaps it, but a settings toggle under a live tunnel never gets there.
            // stop() is a safe no-op when nothing is running.
            i2pdManager.stop()
            return null
        }
        return runCatching { i2pdManager.ensureStarted(settings.i2p).socksPort }
            .onFailure { error ->
                if (error is CancellationException) throw error
                diagnosticsLogger.record("i2pd", "i2pd start skipped: ${error.message ?: error.javaClass.simpleName}")
            }.getOrNull()
    }

    private fun Settings.shouldPrepareTorRuntime(selectedProtocolHint: ProtocolHint): Boolean =
        privacyRoute.enabled &&
            traffic.mode == TrafficMode.TUNNEL &&
            (privacyRoute.bypassVpnTunnel || !selectedProtocolHint.isUdpTransport())

    private suspend fun Settings.prepareVerifiedDnsFilterRuntimePaths(): DnsFilterRuntimePaths? {
        if (!dns.filteringEnabled) {
            return null
        }
        return dnsFilterAssetInstaller.prepareVerifiedOrNull()
            ?: run {
                diagnosticsLogger.record("dns", "dns rule-set runtime disabled: verified filter unavailable")
                null
            }
    }

    private fun Settings.disableUnverifiedDnsRuleSetFiltering(
        dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
    ): Settings =
        if (dns.filteringEnabled && dnsFilterRuntimePaths == null) {
            copy(dns = dns.disableUnverifiedRuleSetRuntimeDns())
        } else {
            this
        }

    private fun newRuntimeCorrelationId(): String =
        "s-" + UUID.randomUUID().toString().replace("-", "").take(12)

    private companion object {
        private const val LOG_TAG = "FoxholeProfileSession"
    }
}

/** Keeps debug call stacks useful without writing exception-borne URLs, hosts or profile keys. */
internal fun sanitizedDiagnosticStackTrace(error: Throwable): String =
    buildString {
        append(error.javaClass.name)
        error.message?.takeIf(String::isNotBlank)?.let { message ->
            append(": ")
            append(DiagnosticSanitizer.sanitizeForExport(message))
        }
        error.stackTrace.take(MAX_DIAGNOSTIC_STACK_FRAMES).forEach { frame ->
            append("\n\tat ")
            append(frame.className)
            append('.')
            append(frame.methodName)
            append('(')
            append(frame.fileName ?: "Unknown Source")
            frame.lineNumber.takeIf { line -> line >= 0 }?.let { line ->
                append(':')
                append(line)
            }
            append(')')
        }
        error.cause?.takeIf { cause -> cause !== error }?.let { cause ->
            append("\nCaused by: ")
            append(cause.javaClass.name)
            cause.message?.takeIf(String::isNotBlank)?.let { message ->
                append(": ")
                append(DiagnosticSanitizer.sanitizeForExport(message))
            }
        }
    }

private const val MAX_DIAGNOSTIC_STACK_FRAMES = 64
