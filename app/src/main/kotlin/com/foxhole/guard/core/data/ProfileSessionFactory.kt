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
import com.foxhole.core.runtime.I2pdEndpoints
import com.foxhole.core.runtime.I2pdManager
import com.foxhole.core.runtime.PrivateDnsMode
import com.foxhole.core.runtime.PrivateDnsState
import com.foxhole.core.runtime.RuntimeConfigAssembler
import com.foxhole.core.runtime.TorRuntimeInstaller
import com.foxhole.core.runtime.appliedTorRouteOrNull
import com.foxhole.core.runtime.i2pRuntimeActive
import com.foxhole.core.runtime.isTorPrivacyRouteActive
import com.foxhole.core.runtime.requireI2pPrivateDnsCompatibility
import com.foxhole.core.runtime.torBridgePolicy
import com.foxhole.core.runtime.withIdentityVersion
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.core.diagnostics.DiagnosticsLogger
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.core.settings.ensureNewAppQuarantineBaseline
import com.foxhole.guard.runtime.DnsFilterAssetInstaller
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.runtime.forProtocolTestTrafficFreeze
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
        protocolTestTrafficFreeze: Boolean = false,
    ): VpnSession {
        val profile = profileProvider(profileId)
        val secret = secretProvider(profile.secretRef) ?: error("profile secret is missing")
        val selectedOption = secret.selectedStoredProtocolOptionForRuntime(protocolOptionIdOverride)
        val selectedProtocolHint = selectedOption?.protocolHint ?: profile.protocolHint
        val correlationId = newRuntimeCorrelationId()
        val settings = settingsRepository.ensureNewAppQuarantineBaseline()
        val dnsFilterRuntimePaths = settings.prepareVerifiedDnsFilterRuntimePaths()
        val baseRuntimeSettings =
            settings
                .disableUnverifiedDnsRuleSetFiltering(dnsFilterRuntimePaths)
                .forProtocolTestTrafficFreeze(protocolTestTrafficFreeze)

        val requestedRuntimeSettings =
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
        val runtimeSettings = requestedRuntimeSettings.withSupportedI2pLane(selectedProtocolHint)
        val activePreset = routingRepository.currentPresetForRuntime()
        requireI2pPrivateDnsCompatibility(
            i2pActive = runtimeSettings.i2pRuntimeActive(),
            privateDnsMode = privateDnsState?.mode ?: privateDnsMode,
        )
        val knownApplications = runtimeSettings.knownApplicationsForNativeQuarantine()
        val torRuntimePaths =
            if (runtimeSettings.shouldPrepareTorRuntime(selectedProtocolHint)) {
                torRuntimeInstaller
                    .prepare(runtimeSettings.privacyRoute.torBridgePolicy())
                    .withIdentityVersion(runtimeSettings.privacyRoute.identityVersion)
            } else {
                null
            }
        val i2pEndpoint = startI2pEndpointOrNull(runtimeSettings)
        val assembled =
            runCatching {
                runtimeConfigAssembler.assemble(
                    baseConfigJson = resolvedConfigProvider(profileId, protocolOptionIdOverride),
                    settings = runtimeSettings,
                    activePreset = activePreset,
                    privateDnsMode = privateDnsMode,
                    privateDnsState = privateDnsState,
                    torRuntimePaths = torRuntimePaths,
                    dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                    vpnProtocolHint = selectedProtocolHint,
                    i2pSocksPort = i2pEndpoint?.socksPort,
                    protocolTestTrafficFreeze = protocolTestTrafficFreeze,
                )
            }.onFailure { error ->

                if (error is CancellationException) {
                    diagnosticsLogger.record("profile", "session build cancelled sessionId=$correlationId")
                    throw error
                }
                diagnosticsLogger.recordFailure(
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
            appliedTorRoute = runtimeSettings.appliedTorRouteOrNull(selectedProtocolHint),
            quarantineNewApps = runtimeSettings.expert.newAppQuarantineEnabled,
            knownApplications = knownApplications,
            runtimeConfigFingerprint =
            runtimeConfigAssembler.runtimeFingerprint(
                settings = runtimeSettings,
                activePreset = activePreset,
                privateDnsState = privateDnsState,
                privateDnsMode = privateDnsMode,
                protocolTestTrafficFreeze = protocolTestTrafficFreeze,
            ),
            quarantinePolicyRevision = runtimeSettings.expert.quarantinePolicyRevision,
            i2pEndpointGeneration = i2pEndpoint?.generation,
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
        val settings = settingsRepository.ensureNewAppQuarantineBaseline()
        require(settings.privacyRoute.permitted && settings.privacyRoute.enabled) { "TOR route is disabled" }
        val correlationId = newRuntimeCorrelationId()
        val dnsFilterRuntimePaths = settings.prepareVerifiedDnsFilterRuntimePaths()
        val runtimeSettings = settings.disableUnverifiedDnsRuleSetFiltering(dnsFilterRuntimePaths)
        requireI2pPrivateDnsCompatibility(
            i2pActive = runtimeSettings.i2pRuntimeActive(),
            privateDnsMode = privateDnsState?.mode ?: privateDnsMode,
        )
        val knownApplications = runtimeSettings.knownApplicationsForNativeQuarantine()
        val activePreset = routingRepository.currentPresetForRuntime()
        val i2pEndpoint = startI2pEndpointOrNull(runtimeSettings)
        val assembled =
            runCatching {
                val torRuntimePaths =
                    torRuntimeInstaller
                        .prepare(runtimeSettings.privacyRoute.torBridgePolicy())
                        .withIdentityVersion(runtimeSettings.privacyRoute.identityVersion)
                runtimeConfigAssembler.assembleTorOnly(
                    settings = runtimeSettings,
                    activePreset = activePreset,
                    privateDnsMode = privateDnsMode,
                    privateDnsState = privateDnsState,
                    torRuntimePaths = torRuntimePaths,
                    dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                    i2pSocksPort = i2pEndpoint?.socksPort,
                )
            }.onFailure { error ->

                if (error is CancellationException) {
                    diagnosticsLogger.record("profile", "tor-only session build cancelled sessionId=$correlationId")
                    throw error
                }
                diagnosticsLogger.recordFailure(
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
            appliedTorRoute = runtimeSettings.appliedTorRouteOrNull(ProtocolHint.TOR),
            quarantineNewApps = runtimeSettings.expert.newAppQuarantineEnabled,
            knownApplications = knownApplications,
            runtimeConfigFingerprint =
            runtimeConfigAssembler.runtimeFingerprint(
                settings = runtimeSettings,
                activePreset = activePreset,
                privateDnsState = privateDnsState,
                privateDnsMode = privateDnsMode,
            ),
            quarantinePolicyRevision = runtimeSettings.expert.quarantinePolicyRevision,
            i2pEndpointGeneration = i2pEndpoint?.generation,
        )
        return session.copy(
            foxCoreConfig =
            foxCoreConfigTranslator.translate(
                session = session,
                dnsRuleSetBootstrap = dnsFilterRuntimePaths?.foxCoreBootstrap,
            ),
        )
    }

    private suspend fun startI2pEndpointOrNull(settings: Settings): I2pdEndpoints? {
        if (!settings.i2pRuntimeActive()) {
            i2pdManager.stop()
            return null
        }
        return runCatching { i2pdManager.ensureStarted(settings.i2p) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                diagnosticsLogger.recordFailure("i2pd", "i2pd start failed")
            }.getOrThrow()
    }

    private suspend fun Settings.withSupportedI2pLane(protocolHint: ProtocolHint): Settings {
        if (!i2pRuntimeActive() || protocolHint != ProtocolHint.WIREGUARD) {
            return this
        }

        i2pdManager.stop()
        diagnosticsLogger.record(
            "i2pd",
            "I2P lane unavailable for packet-tunnel profile; VPN continues fail-closed",
        )
        return copy(i2p = i2p.copy(engaged = false))
    }

    private fun Settings.shouldPrepareTorRuntime(selectedProtocolHint: ProtocolHint): Boolean =
        privacyRoute.permitted &&
            privacyRoute.enabled &&
            traffic.mode == TrafficMode.TUNNEL &&
            (privacyRoute.bypassVpnTunnel || !selectedProtocolHint.isUdpTransport())

    private fun Settings.knownApplicationsForNativeQuarantine() =
        if (expert.newAppQuarantineEnabled) expert.quarantineKnownApplications else emptyList()

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
