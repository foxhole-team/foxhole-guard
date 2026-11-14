package com.foxhole.guard.router

import android.net.ConnectivityManager
import com.foxhole.guard.FoxholeApplication
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.TrafficMode
import com.foxhole.guard.core.settings.onboardingLicenseAccepted
import com.foxhole.guard.runtime.currentVpnNetworkHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
internal class GuardRouterProfileMonitor(private val owner: FoxholeApplication) {
    private val manager = requireNotNull(owner.getSystemService(ConnectivityManager::class.java))
    private val repository = owner.appGraph.profileRepository
    private val pairing = EncryptedRouterPairingStore(owner)
    private val preferences = LocalRouterControlPreferenceStore(owner, pairings = pairing)
    private val profiles = GuardRouterProfiles(repository, pairing)
    private val access = {
        requireRouterLocalNetworkAccess(owner)
        if (owner.appGraph.settingsRepository.settings.value.expert.bypassLan) throw RouterHomeVpnRequired()
    }
    private val connection get() = owner.appGraph.connectionController
    private val connectedProfiles = connection.snapshot.flatMapLatest { snapshot ->
        if (snapshot.state != ConnectionState.CONNECTED || snapshot.trafficMode != TrafficMode.TUNNEL || snapshot.profileId == null) flowOf(null)
        else combine(repository.activeProfile, owner.appGraph.settingsRepository.settings, connection.networkRevision) { active, settings, _ ->
            if (settings.expert.bypassLan || !settings.ui.onboardingCompleted ||
                !onboardingLicenseAccepted(settings.ui.acceptedLicenseAgreementVersion) ||
                active?.id != snapshot.profileId) return@combine null
            try {
                val network = connection.currentVpnNetworkHandle() ?: return@combine null
                val generation = connection.routerProbeGeneration() ?: return@combine null
                val profile = profiles.connectedProfile(requireNotNull(snapshot.profileId), snapshot.protocolOptionId, "$generation:$network", network)
                profile.takeIf { connection.snapshot.value == snapshot && connection.currentVpnNetworkHandle() == network }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }.distinctUntilChanged().stateIn(owner.appScope, SharingStarted.Eagerly, null)
    fun connectedProfile(): RouterConnectedProfile? = connectedProfiles.value?.takeIf { profile ->
        val snapshot = connection.snapshot.value
        !owner.appGraph.settingsRepository.settings.value.expert.bypassLan &&
            snapshot.state == ConnectionState.CONNECTED && snapshot.trafficMode == TrafficMode.TUNNEL &&
            snapshot.profileId?.toString() == profile.id && connection.currentVpnNetworkHandle() == profile.networkHandle &&
            connection.routerProbeGeneration()?.let { "$it:${profile.networkHandle}" } == profile.connectionGeneration
    }

    val smartHome = RouterHomeReturnMonitor(owner.appScope, manager, pairing, preferences, connectedProfiles,
        ::connectedProfile, object : RouterSocketProtection {
            override fun protect(socket: java.net.Socket) = GuardRouterSocketOwners.protect(socket)
            override fun isCurrent(socket: java.net.Socket) = GuardRouterSocketOwners.isCurrent(socket)
        }, access, GuardRouterSmartPhone(owner), canMonitor = {
            val settings = owner.appGraph.settingsRepository.settings.value
            settings.ui.onboardingCompleted && onboardingLicenseAccepted(settings.ui.acceptedLicenseAgreementVersion) &&
                !settings.expert.bypassLan && routerLocalNetworkAccessGranted(owner) && routerSmartNotificationsAvailable(owner)
        }, startForeground = { epoch -> androidx.core.content.ContextCompat.startForegroundService(owner,
            android.content.Intent(owner, RouterSmartHomeService::class.java).setAction(RouterSmartHomeService.ACTION_START)
                .putExtra(RouterSmartHomeService.EXTRA_EPOCH, epoch)) },
        stopForeground = { epoch -> owner.startService(android.content.Intent(owner, RouterSmartHomeService::class.java)
            .setAction(RouterSmartHomeService.ACTION_STOP).putExtra(RouterSmartHomeService.EXTRA_EPOCH, epoch)) })

    val profileProbe = RouterProfileAutoProbe(owner.appScope, manager, pairing, preferences, connectedProfiles, access)

}
