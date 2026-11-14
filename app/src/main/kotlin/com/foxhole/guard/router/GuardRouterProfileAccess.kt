package com.foxhole.guard.router

import com.foxhole.core.model.Profile
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StoredProfileProtocolOption
import com.foxhole.core.model.StoredProfileSecret
import com.foxhole.guard.core.data.ProfileRepository
import com.foxhole.guard.core.data.allowsInsecureTlsForStoredProfileRuntime
import com.foxhole.guard.core.data.normalizeLegacyRawResolvedConfig
import com.foxhole.guard.core.data.selectedStoredProtocolOptionForRuntime
import com.foxhole.guard.core.data.toResolvedDomainProfile

internal class GuardRouterProfileCapture(val profile: Profile, val secret: StoredProfileSecret,
    val settings: Settings, val selected: StoredProfileProtocolOption?, val rawConfig: String,
    val materialGeneration: String? = null) {
    override fun toString(): String = "GuardRouterProfileCapture"
}

internal interface GuardRouterProfileAccess {
    suspend fun capture(id: Long): GuardRouterProfileCapture
    suspend fun captureOption(id: Long, optionId: String?): GuardRouterProfileCapture {
        val captured = capture(id)
        val selected = if (captured.secret.protocolOptions.isEmpty()) {
            if (optionId != null) throw RouterApiFailure(RouterApiError.UNSUPPORTED)
            null
        } else captured.secret.protocolOptions.singleOrNull { it.id == optionId && it.enabled }
            ?: throw RouterApiFailure(RouterApiError.UNSUPPORTED)
        val raw = selected?.normalizedConfigJson ?: captured.secret.resolvedConfigJson
            ?: throw RouterApiFailure(RouterApiError.UNSUPPORTED)
        return GuardRouterProfileCapture(captured.profile, captured.secret, captured.settings, selected, raw, captured.materialGeneration)
    }
    fun resolve(capture: GuardRouterProfileCapture): String
    suspend fun isCurrent(capture: GuardRouterProfileCapture): Boolean
    suspend fun importProfile(value: String)
    suspend fun importProfileCapture(value: String): GuardRouterProfileCapture? { importProfile(value); return null }
}

internal class GuardRouterRepositoryProfileAccess(private val repository: ProfileRepository) : GuardRouterProfileAccess {
    override suspend fun capture(id: Long): GuardRouterProfileCapture = capture(id, null, false)
    override suspend fun captureOption(id: Long, optionId: String?): GuardRouterProfileCapture = capture(id, optionId, true)

    private suspend fun capture(id: Long, optionId: String?, specific: Boolean): GuardRouterProfileCapture = repository.mutationCoordinator.readCommitted {
        val entity = repository.dao.getById(id) ?: unavailable()
        val secret = repository.secretStore.read(entity.secretRef) ?: unavailable()
        val selected = if (!specific) secret.selectedStoredProtocolOptionForRuntime()
            else if (secret.protocolOptions.isEmpty()) { if (optionId != null) unavailable(); null }
            else secret.protocolOptions.singleOrNull { it.id == optionId && it.enabled } ?: unavailable()
        if (secret.protocolOptions.isNotEmpty() && (selected == null || !selected.enabled)) unavailable()
        val config = selected?.normalizedConfigJson ?: secret.resolvedConfigJson ?: unavailable()
        if (config.isBlank()) unavailable()
        GuardRouterProfileCapture(entity.toResolvedDomainProfile(secret, repository.json), secret,
            repository.settingsRepository.current(), selected, config, entity.secretRef)
    }

    override fun resolve(capture: GuardRouterProfileCapture): String {
        val allowInsecure = allowsInsecureTlsForStoredProfileRuntime(capture.settings.expert.allowInsecureTls, capture.secret)
        val repaired = repository.parser.normalizeLegacyRawResolvedConfig(capture.rawConfig, capture.settings, allowInsecure)
            ?: capture.rawConfig
        return repository.parser.sanitizeResolvedConfig(raw = repaired,
            allowPrivateOutboundHosts = capture.settings.expert.allowPrivateOutboundHosts, allowInsecureTls = allowInsecure)
    }

    override suspend fun isCurrent(capture: GuardRouterProfileCapture): Boolean = repository.mutationCoordinator.readCommitted {
        val entity = repository.dao.getById(capture.profile.id) ?: return@readCommitted false
        val secret = repository.secretStore.read(entity.secretRef) ?: return@readCommitted false
        val settings = repository.settingsRepository.current()
        entity.toResolvedDomainProfile(secret, repository.json) == capture.profile && secret == capture.secret &&
            settings.expert.allowPrivateOutboundHosts == capture.settings.expert.allowPrivateOutboundHosts &&
            settings.expert.allowInsecureTls == capture.settings.expert.allowInsecureTls
    }

    override suspend fun importProfile(value: String) { repository.importProfile(value, allowInsecureTlsForProfile = false) }
    override suspend fun importProfileCapture(value: String): GuardRouterProfileCapture {
        val imported = repository.importProfile(value, allowInsecureTlsForProfile = false)
        return capture(imported.id)
    }
    private fun unavailable(): Nothing = throw RouterApiFailure(RouterApiError.UNSUPPORTED)
}
