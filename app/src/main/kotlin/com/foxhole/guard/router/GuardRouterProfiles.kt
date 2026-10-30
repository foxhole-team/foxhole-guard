package com.foxhole.guard.router

import com.foxhole.core.importer.exportPortableConfigs
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.network.ensurePublicSubscriptionUrl
import com.foxhole.guard.core.data.ProfileRepository
import com.foxhole.guard.router.profile.VpnProfileImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class GuardRouterProfileSummary(
    val id: String,
    val name: String,
    val protocol: String,
    val refreshable: Boolean = false,
    val protocolOptions: List<GuardRouterProtocolSummary> = emptyList(),
    val selectedProtocolOptionId: String? = null,
    val subscriptionExpiresAt: Long? = null,
)

internal data class GuardRouterProtocolSummary(val id: String, val name: String, val protocol: String)

internal fun Profile.routerSummary() = GuardRouterProfileSummary(
    id = id.toString(), name = name, protocol = protocolHint.name,
    refreshable = sourceType == ProfileSourceType.SUBSCRIPTION_URL,
    protocolOptions = protocolOptions.filter { it.enabled }.map { GuardRouterProtocolSummary(it.id, it.displayName, it.protocolHint.name) },
    selectedProtocolOptionId = selectedProtocolOptionId, subscriptionExpiresAt = subscriptionExpiresAt,
)

internal class GuardRouterProfiles(private val access: GuardRouterProfileAccess, private val pairings: EncryptedRouterPairingStore? = null) {
    constructor(repository: ProfileRepository, pairings: EncryptedRouterPairingStore? = null) : this(GuardRouterRepositoryProfileAccess(repository), pairings)
    suspend fun selected(profileId: String): RouterProfileSelection = withContext(Dispatchers.IO) {
        val id = profileId.toLongOrNull()?.takeIf { it > 0 } ?: unsupported()
        val capture = access.capture(id)
        val before = capture.profile
        val secret = capture.secret
        val option = capture.selected
        val config = access.resolve(capture)
        val selected = exportPortableConfigs(before.name, listOf(config))
        if (!access.isCurrent(capture)) throw RouterApiFailure(RouterApiError.CONFLICT)
        val source = secret.subscriptionUrl?.takeIf { before.sourceType == ProfileSourceType.SUBSCRIPTION_URL }
            ?.also { it.ensurePublicSubscriptionUrl(resolveHost = false) }
        val protocol = (option?.protocolHint ?: before.protocolHint).name.let {
            if (it == "OUTLINE") "shadowsocks" else it.lowercase(java.util.Locale.ROOT)
        }
        RouterProfileSelection(profileId, before.name, protocol, option?.displayName ?: before.name, option?.id, source, selected)
    }

    suspend fun importProfile(value: String): Boolean = withContext(Dispatchers.IO) {
        val importer = VpnProfileImporter()
        // Router imports do not grant TLS consent or change Guard's existing import policy.
        if (importer.subscriptionUrlOrNull(value, allowInsecureTls = false) == null) importer.import(value, allowInsecureTls = false)
        access.importProfile(value)
        true
    }

    suspend fun importAuthenticated(value: RouterAuthenticatedProfileImport): Boolean = withContext(Dispatchers.IO) {
        val store = pairings ?: throw RouterApiFailure(RouterApiError.UNAVAILABLE)
        val saved = store.read() ?: throw RouterApiFailure(RouterApiError.CONFLICT)
        try {
            if (saved.id != value.marker.pairingId || saved.identity != value.identity || !saved.active ||
                !value.marker.matches(value.sourceUri, saved.id)) throw RouterApiFailure(RouterApiError.CONFLICT)
        } finally { saved.clear() }
        VpnProfileImporter().import(value.content, allowInsecureTls = false)
        val capture = access.importProfileCapture(value.content) ?: throw RouterApiFailure(RouterApiError.UNAVAILABLE)
        if (!access.isCurrent(capture)) throw RouterApiFailure(RouterApiError.CONFLICT)
        store.trustProfile(value.identity, RouterTrustedProfile(capture.profile.id.toString(), routerProfileDigest(capture.rawConfig),
            value.identity.certificateSha256, value.marker))
        access.isCurrent(capture)
    }

    suspend fun connectedProfile(profileId: Long, optionId: String?, generation: String, networkHandle: Long): RouterConnectedProfile? {
        val capture = access.capture(profileId)
        if (optionId != null && optionId != capture.selected?.id || !access.isCurrent(capture)) return null
        return RouterConnectedProfile(profileId.toString(), routerProfileDigest(capture.rawConfig), generation, networkHandle)
    }

    private fun unsupported(): Nothing = throw RouterApiFailure(RouterApiError.UNSUPPORTED)
}
