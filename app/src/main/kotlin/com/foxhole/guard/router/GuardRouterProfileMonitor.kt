package com.foxhole.guard.router

import android.net.ConnectivityManager
import com.foxhole.guard.FoxholeApplication
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
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
    private val access = { requireRouterLocalNetworkAccess(owner) }
    private val connection get() = owner.appGraph.connectionController
    private val connectedProfiles = FoxholeVpnRuntimeBridge.snapshot.flatMapLatest { snapshot ->
        if (snapshot.state != ConnectionState.CONNECTED || snapshot.trafficMode != TrafficMode.TUNNEL || snapshot.profileId == null) flowOf(null)
        else combine(repository.activeProfile, owner.appGraph.settingsRepository.settings, connection.networkRevision) { active, settings, _ ->
            if (!settings.ui.onboardingCompleted ||
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
    val profileProbe = RouterProfileAutoProbe(owner.appScope, manager, pairing, preferences, connectedProfiles, access)

}
