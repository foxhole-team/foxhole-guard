package com.foxhole.beta.core.data

import android.util.Log
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.diagnostics.DiagnosticSanitizer
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.DnsSettings
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.StoredProfileProtocolOption
import com.foxhole.beta.core.model.StoredProfileSecret
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.VpnSession
import com.foxhole.beta.core.model.isUdpTransport
import com.foxhole.beta.core.settings.SettingsRepository
import com.foxhole.beta.vpn.DnsFilterAssetInstaller
import com.foxhole.beta.vpn.FoxholeVpnService
import com.foxhole.beta.vpn.PrivateDnsMode
import com.foxhole.beta.vpn.PrivateDnsState
import com.foxhole.beta.vpn.RuntimeConfigAssembler
import com.foxhole.beta.vpn.TorRuntimeInstaller
import com.foxhole.beta.vpn.withIdentityVersion
import java.util.UUID

internal class ProfileSessionFactory(
    private val settingsRepository: SettingsRepository,
    private val routingRepository: RoutingRepository,
    private val runtimeConfigAssembler: RuntimeConfigAssembler,
    private val torRuntimeInstaller: TorRuntimeInstaller,
    private val dnsFilterAssetInstaller: DnsFilterAssetInstaller,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val profileProvider: suspend (Long) -> Profile,
    private val secretProvider: suspend (String) -> StoredProfileSecret?,
    private val resolvedConfigProvider: suspend (Long, String?) -> String,
) {
    suspend fun getSession(
        profileId: Long,
        protocolOptionIdOverride: String? = null,
        privateDnsMode: PrivateDnsMode? = null,
        privateDnsState: PrivateDnsState? = null,
    ): VpnSession {
        val profile = profileProvider(profileId)
        val secret = secretProvider(profile.secretRef) ?: error("profile secret is missing")
        val selectedOption = secret.selectedStoredProtocolOption(protocolOptionIdOverride)
        val selectedProtocolHint = selectedOption?.protocolHint ?: profile.protocolHint
        val correlationId = newRuntimeCorrelationId()
        val settings = settingsRepository.current()
        val dnsFilterRuntimePaths =
            if (settings.dns.bundledAdGuardFilterEnabled()) {
                dnsFilterAssetInstaller.prepare()
            } else {
                null
            }
        val torRuntimePaths =
            if (settings.shouldPrepareTorRuntime(selectedProtocolHint)) {
                torRuntimeInstaller.prepare().withIdentityVersion(settings.privacyRoute.identityVersion)
            } else {
                null
            }
        val assembled =
            runCatching {
                runtimeConfigAssembler.assemble(
                    baseConfigJson = resolvedConfigProvider(profileId, protocolOptionIdOverride),
                    settings = settings,
                    activePreset = routingRepository.currentPresetForRuntime(),
                    privateDnsMode = privateDnsMode,
                    privateDnsState = privateDnsState,
                    torRuntimePaths = torRuntimePaths,
                    dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                    vpnProtocolHint = selectedProtocolHint,
                )
            }.onFailure { error ->
                diagnosticsLogger.record(
                    "profile",
                    "session build failed sessionId=$correlationId error=${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
                val logMessage = "session build failed sessionId=$correlationId error=${error.javaClass.simpleName}"
                if (BuildConfig.DEBUG) {
                    Log.e(LOG_TAG, logMessage, error)
                } else {
                    Log.e(LOG_TAG, DiagnosticSanitizer.sanitizeForExport(logMessage))
                }
            }.getOrThrow()
        diagnosticsLogger.record("runtime", runtimeConfigAssembler.redactedRuntimeShape(assembled))
        return VpnSession(
            profileId = profile.id,
            profileName = profile.name,
            protocolHint = selectedProtocolHint,
            protocolOptionId = selectedOption?.id,
            configJson = assembled,
            correlationId = correlationId,
        )
    }

    suspend fun getTorOnlySession(
        privateDnsMode: PrivateDnsMode? = null,
        privateDnsState: PrivateDnsState? = null,
    ): VpnSession {
        val settings = settingsRepository.current()
        require(settings.privacyRoute.enabled) { "TOR route is disabled" }
        val correlationId = newRuntimeCorrelationId()
        val dnsFilterRuntimePaths =
            if (settings.dns.bundledAdGuardFilterEnabled()) {
                dnsFilterAssetInstaller.prepare()
            } else {
                null
            }
        val assembled =
            runCatching {
                val torRuntimePaths =
                    torRuntimeInstaller
                        .prepare()
                        .withIdentityVersion(settings.privacyRoute.identityVersion)
                runtimeConfigAssembler.assembleTorOnly(
                    settings = settings,
                    activePreset = routingRepository.currentPresetForRuntime(),
                    privateDnsMode = privateDnsMode,
                    privateDnsState = privateDnsState,
                    torRuntimePaths = torRuntimePaths,
                    dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                )
            }.onFailure { error ->
                diagnosticsLogger.record(
                    "profile",
                    "tor-only session build failed sessionId=$correlationId error=${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
                val logMessage = "tor-only session build failed sessionId=$correlationId error=${error.javaClass.simpleName}"
                if (BuildConfig.DEBUG) {
                    Log.e(LOG_TAG, logMessage, error)
                } else {
                    Log.e(LOG_TAG, DiagnosticSanitizer.sanitizeForExport(logMessage))
                }
            }.getOrThrow()
        return VpnSession(
            profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
            profileName = "TOR",
            protocolHint = ProtocolHint.SING_BOX,
            configJson = assembled,
            correlationId = correlationId,
        )
    }

    private fun Settings.shouldPrepareTorRuntime(selectedProtocolHint: ProtocolHint): Boolean =
        privacyRoute.enabled &&
            traffic.mode == TrafficMode.TUNNEL &&
            (privacyRoute.bypassVpnTunnel || !selectedProtocolHint.isUdpTransport())

    private fun StoredProfileSecret.selectedStoredProtocolOption(
        overrideOptionId: String? = null,
    ): StoredProfileProtocolOption? {
        if (protocolOptions.isEmpty()) {
            return null
        }
        val resolvedOptionId = overrideOptionId?.takeIf(String::isNotBlank) ?: selectedProtocolOptionId
        return protocolOptions.firstOrNull { it.id == resolvedOptionId }
            ?: protocolOptions.firstOrNull()
    }

    private fun newRuntimeCorrelationId(): String =
        "s-" + UUID.randomUUID().toString().replace("-", "").take(12)

    private companion object {
        private const val LOG_TAG = "FoxholeProfileSession"
    }
}

private fun DnsSettings.bundledAdGuardFilterEnabled(): Boolean =
    filteringEnabled && (blockAds || blockTrackers || blockAppTelemetry || blockMaliciousDomains)
