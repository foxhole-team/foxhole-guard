package com.foxhole.guard.runtime

import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StoredProfileSecret
import com.foxhole.guard.core.data.MAX_LOCAL_PROFILE_IMPORT_BYTES
import com.foxhole.guard.core.data.PreparedProfileConnection
import com.foxhole.guard.core.data.PreparedProfileSessionSnapshot
import com.foxhole.guard.core.data.ProfileRepository
import com.foxhole.guard.core.data.SubscriptionFetchConnectivityFailure
import com.foxhole.guard.core.data.localProfileImportByteCount
import com.foxhole.guard.core.data.prepareProfileForConnection
import com.foxhole.guard.core.data.selectedStoredProtocolOptionForRuntime
import com.foxhole.guard.core.data.toResolvedDomainProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PreparedVpnStartupProfile(
    val connection: PreparedProfileConnection,
    val settings: Settings,
)

internal class SubscriptionStartupCacheAnchor private constructor(
    private val profileId: Long,
    private val secretRef: String,
    private val sourceUrl: String,
    private val optionId: String?,
    private val rawConfig: String,
    private val lastUpdatedAt: Long?,
    private val expiresAt: Long?,
) {
    fun matches(other: SubscriptionStartupCacheAnchor): Boolean =
        profileId == other.profileId && secretRef == other.secretRef && sourceUrl == other.sourceUrl &&
            optionId == other.optionId && rawConfig == other.rawConfig && lastUpdatedAt == other.lastUpdatedAt &&
            expiresAt == other.expiresAt

    companion object {
        fun capture(
            profile: Profile,
            secret: StoredProfileSecret,
            settings: Settings,
            requestedOptionId: String?,
            nowMs: Long
        ): SubscriptionStartupCacheAnchor? {
            if (!profile.isLiveCachedSubscription(nowMs)) return null
            val source = secret.subscriptionUrl?.takeIf(String::isNotBlank) ?: return null
            val selected = secret.selectedStoredProtocolOptionForRuntime(requestedOptionId)
            val raw = (selected?.normalizedConfigJson ?: secret.resolvedConfigJson)?.takeIf(String::isNotBlank) ?: return null
            if (!cachedOptionAllowed(selected) || !cachedTlsAllowed(profile, secret, settings, selected) ||
                localProfileImportByteCount(raw) > MAX_LOCAL_PROFILE_IMPORT_BYTES
            ) {
                return null
            }
            return SubscriptionStartupCacheAnchor(
                profile.id,
                profile.secretRef,
                source,
                selected?.id,
                raw,
                profile.lastUpdatedAt,
                profile.subscriptionExpiresAt
            )
        }
    }
}

internal suspend fun prepareSubscriptionStartupWithCache(
    refreshSubscription: Boolean,
    captureAnchor: suspend () -> SubscriptionStartupCacheAnchor?,
    refresh: suspend () -> PreparedVpnStartupProfile,
    captureValidatedCache: suspend (SubscriptionStartupCacheAnchor) -> PreparedVpnStartupProfile?,
    onCacheUsed: () -> Unit = {},
): PreparedVpnStartupProfile {
    currentCoroutineContext().ensureActive()
    val anchor = if (refreshSubscription) cacheOrUnavailable(captureAnchor) else null
    return try {
        refresh()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: SubscriptionFetchConnectivityFailure) {
        val cached = anchor?.let { current -> cacheOrUnavailable { captureValidatedCache(current) } }
            ?: throw error
        currentCoroutineContext().ensureActive()
        onCacheUsed()
        cached
    }
}

private suspend fun <T> cacheOrUnavailable(capture: suspend () -> T?): T? =
    try { capture() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }

internal suspend fun ProfileRepository.prepareVpnStartupProfile(
    profileId: Long,
    requestedOptionId: String?,
    refreshSubscription: Boolean,
    onCacheUsed: () -> Unit,
): PreparedVpnStartupProfile = prepareSubscriptionStartupWithCache(
    refreshSubscription = refreshSubscription,
    captureAnchor = {
        mutationCoordinator.readOwned { owner ->
            owner.secrets(secretMutationMutex) {
                val entity = dao.getById(profileId) ?: return@secrets null
                if (entity.sourceType != ProfileSourceType.SUBSCRIPTION_URL.name) return@secrets null
                val secret = secretStore.read(entity.secretRef) ?: return@secrets null
                SubscriptionStartupCacheAnchor.capture(
                    entity.toResolvedDomainProfile(secret, json),
                    secret,
                    settingsRepository.current(),
                    requestedOptionId,
                    System.currentTimeMillis()
                )
            }
        }
    },
    refresh = {
        val prepared = prepareProfileForConnection(profileId, requestedOptionId, refreshSubscription)
        PreparedVpnStartupProfile(prepared, settingsRepository.current())
    },
    captureValidatedCache = { expected ->
        val snapshot = prepareSessionSnapshot(profileId, requestedOptionId)
        snapshot.startupCacheIfCurrent(expected)
    },
    onCacheUsed = onCacheUsed,
)

internal fun PreparedProfileSessionSnapshot.startupCacheIfCurrent(expected: SubscriptionStartupCacheAnchor, nowMs: Long = System.currentTimeMillis()): PreparedVpnStartupProfile? {
    if (resolvedConfig.isBlank()) return null
    val current = SubscriptionStartupCacheAnchor.capture(profile, secret, settings, protocolOptionId, nowMs) ?: return null
    if (!expected.matches(current)) return null
    return PreparedVpnStartupProfile(PreparedProfileConnection(profile, protocolOptionId), settings)
}

private fun Profile.isLiveCachedSubscription(nowMs: Long): Boolean {
    if (sourceType != ProfileSourceType.SUBSCRIPTION_URL || protocolHint == ProtocolHint.UNKNOWN) return false
    val expiry = subscriptionExpiresAt
    return expiry == null || expiry > nowMs
}

private fun cachedOptionAllowed(option: com.foxhole.core.model.StoredProfileProtocolOption?): Boolean =
    option == null || (option.enabled && option.protocolHint != ProtocolHint.UNKNOWN)

private fun cachedTlsAllowed(
    profile: Profile,
    secret: StoredProfileSecret,
    settings: Settings,
    option: com.foxhole.core.model.StoredProfileProtocolOption?,
): Boolean {
    val required = profile.requiresInsecureTls || option?.requiresInsecureTls == true
    val allowed = settings.expert.allowInsecureTls || secret.insecureTlsConsentGranted == true
    return !required || allowed
}
