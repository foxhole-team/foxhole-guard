package com.foxhole.guard.runtime

import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.RuntimeCommand
import com.foxhole.core.runtime.RuntimeCommandSource
import com.foxhole.guard.router.RouterSmartPause

internal data class RouterOwnedResume(val token: String, val profileId: Long, val protocolOptionId: String?,
    val requestGeneration: Long, val authorityRevision: Long) {
    override fun toString(): String = "RouterOwnedResume"
}
internal fun validRouterSmartToken(value: String): Boolean = value.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
internal fun routerOwnedResumeCommand(): RuntimeCommand =
    RuntimeCommand.Reload(reason = "router_smart_owned_start", source = RuntimeCommandSource.SYSTEM)

internal fun routerSmartVpnOnly(settings: Settings, session: VpnSession?): Boolean =
    settings.traffic.mode == TrafficMode.TUNNEL && !settings.privacyRoute.enabled && !settings.i2p.enabled &&
        !settings.expert.firewallEnabled && !settings.statistics.dnsFilteringEnabled &&
        session != null && session.profileId > 0 && !session.torActive && session.appliedTorRoute == null &&
        session.i2pEndpointGeneration == null && !session.quarantineNewApps && session.runtimeConfigFingerprint != null

internal fun routerSmartResumeOwnerMatches(expected: RouterOwnedResume, supplied: RouterOwnedResume,
    requestGeneration: Long, selectedProfile: Long?, materialGeneration: String?, expectedMaterialGeneration: String,
    selectionGeneration: Long, expectedSelectionGeneration: Long, authorityRevision: Long,
    idle: Boolean, cellular: Boolean, anotherVpn: Boolean, consent: Boolean): Boolean =
    expected == supplied && validRouterSmartToken(supplied.token) && supplied.profileId > 0 &&
        supplied.requestGeneration == requestGeneration && supplied.profileId == selectedProfile &&
        materialGeneration == expectedMaterialGeneration && selectionGeneration == expectedSelectionGeneration &&
        supplied.authorityRevision == authorityRevision && idle && cellular && !anotherVpn && consent

internal class RouterSmartGuardState(val pause: RouterSmartPause, val stop: RouterOwnedDisconnect,
    val settings: Settings, val session: VpnSession, val materialGeneration: String, val selectionGeneration: Long) {
    @Volatile var acknowledged = false
    @Volatile var queued: RouterOwnedResume? = null
    @Volatile var prepared: RouterSmartPreparedStart? = null
    @Volatile var authority: (() -> Boolean)? = null
    override fun toString(): String = "RouterSmartGuardState"
}
internal class RouterSmartPreparedStart(val owner: RouterOwnedResume, val settings: Settings, val session: VpnSession,
    val privateDnsState: com.foxhole.core.runtime.PrivateDnsState) {
    override fun toString(): String = "RouterSmartPreparedStart"
}

internal fun routerOwnedResumeFromFields(token: String?, profileId: Long, optionId: String?,
    requestGeneration: Long, authorityRevision: Long): RouterOwnedResume? {
    if (token == null || !validRouterSmartToken(token) || profileId <= 0 || requestGeneration < 0 || authorityRevision < 0 ||
        optionId != null && (optionId.length !in 1..128 || optionId.any(Char::isISOControl))) return null
    return RouterOwnedResume(token, profileId, optionId, requestGeneration, authorityRevision)
}
