package com.foxhole.guard.router

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

internal enum class RouterSmartMonitorState { OFF, STARTING, ACTIVE, FAILED }

internal class RouterHomeReturnMonitor(private val scope: CoroutineScope, private val manager: ConnectivityManager,
    private val pairings: EncryptedRouterPairingStore, private val preferences: RouterControlPreferenceStore,
    connected: StateFlow<RouterConnectedProfile?>, private val currentProfile: () -> RouterConnectedProfile?,
    private val protection: RouterSocketProtection, private val access: () -> Unit,
    private val phone: RouterSmartPhone, private val canMonitor: () -> Boolean,
    private val startForeground: (Long) -> Unit, private val stopForeground: (Long) -> Unit) {
    private data class Paused(val owner: RouterSmartPause, val wifiHandle: Long)
    private val operations = Mutex()
    private val epochs = AtomicLong()
    @Volatile private var requestedPairing: String? = null
    @Volatile private var attached: Long? = null
    @Volatile private var paused: Paused? = null
    @Volatile private var resuming: Paused? = null
    @Volatile private var lastHomeHandle: Long? = null
    private var manual = phone.manualGeneration()
    private var window = RouterSmartTransitionWindow()
    private val mutableStatus = MutableStateFlow(RouterSmartMonitorState.OFF)
    val status: StateFlow<RouterSmartMonitorState> = mutableStatus
    private val mutableActive = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutableActive
    val isActive: Boolean get() = attached == epochs.get() && requestedPairing != null && canMonitor()
    fun isCurrent(epoch: Long): Boolean = epochs.get() == epoch && requestedPairing != null
    fun canAttach(epoch: Long): Boolean = isCurrent(epoch) && canMonitor()
    fun foregroundAttached(epoch: Long): Boolean {
        if (!canAttach(epoch)) return false
        attached = epoch; mutableActive.value = true; mutableStatus.value = RouterSmartMonitorState.ACTIVE
        return true
    }
    fun foregroundStopped(epoch: Long) {
        if (attached != epoch) return
        attached = null; mutableActive.value = false; mutableStatus.value = RouterSmartMonitorState.OFF
        clearPause()
    }
    fun foregroundStartFailed(epoch: Long) {
        if (!isCurrent(epoch)) return
        val id = requestedPairing
        requestedPairing = null; attached = null
        val retired = epochs.incrementAndGet()
        mutableActive.value = false; clearPause(); mutableStatus.value = RouterSmartMonitorState.FAILED
        scope.launch { operations.withLock {
            if (epochs.get() == retired && id != null) {
                val old = preferences.read(id)
                if (old.homeAutodetection) preferences.save(old.copy(homeAutodetection = false))
            }
        } }
    }
    suspend fun setEnabled(enabled: Boolean) = operations.withLock {
        if (!enabled) { disable(); return@withLock }
        val saved = pairings.read() ?: throw RouterHomeVpnRequired()
        try {
            val settings = preferences.read(saved.id)
            if (!saved.active || !routerSmartConsent(settings, saved.id) || !canMonitor()) throw RouterHomeVpnRequired()
            if (isActive && requestedPairing == saved.id) return@withLock
            clearPause(); manual = phone.manualGeneration(); window = RouterSmartTransitionWindow()
            requestedPairing = saved.id; attached = null
            val epoch = epochs.incrementAndGet()
            mutableStatus.value = RouterSmartMonitorState.STARTING
            try { startForeground(epoch) } catch (failure: RuntimeException) { disable(); throw failure }
        } finally { saved.clear() }
    }
    suspend fun disableFromService(epoch: Long) = operations.withLock {
        if (!isCurrent(epoch)) return@withLock
        val id = requestedPairing
        if (id != null) {
            val old = preferences.read(id)
            if (old.homeAutodetection) preferences.save(old.copy(homeAutodetection = false))
        }
        disable()
    }
    private fun disable() {
        val old = attached ?: epochs.get()
        requestedPairing = null; attached = null; epochs.incrementAndGet()
        mutableActive.value = false; mutableStatus.value = RouterSmartMonitorState.OFF; clearPause(); try { stopForeground(old) } catch (_: RuntimeException) { }
    }
    private fun clearPause() { val old = paused; val starting = resuming; paused = null; resuming = null; old?.let { phone.forget(it.owner) }; starting?.takeIf { it != old }?.let { phone.forget(it.owner) } }

    init { scope.launch {
        combine(connected, pairings.changes, networkChanges(), mutableActive, phone.changes) { profile, _, _, _, _ -> profile }.collectLatest { profile ->
            if (attached != epochs.get() || requestedPairing == null) return@collectLatest
            while (attached == epochs.get() && requestedPairing != null) {
                try {
                    val epoch = epochs.get()
                    if (!canMonitor() || !consentCurrent()) {
                        operations.withLock { if (epochs.get() == epoch) disable() }
                        break
                    }
                    val generation = phone.manualGeneration()
                    if (generation != manual) { clearPause(); manual = generation; window.manualAction() }
                    resuming?.let { if (!phone.isCurrent(it.owner)) resuming = null }
                    val owner = paused
                    if (owner != null) tryResume(owner)
                    else if (profile != null && currentProfile() == profile && window.permitsPause())
                        withTimeout(5000) { returnHome(profile, pairings.changes.value, epochs.get()) }
                    else window.observe(network(lastHomeHandle), SystemClock.elapsedRealtime())
                } catch (cancelled: CancellationException) {
                    if (cancelled !is TimeoutCancellationException || !currentCoroutineContext().isActive) throw cancelled
                } catch (_: Exception) { currentCoroutineContext().ensureActive() }
                delay(1000)
                if (currentProfile() != profile) break
            }
        }
    } }
    private suspend fun consentCurrent(): Boolean {
        val saved = pairings.read() ?: return false
        return try { saved.active && requestedPairing == saved.id && routerSmartConsent(preferences.read(saved.id), saved.id) }
            finally { saved.clear() }
    }
    private suspend fun returnHome(profile: RouterConnectedProfile, revision: Long, epoch: Long) {
        val origin = pairings.trustedProfile(profile.id, profile.configSha256) ?: return
        val saved = pairings.read() ?: return
        try {
            val settings = preferences.read(saved.id)
            if (!isCurrent(epoch) || !routerSmartConsent(settings, saved.id) ||
                requestedPairing != saved.id || origin.marker.pairingId != saved.id ||
                origin.certificateSha256 != saved.identity.certificateSha256) return
            fun fence() {
                access()
                if (!isActive || !isCurrent(epoch) || currentProfile() != profile || pairings.changes.value != revision ||
                    phone.manualGeneration() != manual) throw RouterHomeVpnRequired()
            }
            fence()
            val local = RouterWifiConnection.select(manager, saved.identity.endpoint, protection, ::fence)
            val api = RouterPanelApi.forWifi(local, saved.identity)
            try {
                val session = api.unlockPairing(saved)
                try {
                    val provisioner = RouterHttpProvisioner(localAddress = { local.requireCurrent(); requireNotNull(local.localAddress.hostAddress) })
                    val snapshot = provisioner.snapshot(api, session)
                    if (routerVpnServerEnabled(snapshot) != true) return
                    val status = provisioner.phoneStatus(api, session, saved.id)
                    if (preferences.read(saved.id) != settings || pairings.trustedProfile(profile.id, profile.configSha256) != origin ||
                        !routerHomeReturnAllowed(profile, currentProfile(), origin, saved, settings, status.proof, SystemClock.elapsedRealtime())) return
                    if (!activeUsesWifi(local.network, profile)) return
                    local.requireCurrent(); fence()
                    val receipt = phone.pause(profile, local, requireNotNull(status.proof).verifiedAtElapsedMs, revision, saved.id) ?: return
                    lastHomeHandle = local.network.networkHandle
                    paused = Paused(receipt, local.network.networkHandle)
                    window = RouterSmartTransitionWindow()
                } finally { session.close() }
            } finally { api.close() }
        } finally { saved.clear() }
    }
    private suspend fun tryResume(value: Paused) {
        if (paused != value || !phone.isCurrent(value.owner)) { clearPause(); return }
        if (manager.allNetworks.any { it.networkHandle != value.owner.profile.networkHandle &&
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }) { clearPause(); return }
        val classification = network(value.wifiHandle)
        val now = SystemClock.elapsedRealtime()
        if (classification == RouterSmartNetwork.OTHER_WIFI) { clearPause(); return }
        if (!window.observe(classification, now)) { if (window.expired(now)) clearPause(); return }
        val saved = pairings.read() ?: run { clearPause(); return }
        try {
            val settings = preferences.read(saved.id)
            val origin = pairings.trustedProfile(value.owner.profile.id, value.owner.profile.configSha256)
            val revision = pairings.changes.value
            if (!routerSmartResumeAllowed(value.owner, origin, saved, settings, classification,
                    phone.isIdle(), phone.isCurrent(value.owner), anotherVpn())) return
            fun current(): Boolean = isActive && requestedPairing == value.owner.pairingId &&
                pairings.changes.value == revision && (paused == value || resuming == value) && phone.isCurrent(value.owner)
            if (!current()) return
            resuming = value
            if (phone.resume(value.owner, revision, ::current)) {
                if (paused == value) paused = null
                manual = phone.manualGeneration()
            } else if (resuming == value) resuming = null
        } finally { saved.clear() }
    }
    private fun activeUsesWifi(wifi: Network, profile: RouterConnectedProfile): Boolean {
        val active = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(active) ?: return false
        return if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            active.networkHandle == profile.networkHandle && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        else active == wifi && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    private fun anotherVpn(): Boolean = manager.allNetworks.any {
        manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
    private fun network(homeHandle: Long?): RouterSmartNetwork {
        val active = manager.activeNetwork ?: return RouterSmartNetwork.UNKNOWN
        val capabilities = manager.getNetworkCapabilities(active) ?: return RouterSmartNetwork.UNKNOWN
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            val wifi = manager.allNetworks.singleOrNull { it.networkHandle == homeHandle }
            return if (wifi != null && manager.getNetworkCapabilities(wifi)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
                RouterSmartNetwork.HOME_WIFI else RouterSmartNetwork.UNKNOWN
        }
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return RouterSmartNetwork.UNKNOWN
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            return if (active.networkHandle == homeHandle) RouterSmartNetwork.HOME_WIFI else RouterSmartNetwork.OTHER_WIFI
        return if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) RouterSmartNetwork.CELLULAR else RouterSmartNetwork.UNKNOWN
    }
    private fun networkChanges() = callbackFlow {
        fun callback() = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }
            override fun onLost(network: Network) { trySend(Unit) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(Unit) }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { trySend(Unit) }
        }
        val physical = callback(); val default = callback()
        manager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), physical)
        manager.registerDefaultNetworkCallback(default)
        trySend(Unit)
        awaitClose { manager.unregisterNetworkCallback(physical); manager.unregisterNetworkCallback(default) }
    }.conflate()
}
