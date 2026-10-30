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
    val settings: Settings, val selected: StoredProfileProtocolOption?, val rawConfig: String) {
    override fun toString(): String = "GuardRouterProfileCapture"
}

internal interface GuardRouterProfileAccess {
    suspend fun capture(id: Long): GuardRouterProfileCapture
    fun resolve(capture: GuardRouterProfileCapture): String
    suspend fun isCurrent(capture: GuardRouterProfileCapture): Boolean
    suspend fun importProfile(value: String)
    suspend fun importProfileCapture(value: String): GuardRouterProfileCapture? { importProfile(value); return null }
}

internal class GuardRouterRepositoryProfileAccess(private val repository: ProfileRepository) : GuardRouterProfileAccess {
    override suspend fun capture(id: Long): GuardRouterProfileCapture = repository.mutationCoordinator.readCommitted {
        val entity = repository.dao.getById(id) ?: unavailable()
        val secret = repository.secretStore.read(entity.secretRef) ?: unavailable()
        val selected = secret.selectedStoredProtocolOptionForRuntime()
        if (secret.protocolOptions.isNotEmpty() && (selected == null || !selected.enabled)) unavailable()
        val config = selected?.normalizedConfigJson ?: secret.resolvedConfigJson ?: unavailable()
        if (config.isBlank()) unavailable()
        GuardRouterProfileCapture(entity.toResolvedDomainProfile(secret, repository.json), secret,
            repository.settingsRepository.current(), selected, config)
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
