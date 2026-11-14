package com.foxhole.guard.router

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class RouterControlCoordinator(
    private val transport: RouterWizardTransport,
    private val pairingStore: RouterPairingStore,
    private val preferenceStore: RouterControlPreferenceStore,
    private val provisioner: RouterHttpProvisioner,
    val homeAutomationAvailable: Boolean = false,
    private val applyHomeAutomation: (suspend (Boolean) -> Unit)? = null,
    private val homeAutomationActive: () -> Boolean = { false },
    private val copyProfile: suspend (String) -> Unit,
    private val importProfile: suspend (String) -> Boolean = { false },
    private val beforeRouterSelection: (suspend () -> Unit)? = null,
    private val importAuthenticatedProfile: (suspend (RouterAuthenticatedProfileImport) -> Boolean)? = null,
    private val deviceUpdateRelay: RouterDeviceUpdateRelay? = null,
    val smartHomeAvailable: Boolean = false,
    private val applySmartHome: (suspend (Boolean) -> Unit)? = null,
    private val smartHomeActive: () -> Boolean = { false },
) : AutoCloseable {
    private val mutableState = MutableStateFlow(RouterControlState())
    val state: StateFlow<RouterControlState> = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val active = AtomicReference<Job?>()
    private val countryConfirmation = AtomicReference<RouterCountryDirectConfirmation?>()
    private val uiNotices = RouterUiNoticeGate()
    fun claimUiNotice(slot: String, key: String?): Boolean = uiNotices.claim(slot, key)
    private var owner = -1L
    private var pairing: RouterPairing? = null
    private var api: RouterPanelApi? = null
    private var session: RouterPanelSession? = null
    @Volatile private var observedHomeActive: Boolean? = null
    private var verifiedIdentity: RouterTlsIdentity? = null
    private var savedInventory = RouterSavedInventory(emptyList(), null)
    private var savedStatuses: Map<String, RouterSavedStatus> = emptyMap()
    private var savedVersions: Map<String, String> = emptyMap()

    suspend fun renameRouter(id: String, label: String) = operation(indicateBusy = false) {
        pairingStore.renameSaved(id, label)
        checkOwner()
        refreshInventory()
        publish(mutableState.value)
    }

    suspend fun selectRouter(id: String) {
        close()
        operation {
            val inventory = pairingStore.listSaved()
            if (inventory.routers.none { it.id == id }) throw RouterApiFailure(RouterApiError.CONFLICT)
            if (inventory.selectedId != id) {
                beforeRouterSelection?.invoke()
                checkOwner()
                pairingStore.selectSaved(id)
                checkOwner()
            }
            openSelectedBounded(identifyConnected = false)
        }
    }

    suspend fun refreshConnectedRouter() {
        close()
        operation { openSelectedBounded(identifyConnected = true) }
    }

    fun homeMonitoringChanged(active: Boolean) {
        observedHomeActive = active
        mutableState.update { current ->
            val effective = active && current.preferences?.enabled == true && current.preferences.homeAutodetection
            if (current.homeAutomationEffective == effective) current else current.copy(homeAutomationEffective = effective, revision = current.revision + 1)
        }
    }

    fun checkHomeVpnTransport() {
        val client = api?.takeIf { it.homeVpnBound } ?: return
        try { client.requireTransportCurrent() }
        catch (failure: IOException) {
            close()
            mutableState.update { it.copy(error = if (failure is RouterLocalNetworkPermissionRequired)
                RouterWizardError.LOCAL_NETWORK_PERMISSION else RouterWizardError.HOME_VPN) }
        }
    }

    suspend fun loadPairingState() = operation { loadPairingStateOwned() }

    suspend fun prepareDiscovery(forceLocal: Boolean = false): RouterManagementAccess {
        var access = RouterManagementAccess.UNAVAILABLE
        operation(indicateBusy = false) {
            loadPairingStateOwned()
            access = transport.managementAccess(pairing, mutableState.value.preferences, forceLocal)
            if (!access.canDiscover && transport.hasPhysicalWifi()) {
                val saved = pairingStore.readAll()
                try {
                    for (other in saved.filter { it.id != pairing?.id }) {
                        val candidate = transport.managementAccess(other, preferenceStore.read(other.id))
                        checkOwner()
                        if (candidate == RouterManagementAccess.LOCAL || candidate == RouterManagementAccess.VPN_ACTIVE) {
                            access = candidate
                            break
                        }
                    }
                } finally { saved.forEach(RouterPairing::clear) }
            }
            publish(mutableState.value.copy(entryAccess = access, error = null))
        }
        return access
    }

    private suspend fun loadPairingStateOwned() {
        clearConnection()
        refreshInventory()
        val saved = pairingStore.read()
        try { checkOwner() } catch (error: Exception) { saved?.clear(); throw error }
        pairing?.clear()
        pairing = saved
        if (saved == null) {
            publish(RouterControlState(stage = RouterControlStage.UNPAIRED))
            return
        }
        val preferences = preferenceStore.read(saved.id)
        checkOwner()
        val needsSetup = mutableState.value.clientCreationNeedsSetup && mutableState.value.preferences?.pairingId == saved.id
        publish(RouterControlState(stage = if (preferences.enabled) RouterControlStage.OFFLINE else RouterControlStage.DISABLED,
            preferences = preferences, address = saved.identity.endpoint.address, clientCreationNeedsSetup = needsSetup,
            lastAuthenticatedPackageVersion = cachedVersion(saved)))
    }

    suspend fun prepareVpnServerPlan() = operation {
        val connection = requireConnection()
        publish(mutableState.value.copy(vpnServer = RouterVpnServerControlState()))
        val plan = provisioner.vpnServerPlan(connection.first, connection.second)
        checkOwner()
        publish(mutableState.value.copy(vpnServer = RouterVpnServerControlState(
            plan = plan.copy(ownerPairingId = mutableState.value.selectedRouterId), ownerPairingId = mutableState.value.selectedRouterId)))
    }

    suspend fun setVpnServer(enabled: Boolean, removeComponent: Boolean = false) = operation {
        val connection = requireConnection()
        val current = mutableState.value
        val plan = current.vpnServer.plan
        if (enabled && removeComponent || removeComponent && plan?.removalAvailable != true)
            throw RouterApiFailure(RouterApiError.CONFLICT)
        if ((enabled || removeComponent) && (plan?.available != true || plan.ownerPairingId != current.selectedRouterId))
            throw RouterApiFailure(RouterApiError.CONFLICT)
        val revision = if (enabled || removeComponent) requireNotNull(plan).revision else requireNotNull(current.snapshot).revision
        val job = provisioner.vpnServerSet(connection.first, connection.second, revision, enabled, plan, removeComponent)
        checkOwner()
        val snapshot = if (job == null) provisioner.snapshot(connection.first, connection.second) else current.snapshot
        if (job == null && routerVpnServerEnabled(snapshot) != enabled) throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        checkOwner()
        observeSmartServer(snapshot)
        publish(mutableState.value.copy(snapshot = snapshot, vpnServer = RouterVpnServerControlState(job = job, ownerPairingId = current.selectedRouterId, requestedEnabled = enabled)))
    }

    suspend fun refreshVpnServerJob() = operation(indicateBusy = false) {
        val server = mutableState.value.vpnServer
        if (server.ownerPairingId != mutableState.value.selectedRouterId) return@operation
        val current = server.job ?: return@operation
        val connection = requireConnection(allowUpdates = true)
        val job = provisioner.vpnServerJob(connection.first, connection.second, current.jobId)
        checkOwner()
        val snapshot = if (!job.running) provisioner.snapshot(connection.first, connection.second) else mutableState.value.snapshot
        if (job.succeeded && server.requestedEnabled != null && routerVpnServerEnabled(snapshot) != server.requestedEnabled)
            throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        checkOwner()
        observeSmartServer(snapshot)
        publish(mutableState.value.copy(snapshot = snapshot, vpnServer = mutableState.value.vpnServer.copy(job = job)))
    }

    suspend fun refreshRouterAbout() = operation(indicateBusy = false) {
        val connection = requireConnection(allowUpdates = true)
        val info = connection.first.packageStatus()
        checkOwner()
        val country = provisioner.routerCountry(connection.first, connection.second)
        checkOwner()
        publish(mutableState.value.copy(about = info, aboutPairingId = mutableState.value.selectedRouterId,
            routerCountryCode = country,
            lastAuthenticatedPackageVersion = info.packageVersion ?: mutableState.value.lastAuthenticatedPackageVersion))
    }

    suspend fun setPublicIpConfirmed(confirmed: Boolean) = operation {
        val preferences = requireNotNull(mutableState.value.preferences)
        val updated = preferences.copy(publicIpConfirmed = confirmed,
            homeVpnManagementEnabled = confirmed && preferences.homeVpnManagementEnabled, homeVpnDefaultPending = false)
        preferenceStore.save(updated)
        checkOwner()
        updateManagementPreference(updated)
    }

    suspend fun setHideOfflineDevices(hidden: Boolean) = operation {
        val preferences = requireNotNull(mutableState.value.preferences)
        val updated = preferences.copy(hideOfflineDevices = hidden)
        preferenceStore.save(updated)
        checkOwner()
        publish(mutableState.value.copy(preferences = updated))
    }

    suspend fun setHomeVpnManagement(enabled: Boolean) = operation {
        val preferences = requireNotNull(mutableState.value.preferences)
        if (enabled) {
            if (!preferences.publicIpConfirmed) throw RouterApiFailure(RouterApiError.UNSUPPORTED)
            val saved = pairingStore.readById(preferences.pairingId) ?: throw RouterApiFailure(RouterApiError.PIN)
            try { checkOwner(); if (!saved.active) throw RouterApiFailure(RouterApiError.PIN) }
            finally { saved.clear() }
        }
        val updated = preferences.copy(homeVpnManagementEnabled = enabled, homeVpnDefaultPending = false)
        preferenceStore.save(updated)
        checkOwner()
        updateManagementPreference(updated)
    }

    private fun updateManagementPreference(updated: RouterControlPreferences) {
        if (api?.homeVpnBound == true && (!updated.publicIpConfirmed || !updated.homeVpnManagementEnabled)) {
            clearConnection()
            publish(mutableState.value.copy(stage = if (updated.enabled) RouterControlStage.OFFLINE else RouterControlStage.DISABLED,
                preferences = updated, snapshot = null, engines = RouterEngineControlState(), updates = RouterUpdateControlState()))
        } else publish(mutableState.value.copy(preferences = updated))
    }

    suspend fun sendProfile(name: String, configuration: String) = operation {
        val preferences = requireNotNull(mutableState.value.preferences)
        loadActionPairing(preferences)
        try {
            if (api == null || session == null) reopen(preferences.copy(enabled = true))
            val connection = requireConnection()
            val transferred = provisioner.sendProfile(connection.first, connection.second, name, configuration)
            checkOwner()
            publish(mutableState.value.copy(snapshot = transferred.snapshot, selectedServerId = transferred.profileId))
        } finally {
            if (!preferences.enabled && owner == generation.get()) {
                clearConnection()
                publish(mutableState.value.copy(stage = RouterControlStage.DISABLED, preferences = preferences, snapshot = null))
            }
        }
    }

    fun phoneTransferCatalogCurrent(catalog: RouterPhoneTransferCatalog): Boolean =
        catalog.generation == generation.get() && catalog.pairingId == mutableState.value.preferences?.pairingId &&
            catalog.pairingId == mutableState.value.selectedRouterId

    suspend fun phoneTransferCatalog(requests: List<RouterPhoneTransferRequest>, export: suspend (RouterPhoneTransferRequest) -> RouterProfileSelection?): RouterPhoneTransferCatalog? {
        var catalog: RouterPhoneTransferCatalog? = null
        operation {
            require(requests.size <= 65536)
            if (mutableState.value.engines.running || mutableState.value.updates.running) throw RouterApiFailure(RouterApiError.UNAVAILABLE)
            val connection = requireConnection()
            val capability = provisioner.phoneTransferCapabilities(connection.first, connection.second)
            checkOwner()
            val choices = mutableListOf<RouterPhoneTransferChoice>()
            for (request in requests.distinct()) {
                currentCoroutineContext().ensureActive()
                checkOwner()
                val selection = try { export(request) }
                catch (failure: RouterApiFailure) {
                    if (failure.kind in setOf(RouterApiError.UNSUPPORTED, RouterApiError.INSECURE_TLS, RouterApiError.CONFLICT)) null
                    else throw failure
                } catch (_: IllegalArgumentException) { null }
                checkOwner()
                if (selection != null && selection.phoneProfileId == request.profileId && selection.selectedOptionId == request.optionId &&
                    provisioner.canTransferPhoneSelection(selection, capability)) {
                    choices += RouterPhoneTransferChoice.capture(selection)
                }
            }
            checkOwner()
            catalog = RouterPhoneTransferCatalog(requireNotNull(mutableState.value.preferences).pairingId, owner, choices.toList())
        }
        return catalog?.takeIf { phoneTransferCatalogCurrent(it) && mutableState.value.error == null }
    }

    suspend fun sendPhoneProfileChoices(catalog: RouterPhoneTransferCatalog, ids: Set<String>,
        export: suspend (RouterPhoneTransferRequest) -> RouterProfileSelection?): RouterPhoneTransferResult {
        val confirmed = linkedSetOf<String>()
        operation {
            if (!phoneTransferCatalogCurrent(catalog)) throw RouterApiFailure(RouterApiError.CONFLICT)
            require(ids.isNotEmpty() && ids.size <= 256)
            val choices = catalog.choices.filter { it.choiceId in ids }
            require(choices.size == ids.size)
            if (mutableState.value.engines.running || mutableState.value.updates.running) throw RouterApiFailure(RouterApiError.UNAVAILABLE)
            val connection = requireConnection()
            suspend fun currentSelection(choice: RouterPhoneTransferChoice): RouterProfileSelection {
                currentCoroutineContext().ensureActive()
                checkOwner()
                val selected = export(choice.request) ?: throw RouterApiFailure(RouterApiError.CONFLICT)
                checkOwner()
                if (!choice.matches(selected)) throw RouterApiFailure(RouterApiError.CONFLICT)
                return selected
            }
            // Only explicitly selected configurations reach the router.
            // Validate every selection before the first write.
            for (choice in choices) {
                provisioner.validatePhoneSelection(connection.first, connection.second, currentSelection(choice))
                checkOwner()
            }
            for (choice in choices) {
                val transferred = provisioner.sendSelection(connection.first, connection.second,
                    requireNotNull(pairing), currentSelection(choice))
                checkOwner()
                confirmed += choice.choiceId
                publish(mutableState.value.copy(snapshot = transferred.snapshot, selectedServerId = transferred.profileId))
            }
        }
        return RouterPhoneTransferResult(ids.size, confirmed.toSet())
    }

    suspend fun sendSelection(selection: RouterProfileSelection) = operation {
        val preferences = requireNotNull(mutableState.value.preferences)
        loadActionPairing(preferences)
        try {
            if (api == null || session == null) reopen(preferences.copy(enabled = true))
            val connection = requireConnection()
            val transferred = provisioner.sendSelection(connection.first, connection.second, requireNotNull(pairing), selection)
            checkOwner()
            publish(mutableState.value.copy(snapshot = transferred.snapshot, selectedServerId = transferred.profileId))
        } finally {
            if (!preferences.enabled && owner == generation.get()) {
                clearConnection()
                publish(mutableState.value.copy(stage = RouterControlStage.DISABLED, preferences = preferences, snapshot = null))
            }
        }
    }

    // Recovery only reads status; neither APPLY nor country settings are replayed.
    suspend fun refreshUpdates(statusOnly: Boolean = false) = updateOperation(indicateBusy = false) {
        val preferences = mutableState.value.preferences ?: throw RouterApiFailure(RouterApiError.UNSUPPORTED)
        val recovering = api == null || session == null || mutableState.value.updates.pending != null ||
            mutableState.value.updates.reconnecting || mutableState.value.updates.unreachable
        if (api == null || session == null) {
            if (mutableState.value.stage == RouterControlStage.PAIRING_REQUIRED) throw RouterApiFailure(RouterApiError.PIN)
            publish(mutableState.value.copy(updates = mutableState.value.updates.copy(reconnecting = true)))
            reopen(preferences, readOnly = true)
        }
        readUpdateState(preparePlan = !statusOnly && !recovering)
    }

    suspend fun checkUpdates() = updateOperation {
        val current = mutableState.value.updates
        if (!current.canCheck) throw RouterUpdateFailure(RouterUpdateFailureReason.BUSY)
        val status = current.status ?: throw RouterUpdateFailure(RouterUpdateFailureReason.NOT_CHECKED)
        if (status.deviceDownloadRequired) { readUpdateState(); return@updateOperation }
        val connection = requireConnection(allowUpdates = true)
        publish(mutableState.value.copy(updates = current.copy(plan = null, failure = null,
            pending = RouterUpdatePending(RouterUpdateOperation.CHECK, null, status.lastOwnJob?.jobId ?: current.job?.jobId),
            pollEpoch = current.pollEpoch + 1)))
        val job = updateWrite { provisioner.checkUpdates(connection.first, connection.second, status) }
        checkOwner()
        publish(mutableState.value.copy(updates = mutableState.value.updates.copy(job = job, pending = null, failure = job.progress.failureReason)))
        if (!job.running) readUpdateState()
    }

    suspend fun applyUpdate(planId: String, planSha256: String) = updateOperation {
        val current = mutableState.value.updates
        val plan = current.plan?.takeIf { current.canApply && it.planId == planId && it.planSha256 == planSha256 }
            ?: throw RouterUpdateFailure(RouterUpdateFailureReason.STALE_PLAN)
        val status = current.status ?: throw RouterUpdateFailure(RouterUpdateFailureReason.STALE_PLAN)
        val connection = requireConnection(allowUpdates = true)
        // Consume confirmation before dispatch. A lost acknowledgement cannot expose this plan again.
        publish(mutableState.value.copy(updates = current.copy(plan = null, job = null, failure = null,
            pending = RouterUpdatePending(RouterUpdateOperation.APPLY, plan.planId, current.job?.jobId), pollEpoch = current.pollEpoch + 1)))
        val job = updateWrite { provisioner.applyUpdate(connection.first, connection.second, status, plan) }
        checkOwner()
        publish(mutableState.value.copy(updates = mutableState.value.updates.copy(job = job, pending = null, failure = job.progress.failureReason)))
        if (!job.running) readUpdateState()
    }

    suspend fun setUpdateAuto(enabled: Boolean) = updateOperation {
        val current = mutableState.value.updates
        if (!current.canSetAuto) throw RouterUpdateFailure(RouterUpdateFailureReason.BUSY)
        val status = current.status ?: throw RouterUpdateFailure(RouterUpdateFailureReason.NOT_CHECKED)
        val connection = requireConnection(allowUpdates = true)
        val updated = provisioner.setUpdateAuto(connection.first, connection.second, status, enabled)
        checkOwner()
        publish(mutableState.value.copy(updates = current.copy(status = updated, plan = null, failure = null)))
        readUpdateState()
    }

    private suspend fun readUpdateState(preparePlan: Boolean = true) {
        val connection = requireConnection(allowUpdates = true)
        val current = mutableState.value.updates
        if (current.supported == null) {
            val capability = try { provisioner.capabilities(connection.first, connection.second).packageUpdates }
            catch (failure: RouterApiFailure) {
                if (failure.kind in setOf(RouterApiError.UNSUPPORTED, RouterApiError.UNAVAILABLE) && failure !is RouterUpdateFailure) false
                else throw failure
            }
            checkOwner()
            if (!capability) {
                publish(mutableState.value.copy(updates = RouterUpdateControlState(supported = false)))
                return
            }
        }
        if (current.supported == false) return
        var status = provisioner.updateStatus(connection.first, connection.second)
        checkOwner()
        if (current.status != null && (current.status.routerId != status.routerId || status.revision < current.status.revision)) invalidEngineResponse()
        rememberAuthenticatedVersion(status.currentVersion)
        val own = status.activeOwnJob ?: status.lastOwnJob
        val matches = own != null && (current.pending == null || current.pending.matches(own))
        val job = if (matches) provisioner.updateJob(connection.first, connection.second, status, requireNotNull(own))
            else current.job?.takeIf { it.routerId == status.routerId }
        checkOwner()
        if (matches && job?.running == false) {
            val verified = provisioner.updateStatus(connection.first, connection.second)
            checkOwner()
            if (verified.routerId != status.routerId || verified.revision < status.revision) invalidEngineResponse()
            status = verified
        }
        if (job?.running == false && (current.job?.jobId != job.jobId || current.job?.running == true)) {
            val snapshot = provisioner.snapshot(connection.first, connection.second)
            checkOwner()
            publish(mutableState.value.copy(snapshot = snapshot))
        }
        val pending = current.pending.takeUnless { matches }
        val discovered = current.pending == null && job != null && current.job?.jobId != job.jobId ||
            status.activeAutomaticJob?.running == true && current.status?.activeAutomaticJob?.running != true
        val jobFailure = job?.progress?.failureReason.takeUnless {
            it == RouterUpdateFailureReason.LEGACY_CONNECTION_ACTIVE && status.failureReason != it &&
                status.autoUpdate.suspendedReason != it && mutableState.value.snapshot?.desiredServerId == null
        }
        var refreshed = RouterUpdateControlState(status.supported, status, job = job, pending = pending,
            failure = jobFailure ?: status.failureReason,
            pollEpoch = current.pollEpoch + if (discovered) 1 else 0)
        publish(mutableState.value.copy(updates = refreshed))
        val relay = deviceUpdateRelay
        if (preparePlan && refreshed.canCheck && !refreshed.requiresVpnStop && status.deviceDownloadRequired && relay != null) {
            val relayOwner = owner
            val relayJob = currentCoroutineContext()[Job]
            publish(mutableState.value.copy(updates = refreshed.copy(deviceDownloading = true)))
            val uploaded = try {
                relay.prepare(connection.first, connection.second, status) { percent ->
                    mutableState.update { state ->
                        if (relayOwner == generation.get() && relayJob === active.get() && relayJob?.isActive == true)
                            state.copy(updates = state.updates.copy(deviceDownloading = true, deviceProgressPercent = percent))
                        else state
                    }
                }
            } finally {
                if (relayOwner == generation.get()) mutableState.update { state ->
                    state.copy(updates = state.updates.copy(deviceDownloading = false, deviceProgressPercent = null))
                }
            }
            checkOwner()
            if (uploaded == null) {
                if (status.deviceDownloadKind == RouterUpdateCohortKind.CORE) invalidEngineResponse()
                publish(mutableState.value.copy(updates = refreshed.copy(failure = null, job = null, deviceCheckedCurrent = true)))
                return
            }
            status = uploaded
            refreshed = refreshed.copy(status = uploaded, failure = null, job = null)
            publish(mutableState.value.copy(updates = refreshed))
        }
        if (preparePlan && refreshed.canCheck && !refreshed.requiresVpnStop && status.availability == RouterUpdateAvailability.AVAILABLE) {
            val plan = provisioner.updatePlan(connection.first, connection.second, status)
            checkOwner()
            publish(mutableState.value.copy(updates = refreshed.copy(plan = plan)))
        }
    }

    private suspend fun updateWrite(block: suspend () -> RouterUpdateJob): RouterUpdateJob = try { block() }
    catch (failure: RouterUpdateFailure) {
        checkOwner()
        // Only a definite server rejection of this dispatch can clear its pending acknowledgement.
        if (failure.reason !in setOf(RouterUpdateFailureReason.STALE_SESSION, RouterUpdateFailureReason.ROUTER_UNAVAILABLE))
            publish(mutableState.value.copy(updates = mutableState.value.updates.copy(pending = null)))
        throw failure
    }

    private suspend fun updateOperation(indicateBusy: Boolean = true, block: suspend () -> Unit) = operation(indicateBusy) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: RouterUpdateFailure) {
            checkOwner()
            val reauth = failure.reason == RouterUpdateFailureReason.STALE_SESSION
            val unavailable = failure.reason == RouterUpdateFailureReason.ROUTER_UNAVAILABLE
            val auth = failure.reason == RouterUpdateFailureReason.AUTHENTICATION_FAILED
            if (reauth || unavailable || auth) clearConnection()
            publish(mutableState.value.copy(
                stage = if (auth) RouterControlStage.PAIRING_REQUIRED else if (reauth || unavailable) RouterControlStage.OFFLINE else mutableState.value.stage,
                snapshot = if (reauth || unavailable || auth) null else mutableState.value.snapshot,
                updates = mutableState.value.updates.copy(plan = null, failure = failure.reason,
                    reconnecting = reauth || unavailable, unreachable = unavailable)))
        }
        catch (failure: RouterApiFailure) {
            checkOwner()
            if (failure.kind == RouterApiError.NETWORK) updateTransportLost()
            else {
                val auth = failure.kind in setOf(RouterApiError.PIN, RouterApiError.CHANGE_PIN, RouterApiError.LOCKED, RouterApiError.IDENTITY)
                if (auth) clearConnection()
                publish(mutableState.value.copy(stage = if (auth) RouterControlStage.PAIRING_REQUIRED else mutableState.value.stage,
                    snapshot = if (auth) null else mutableState.value.snapshot,
                    updates = mutableState.value.updates.copy(plan = null, reconnecting = false, unreachable = false,
                        failure = if (auth) RouterUpdateFailureReason.AUTHENTICATION_FAILED else RouterUpdateFailureReason.INVALID_REQUEST)))
            }
        }
        catch (_: IOException) { checkOwner(); updateTransportLost() }
    }

    private fun updateTransportLost() {
        clearConnection()
        publish(mutableState.value.copy(stage = RouterControlStage.OFFLINE, snapshot = null,
            updates = mutableState.value.updates.copy(plan = null, reconnecting = true, unreachable = true)))
    }

    suspend fun refreshEngines() = operation(indicateBusy = false) {
        val connection = requireConnection()
        val status = try { provisioner.engineStatus(connection.first, connection.second) }
        catch (failure: RouterApiFailure) {
            if (failure !is RouterEngineFailure && failure.kind in setOf(RouterApiError.UNSUPPORTED, RouterApiError.UNAVAILABLE)) {
                checkOwner()
                publish(mutableState.value.copy(engines = RouterEngineControlState(supported = false)))
                return@operation
            }
            throw failure
        }
        checkOwner()
        val current = mutableState.value.engines
        val own = status.activeOwnJob ?: status.lastOwnJob
        val job = if (own != null) provisioner.engineJob(connection.first, connection.second, status, own.jobId, own.planId)
            else current.job?.takeIf { it.routerId == status.routerId && !it.running }
        checkOwner()
        val verified = if (own != null && job?.running == false) provisioner.engineStatus(connection.first, connection.second) else status
        checkOwner()
        if (verified.routerId != status.routerId) invalidEngineResponse()
        publish(mutableState.value.copy(engines = RouterEngineControlState(true, verified, job = job, failure = job?.failureReason)))
    }

    suspend fun planEngines(kind: RouterEngineOperation, protocols: Set<RouterInstallProtocol>) = operation {
        val connection = requireConnection()
        val current = mutableState.value.engines
        if (current.running) throw RouterEngineFailure(RouterEngineFailureReason.BUSY)
        val status = current.status ?: throw RouterEngineFailure(RouterEngineFailureReason.STALE_PLAN)
        publish(mutableState.value.copy(engines = mutableState.value.engines.copy(plan = null, failure = null)))
        val plan = provisioner.planEngines(connection.first, connection.second, status, kind, protocols)
        checkOwner()
        if (mutableState.value.engines.status != status) throw RouterEngineFailure(RouterEngineFailureReason.STALE_PLAN)
        publish(mutableState.value.copy(engines = mutableState.value.engines.copy(plan = plan)))
    }

    fun discardEnginePlan() {
        mutableState.update { it.copy(engines = it.engines.copy(plan = null, failure = null)) }
    }

    suspend fun applyEnginePlan(planId: String, planSha256: String) = operation {
        val connection = requireConnection()
        val current = mutableState.value.engines
        val plan = current.plan?.takeIf { it.planId == planId && it.planSha256 == planSha256 }
            ?: throw RouterEngineFailure(RouterEngineFailureReason.STALE_PLAN)
        val status = current.status ?: throw RouterEngineFailure(RouterEngineFailureReason.STALE_PLAN)
        publish(mutableState.value.copy(engines = current.copy(plan = null, job = null, failure = null)))
        val job = provisioner.applyEngines(connection.first, connection.second, status, plan)
        checkOwner()
        val refreshed = if (job.running) status else provisioner.engineStatus(connection.first, connection.second)
        checkOwner()
        if (refreshed.routerId != status.routerId) invalidEngineResponse()
        publish(mutableState.value.copy(engines = current.copy(status = refreshed, plan = null, job = job, failure = job.failureReason)))
    }

    suspend fun refreshEngineJob(jobId: String) = operation(indicateBusy = false) {
        val connection = requireConnection()
        val current = mutableState.value.engines
        val job = current.job?.takeIf { it.jobId == jobId && it.running } ?: return@operation
        val status = current.status ?: return@operation
        val updated = provisioner.engineJob(connection.first, connection.second, status, job.jobId, job.planId)
        checkOwner()
        val refreshed = if (updated.running) status else provisioner.engineStatus(connection.first, connection.second)
        checkOwner()
        if (refreshed.routerId != status.routerId) invalidEngineResponse()
        publish(mutableState.value.copy(engines = current.copy(status = refreshed, job = updated, failure = updated.failureReason)))
    }

    suspend fun refreshDevices() = operation {
        val ticket = owner
        publish(mutableState.value.copy(lanDevices = emptyList(), lanDevicesLoading = true,
            lanDevicesLoaded = false, lanDevicesFailed = false))
        try {
            val connection = requireConnection()
            val snapshot = requireNotNull(mutableState.value.snapshot)
            val devices = withTimeoutOrNull(15_000) { provisioner.devices(connection.first, connection.second, snapshot) }
                ?: throw RouterApiTimeout()
            checkOwner()
            publish(mutableState.value.copy(lanDevices = devices, lanDevicesLoaded = true, lanDevicesFailed = false))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            checkOwner()
            publish(mutableState.value.copy(lanDevicesLoaded = false, lanDevicesFailed = true))
            throw failure
        } finally {
            if (ticket == generation.get()) publish(mutableState.value.copy(lanDevicesLoading = false))
        }
    }

    private val readCurrentSettingsOnNextOpen = AtomicBoolean(false)

    fun preparePairedOpen() { readCurrentSettingsOnNextOpen.set(true) }

    suspend fun openUserManagement(userVisible: () -> Boolean) = operation {
        if (userVisible()) openSelectedBounded(identifyConnected = true, userVisible = userVisible)
    }

    suspend fun open() = operation {
        openSelectedBounded(identifyConnected = true, currentSettingsOnly = readCurrentSettingsOnNextOpen.getAndSet(false))
    }

    private suspend fun openSelectedBounded(identifyConnected: Boolean, currentSettingsOnly: Boolean = false, userVisible: (() -> Boolean)? = null) {
        val completed = withTimeoutOrNull(40_000) {
            openSelected(identifyConnected, currentSettingsOnly, userVisible)
            true
        } == true
        if (!completed) {
            checkOwner()
            fail(owner, RouterWizardError.NETWORK)
        }
    }

    private suspend fun cachedVersion(saved: RouterPairing): String? = try {
        preferenceStore.readAuthenticatedVersion(saved.id, saved.identity.certificateSha256)
    } catch (_: IOException) { null }

    private suspend fun rememberAuthenticatedVersion(version: String?) {
        val value = routerDiscoveryPackageVersion(version) ?: return
        val saved = pairing ?: return
        checkOwner()
        try { preferenceStore.saveAuthenticatedVersion(saved.id, saved.identity.certificateSha256, value) }
        catch (_: IOException) { /* Public display metadata must not interrupt authenticated management. */ }
        checkOwner()
        savedVersions = savedVersions + (saved.id to value)
        publish(mutableState.value.copy(lastAuthenticatedPackageVersion = value))
    }

    private suspend fun openSelected(identifyConnected: Boolean, currentSettingsOnly: Boolean = false, userVisible: (() -> Boolean)? = null) {
        clearConnection()
        refreshInventory()
        if (mutableState.value.entryAccess == RouterManagementAccess.REMOTE || !transport.hasPhysicalWifi()) {
            openSavedWithoutWifi()
            return
        }
        val all = pairingStore.readAll()
        val match = try { discoverSavedRouterIdentity(transport, all, ::checkOwner) }
        finally { all.forEach(RouterPairing::clear) }
        savedStatuses = match.statuses
        if (identifyConnected && match.pairingId != null && match.pairingId != savedInventory.selectedId) {
            beforeRouterSelection?.invoke()
            checkOwner()
            pairingStore.selectSaved(match.pairingId)
            checkOwner()
            refreshInventory()
        }
        val saved = pairingStore.read()
        if (saved == null) {
            publish(RouterControlState(stage = RouterControlStage.UNPAIRED))
            return
        }
        val needsSetup = mutableState.value.clientCreationNeedsSetup && mutableState.value.preferences?.pairingId == saved.id
        try {
            checkOwner()
            val observed = match.identity?.takeIf { match.pairingId == saved.id }
            val chosen = observed?.let(saved::atVerifiedIdentity) ?: saved
            try {
                if (chosen !== saved && saved.identity != observed) pairingStore.save(chosen)
                checkOwner()
                pairing?.clear(); pairing = chosen
                verifiedIdentity = observed
                if (chosen !== saved) saved.clear()
            } catch (error: Exception) { if (chosen !== saved) chosen.clear(); throw error }
        } catch (error: Exception) { saved.clear(); throw error }
        refreshInventory()
        val preferences = preferenceStore.read(saved.id)
        checkOwner()
        publish(RouterControlState(stage = if (preferences.enabled) RouterControlStage.CONNECTING else RouterControlStage.DISABLED,
            preferences = preferences, address = requireNotNull(pairing).identity.endpoint.address, clientCreationNeedsSetup = needsSetup,
            lastAuthenticatedPackageVersion = cachedVersion(requireNotNull(pairing))))
        if (!preferences.enabled && saved.active && match.pairingId == saved.id && userVisible?.invoke() == true) {
            // The visible user action may unlock the pinned LAN pair, but background reads
            // must never migrate or re-enable a previously disabled consent preference.
            reopen(preferences.copy(enabled = true), readOnly = true, persistPreferences = false)
            checkOwner()
            if (mutableState.value.stage == RouterControlStage.CONNECTED && userVisible.invoke()) {
                val verified = requireNotNull(mutableState.value.preferences).copy(enabled = true)
                preferenceStore.save(verified); checkOwner()
                publish(mutableState.value.copy(preferences = verified))
            } else {
                clearConnection()
                publish(mutableState.value.copy(stage = RouterControlStage.DISABLED, preferences = preferences, snapshot = null))
            }
            return
        }
        if ((preferences.enabled || currentSettingsOnly) && match.pairingId == saved.id) {
            reopen(preferences, readOnly = currentSettingsOnly)
            if (currentSettingsOnly) {
                if (preferences.enabled) readUpdateState(preparePlan = false)
                else {
                    val current = mutableState.value
                    clearConnection()
                    publish(current.copy(stage = RouterControlStage.DISABLED))
                }
            }
        } else if (preferences.enabled) publish(mutableState.value.copy(stage = RouterControlStage.OFFLINE,
            error = if (match.unknownIdentity) RouterWizardError.IDENTITY else RouterWizardError.UNAVAILABLE))
    }

    private suspend fun openSavedWithoutWifi() {
        val saved = pairingStore.read()
        try { checkOwner() } catch (failure: Exception) { saved?.clear(); throw failure }
        pairing?.clear(); pairing = saved
        if (saved == null) {
            publish(RouterControlState(stage = RouterControlStage.UNPAIRED))
            return
        }
        val preferences = preferenceStore.read(saved.id)
        checkOwner()
        publish(RouterControlState(stage = if (preferences.enabled) RouterControlStage.CONNECTING else RouterControlStage.DISABLED,
            preferences = preferences, address = saved.identity.endpoint.address, lastAuthenticatedPackageVersion = cachedVersion(saved)))
        if (preferences.enabled) reopen(preferences)
    }

    private suspend fun refreshInventory() {
        savedInventory = pairingStore.listSaved()
        checkOwner()
        val saved = pairingStore.readAll()
        try {
            savedVersions = buildMap {
                saved.forEach { row -> cachedVersion(row)?.let { put(row.id, it) } }
            }
            checkOwner()
        } finally { saved.forEach(RouterPairing::clear) }
    }

    suspend fun setEnabled(enabled: Boolean) = operation {
        val preferences = requireNotNull(mutableState.value.preferences)
        if (!enabled) {
            if (homeAutomationAvailable && applyHomeAutomation != null) { applyHomeAutomation.invoke(false); checkOwner() }
            clearConnection()
            checkOwner()
            val updated = preferences.copy(enabled = false)
            preferenceStore.save(updated)
            checkOwner()
            publish(mutableState.value.copy(stage = RouterControlStage.DISABLED, preferences = updated, snapshot = null, selectedServerId = null, homeAutomationEffective = false, engines = RouterEngineControlState()))
        } else {
            if (pairing == null) pairing = pairingStore.read() ?: throw RouterApiFailure(RouterApiError.PIN)
            checkOwner()
            reopen(preferences.copy(enabled = true))
            checkOwner()
            val updated = requireNotNull(mutableState.value.preferences).copy(enabled = true)
            preferenceStore.save(updated)
            checkOwner()
            publish(mutableState.value.copy(preferences = updated))
        }
    }

    fun selectServer(id: String) {
        val current = mutableState.value
        if (!current.controlsAvailable || current.snapshot?.servers?.none { it.text("id") == id } != false) return
        mutableState.value = current.copy(selectedServerId = id)
    }

    suspend fun refresh() = operation {
        val preferences = requireNotNull(mutableState.value.preferences)
        if (!preferences.enabled) throw RouterApiFailure(RouterApiError.UNSUPPORTED)
        reopen(preferences)
    }

    suspend fun setCountry(code: String, direct: Boolean) = operation {
        if (code !in Locale.getISOCountries().toSet()) throw RouterApiFailure(RouterApiError.UNSUPPORTED)
        val preferences = requireNotNull(mutableState.value.preferences)
        val selected = preferences.copy(countryCode = code, countrySitesDirect = direct, countryAuto = false)
        if (direct) applyCountrySelection(selected) else {
            preferenceStore.save(selected); checkOwner()
            publish(mutableState.value.copy(preferences = selected))
        }
    }

    fun requestCountryDirectChange(direct: Boolean, manualCode: String? = null): RouterCountryDirectConfirmation? {
        val current = mutableState.value
        val preferences = current.preferences ?: return null
        val snapshot = current.snapshot ?: return null
        val routerId = current.selectedRouterId ?: return null
        if (current.busy || !current.controlsAvailable || !preferences.enabled) return null
        val control = routerCountryDirectControl(preferences, current.routerCountryCode, snapshot)
        val code = manualCode ?: (if (direct) control.enableCode else control.disableCode) ?: return null
        if (code !in Locale.getISOCountries().toSet() || direct == control.checked &&
            (manualCode == null || code == preferences.countryCode)) return null
        return RouterCountryDirectConfirmation(generation.get(), routerId, snapshot.revision, preferences,
            current.routerCountryCode, control.checked, code, manualCode != null, direct).also(countryConfirmation::set)
    }

    fun cancelCountryDirectChange(confirmation: RouterCountryDirectConfirmation) {
        countryConfirmation.compareAndSet(confirmation, null)
    }

    suspend fun confirmCountryDirectChange(confirmation: RouterCountryDirectConfirmation) = operation {
        val current = mutableState.value
        val preferences = current.preferences ?: throw RouterApiFailure(RouterApiError.CONFLICT)
        if (!countryConfirmation.compareAndSet(confirmation, null) || confirmation.generation != generation.get() ||
            current.selectedRouterId != confirmation.routerId || current.snapshot?.revision != confirmation.revision ||
            preferences != confirmation.preferences || current.routerCountryCode != confirmation.detectedCountry ||
            routerCountryDirectControl(preferences, current.routerCountryCode, current.snapshot).checked != confirmation.wasEnabled)
            throw RouterApiFailure(RouterApiError.CONFLICT)
        val selected = preferences.copy(countryCode = confirmation.countryCode, countrySitesDirect = confirmation.turnOn,
            countryAuto = if (confirmation.manualCountry) false else preferences.countryAuto)
        if (confirmation.manualCountry && !confirmation.turnOn) {
            preferenceStore.save(selected); checkOwner()
            publish(mutableState.value.copy(preferences = selected))
        } else applyCountrySelection(selected)
    }

    suspend fun setCountryDirect(direct: Boolean) = operation {
        val preferences = requireNotNull(mutableState.value.preferences)
        val current = mutableState.value
        val control = routerCountryDirectControl(preferences, current.routerCountryCode, current.snapshot)
        val code = (if (direct) control.enableCode else control.disableCode)
            ?: throw RouterApiFailure(RouterApiError.UNSUPPORTED)
        applyCountrySelection(preferences.copy(countryCode = code, countrySitesDirect = direct))
    }

    suspend fun setCountryAuto(enabled: Boolean) = operation {
        val preferences = requireNotNull(mutableState.value.preferences)
        val connection = requireConnection()
        val country = provisioner.routerCountry(connection.first, connection.second)
        checkOwner()
        publish(mutableState.value.copy(routerCountryCode = country))
        val active = routerCountryDirectControl(preferences, mutableState.value.routerCountryCode, mutableState.value.snapshot).checked
        val updated = preferences.copy(countryAuto = enabled, countrySitesDirect = active,
            countryCode = if (enabled) country ?: preferences.countryCode else preferences.countryCode)
        if (active && enabled && country != null) applyCountrySelection(updated)
        else {
            preferenceStore.save(updated)
            checkOwner()
            publish(mutableState.value.copy(preferences = updated))
        }
    }

    private suspend fun applyCountrySelection(preferences: RouterControlPreferences) {
        val connection = requireConnection()
        val current = requireNotNull(mutableState.value.snapshot)
        val verified = try {
            provisioner.setCountryRule(connection.first, connection.second, current, preferences.countryCode, preferences.countrySitesDirect)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            try {
                val observed = provisioner.snapshot(connection.first, connection.second)
                checkOwner()
                if (observed.revision >= current.revision) publish(mutableState.value.copy(snapshot = observed))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { currentCoroutineContext().ensureActive() }
            throw failure
        }
        checkOwner()
        preferenceStore.save(preferences)
        checkOwner()
        publish(mutableState.value.copy(snapshot = verified, preferences = preferences))
    }

    suspend fun refreshClientStatus() = operation {
        val connection = requireConnection()
        val snapshot = requireNotNull(mutableState.value.snapshot)
        publish(mutableState.value.copy(remoteClients = unknownRouterClientStatuses(snapshot)))
        val observed = provisioner.clientStatuses(connection.first, connection.second, snapshot)
        checkOwner()
        publish(mutableState.value.copy(remoteClients = observed))
    }

    private suspend fun observeSmartServer(snapshot: RouterControlSnapshot?) {
        val old = mutableState.value.preferences ?: return
        val value = old.copy(authenticatedHomeServerEnabled = routerVpnServerEnabled(snapshot))
        if (value != old) {
            preferenceStore.save(value); checkOwner()
            publish(mutableState.value.copy(preferences = value))
        }
    }
    suspend fun setSmartHome(enabled: Boolean, userVisible: () -> Boolean = { false }) = operation {
        if (enabled && (!userVisible() || !smartHomeAvailable || applySmartHome == null)) throw RouterApiFailure(RouterApiError.UNSUPPORTED)
        val old = requireNotNull(mutableState.value.preferences)
        if (enabled) {
            requireConnection()
            if (routerVpnServerEnabled(mutableState.value.snapshot) != true || !old.publicIpConfirmed || !old.homeVpnManagementEnabled)
                throw RouterApiFailure(RouterApiError.UNSUPPORTED)
        }
        val updated = old.copy(homeAutodetection = enabled,
            authenticatedHomeServerEnabled = routerVpnServerEnabled(mutableState.value.snapshot))
        preferenceStore.save(updated); checkOwner()
        try {
            if (enabled && !userVisible()) throw RouterApiFailure(RouterApiError.UNSUPPORTED)
            applySmartHome?.invoke(enabled)
        }
        catch (failure: Exception) { preferenceStore.save(old); throw failure }
        checkOwner()
        val actual = preferenceStore.read(old.pairingId); checkOwner()
        publish(mutableState.value.copy(preferences = actual, homeAutomationEffective = enabled && smartHomeActive()))
    }
    suspend fun smartMonitoringChanged(status: RouterSmartMonitorState) {
        val id = mutableState.value.selectedRouterId ?: return
        val current = preferenceStore.read(id)
        if (mutableState.value.selectedRouterId != id) return
        homeMonitoringChanged(status == RouterSmartMonitorState.ACTIVE)
        mutableState.update { value -> if (value.selectedRouterId != id) value else value.copy(preferences = current,
            homeAutomationEffective = status == RouterSmartMonitorState.ACTIVE && current.homeAutodetection,
            error = if (status == RouterSmartMonitorState.FAILED) RouterWizardError.UNAVAILABLE else value.error) }
    }

    suspend fun setHomeAutodetection(enabled: Boolean) = operation {
        if (enabled && (!homeAutomationAvailable || applyHomeAutomation == null)) throw RouterApiFailure(RouterApiError.UNSUPPORTED)
        val preferences = requireNotNull(mutableState.value.preferences)
        if (enabled) requireConnection()
        if (applyHomeAutomation != null) applyHomeAutomation.invoke(enabled)
        else preferenceStore.save(preferences.copy(homeAutodetection = false))
        checkOwner()
        val updated = preferenceStore.read(preferences.pairingId)
        checkOwner()
        if (updated.homeAutodetection != enabled) throw RouterSetupFailure(RouterWizardError.UNAVAILABLE)
        publish(mutableState.value.copy(preferences = updated, homeAutomationEffective = enabled && homeAutomationActive()))
    }

    suspend fun refreshRuntimeStatus() = operation(indicateBusy = false) {
        val current = mutableState.value
        if (current.stage != RouterControlStage.CONNECTED || current.preferences?.enabled != true ||
            current.snapshot == null || current.updates.running || current.vpnServer.running) return@operation
        val connection = requireConnection(allowUpdates = true)
        val observed = provisioner.snapshot(connection.first, connection.second)
        checkOwner()
        if (observed.revision < requireNotNull(mutableState.value.snapshot).revision) return@operation
        observeSmartServer(observed)
        checkOwner()
        publish(mutableState.value.copy(snapshot = observed))
    }

    suspend fun setVpn(enabled: Boolean) = operation {
        val connection = if (enabled) requireConnection() else requireStopConnection()
        val selected = mutableState.value.selectedServerId
        if (enabled && selected != null && mutableState.value.snapshot?.desiredServerId == selected) return@operation
        val result = provisioner.setRunning(connection.first, connection.second, selected, enabled)
        checkOwner()
        if (enabled && result.desiredServerId != selected || !enabled && result.desiredServerId != null) throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        publish(mutableState.value.copy(snapshot = result,
            updates = if (enabled) mutableState.value.updates else mutableState.value.updates.copy(plan = null)))
        if (!enabled && mutableState.value.updates.supported == true) readUpdateState(preparePlan = false)
    }

    suspend fun setLanClient(id: String, enabled: Boolean) = operation {
        val connection = requireConnection()
        val result = provisioner.setLanClientVpn(connection.first, connection.second, id, enabled,
            mutableState.value.lanDevices.singleOrNull { it.id == id })
        checkOwner()
        if (result.devices.mapNotNull { it as? JsonObject }.singleOrNull { it.text("id") == id }?.text("route") !=
            (if (enabled) "vpn" else "direct")) throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        publish(mutableState.value.copy(snapshot = result, lanDevices = mutableState.value.lanDevices.map {
            if (it.id == id) it.copy(route = if (enabled) "vpn" else "direct") else it
        }))
    }

    suspend fun copyServer(id: String) = operation {
        val content = serverContent(id)
        checkOwner()
        copyProfile(content)
        checkOwner()
    }

    suspend fun showServer(id: String, onProfile: (String) -> Unit) = operation {
        val content = serverContent(id)
        checkOwner()
        onProfile(content)
        checkOwner()
    }

    private suspend fun serverContent(id: String): String {
        val connection = requireConnection()
        if (mutableState.value.snapshot?.servers?.none { it.text("id") == id } != false) throw RouterApiFailure(RouterApiError.UNSUPPORTED)
        val result = connection.first.mobileRequest(RouterMobileAction.PROFILE_GET,
            JsonObject(mapOf("token" to JsonPrimitive(connection.second.token()), "id" to JsonPrimitive(id))))
        checkOwner()
        if (result.text("id") != id) throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        return routerWholeShareContent(result)
    }

    suspend fun removeServer(id: String, expectedRevision: Long) = operation {
        val connection = requireConnection()
        if (mutableState.value.snapshot?.revision != expectedRevision ||
            mutableState.value.snapshot?.servers?.none { it.text("id") == id } != false) throw RouterApiFailure(RouterApiError.CONFLICT)
        connection.first.mobileRequest(RouterMobileAction.PROFILE_DELETE, JsonObject(mapOf(
            "token" to JsonPrimitive(connection.second.token()), "revision" to JsonPrimitive(expectedRevision), "id" to JsonPrimitive(id))))
        checkOwner()
        val result = provisioner.snapshot(connection.first, connection.second)
        checkOwner()
        if (result.servers.any { it.text("id") == id }) throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        publish(mutableState.value.copy(snapshot = result, selectedServerId = if (mutableState.value.selectedServerId == id) result.desiredServerId else mutableState.value.selectedServerId))
    }

    suspend fun copyRemoteClient(id: String) = operation {
        val content = remoteClientContent(id)
        checkOwner()
        copyProfile(content)
        checkOwner()
    }

    suspend fun showRemoteClient(id: String, onProfile: (String) -> Unit) = operation {
        val content = remoteClientContent(id)
        checkOwner()
        onProfile(content)
        checkOwner()
    }

    suspend fun importRemoteClient(id: String) = operation {
        val exported = remoteClientExport(id)
        val content = exported.uri
        checkOwner()
        val name = mutableState.value.snapshot?.clients?.singleOrNull { it.text("id") == id }?.text("name")
            ?: throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        val importText = routerClientProfileForImport(content, name)
        val marker = exported.marker
        val imported = if (marker != null && importAuthenticatedProfile != null)
            importAuthenticatedProfile.invoke(RouterAuthenticatedProfileImport(importText, content, marker, requireNotNull(pairing).identity))
        else importProfile(importText)
        if (!imported) {
            checkOwner()
            publish(mutableState.value.copy(error = RouterWizardError.PROFILE))
            return@operation
        }
        checkOwner()
    }

    suspend fun createRemoteClient(name: String) = operation {
        if (!mutableState.value.hasConfirmedDirectIp) throw RouterApiFailure(RouterApiError.UNSUPPORTED)
        if (mutableState.value.clientCreationNeedsSetup) return@operation
        if (!validRouterClientName(name)) {
            publish(mutableState.value.copy(error = RouterWizardError.UNSUPPORTED))
            return@operation
        }
        val connection = requireConnection()
        val result = provisioner.createRemoteClient(connection.first, connection.second, name)
        checkOwner()
        publish(mutableState.value.copy(snapshot = result.snapshot, clientCreationNeedsSetup = !result.provisioned))
    }

    private suspend fun remoteClientContent(id: String): String = remoteClientExport(id).uri

    private suspend fun remoteClientExport(id: String): RouterClientExport {
        val connection = requireConnection()
        val client = mutableState.value.snapshot?.clients?.singleOrNull { it.text("id") == id } ?: throw RouterApiFailure(RouterApiError.UNSUPPORTED)
        val provisioned = (client["provisioned"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        val content = provisioner.remoteClientExport(connection.first, connection.second, id, provisioned, requireNotNull(pairing).id)
        checkOwner()
        return content
    }

    suspend fun removeRemoteClient(id: String, expectedRevision: Long) = operation {
        val connection = requireConnection()
        val snapshot = mutableState.value.snapshot ?: throw RouterApiFailure(RouterApiError.CONFLICT)
        val client = snapshot.clients.singleOrNull { it.text("id") == id } ?: throw RouterApiFailure(RouterApiError.CONFLICT)
        if (snapshot.revision != expectedRevision) throw RouterApiFailure(RouterApiError.CONFLICT)
        val provisioned = (client["provisioned"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        val result = provisioner.removeRemoteClient(connection.first, connection.second, id, expectedRevision, provisioned)
        checkOwner()
        publish(mutableState.value.copy(snapshot = result))
    }

    suspend fun unpair(expectedId: String, deletePackage: Boolean = false) = operation {
        if (expectedId != mutableState.value.preferences?.pairingId) {
            val inventory = pairingStore.listSaved()
            checkOwner()
            if (deletePackage || inventory.selectedId != mutableState.value.selectedRouterId ||
                inventory.selectedId == expectedId || inventory.routers.none { it.id == expectedId })
                throw RouterApiFailure(RouterApiError.CONFLICT)
            pairingStore.remove(expectedId)
            checkOwner()
            refreshInventory()
            publish(mutableState.value)
            return@operation
        }
        val saved = pairing ?: pairingStore.read()?.also { checkOwner(); pairing = it } ?: throw RouterApiFailure(RouterApiError.CONFLICT)
        if (saved.id != expectedId || mutableState.value.preferences?.pairingId != expectedId) throw RouterApiFailure(RouterApiError.CONFLICT)
        applyHomeAutomation?.invoke(false)
        checkOwner()
        if (deletePackage) {
            val preferences = requireNotNull(mutableState.value.preferences)
            if (api == null || session == null) reopen(preferences.copy(enabled = true))
            val connection = requireConnection()
            val revision = requireNotNull(mutableState.value.snapshot).revision
            provisioner.removePackage(connection.first, connection.second, expectedId, revision)
            checkOwner()
        }
        pairingStore.remove(expectedId)
        checkOwner()
        clearConnection()
        pairing?.clear(); pairing = null
        refreshInventory()
        if (savedInventory.selectedId == null) publish(RouterControlState(stage = RouterControlStage.UNPAIRED))
        else {
            val next = requireNotNull(pairingStore.read())
            pairing = next
            val preferences = preferenceStore.read(next.id)
            checkOwner()
            publish(RouterControlState(stage = if (preferences.enabled) RouterControlStage.OFFLINE else RouterControlStage.DISABLED,
                preferences = preferences, address = next.identity.endpoint.address))
        }
    }

    private suspend fun loadActionPairing(preferences: RouterControlPreferences) {
        val saved = pairingStore.read() ?: throw RouterApiFailure(RouterApiError.CONFLICT)
        try {
            checkOwner()
            if (saved.id != preferences.pairingId) throw RouterApiFailure(RouterApiError.CONFLICT)
            pairing?.clear()
            pairing = saved
        } catch (error: Exception) { saved.clear(); throw error }
    }

    private suspend fun reopen(preferences: RouterControlPreferences, readOnly: Boolean = false, persistPreferences: Boolean = true) {
        clearConnection()
        val saved = pairing ?: pairingStore.read()?.also { checkOwner(); pairing = it } ?: throw RouterApiFailure(RouterApiError.PIN)
        val client = transport.openManagement(saved, preferences)
        if (client.identity != saved.identity) { client.close(); throw RouterApiFailure(RouterApiError.IDENTITY) }
        try { checkOwner(); api = client } catch (error: Exception) { client.close(); throw error }
        val unlocked = client.unlockPairing(saved)
        try { checkOwner(); session = unlocked } catch (error: Exception) { unlocked.close(); throw error }
        val current = provisioner.snapshot(client, unlocked)
        checkOwner()
        val observedPreferences = preferences.withPublicIpv4Evidence(current.publicIpv4Available)
            .copy(authenticatedHomeServerEnabled = routerVpnServerEnabled(current)).let {
            if (client.homeVpnBound) it else transport.authenticatedWifiPreferences(saved, it)
        }
        if (persistPreferences && observedPreferences != preferences) {
            preferenceStore.save(observedPreferences)
            checkOwner()
        }
        if (!saved.active) {
            val activated = saved.activated()
            try { pairingStore.save(activated); checkOwner() } finally { activated.clear() }
        }
        val automation = observedPreferences.homeAutodetection && if (smartHomeAvailable) smartHomeActive()
            else homeAutomationAvailable && applyHomeAutomation != null && homeAutomationActive()
        publish(mutableState.value.copy(stage = RouterControlStage.CONNECTED, preferences = observedPreferences, snapshot = current,
            selectedServerId = current.desiredServerId ?: mutableState.value.selectedServerId?.takeIf { id -> current.servers.any { it.text("id") == id } }
                ?: current.servers.singleOrNull()?.text("id"), homeAutomationEffective = automation,
            clientCreationNeedsSetup = mutableState.value.clientCreationNeedsSetup && current.clients.none { it["provisioned"] == JsonPrimitive(true) },
            remoteClients = unknownRouterClientStatuses(current), routerCountryCode = null))
        if (readOnly) return
        // Discover a surviving manual/automatic transaction before any normal country-policy write.
        readUpdateState()
        if (mutableState.value.updates.running) return
        val country = provisioner.routerCountry(client, unlocked)
        checkOwner()
        publish(mutableState.value.copy(routerCountryCode = country))
        if (observedPreferences.countryAuto && country != null) {
            val live = mutableState.value
            val old = live.preferences ?: observedPreferences
            val direct = routerCountryDirectControl(old, country, live.snapshot).checked
            val display = old.copy(countryCode = country, countrySitesDirect = direct)
            if (display != old) {
                preferenceStore.save(display); checkOwner()
                publish(mutableState.value.copy(preferences = display))
            }
        }
    }

    private fun requireStopConnection(): Pair<RouterPanelApi, RouterPanelSession> {
        val current = mutableState.value
        if (!current.updates.running) return requireConnection()
        if (current.stage != RouterControlStage.CONNECTED || current.preferences?.enabled != true ||
            current.snapshot?.desiredServerId == null) throw RouterUpdateFailure(RouterUpdateFailureReason.BUSY)
        return requireNotNull(api) to requireNotNull(session)
    }

    private fun requireConnection(allowUpdates: Boolean = false): Pair<RouterPanelApi, RouterPanelSession> {
        if (!allowUpdates && mutableState.value.updates.running) throw RouterUpdateFailure(RouterUpdateFailureReason.BUSY)
        if (!allowUpdates && (mutableState.value.vpnServer.running || mutableState.value.vpnServer.requiresRecovery))
            throw RouterApiFailure(RouterApiError.CONFLICT)
        if (mutableState.value.stage != RouterControlStage.CONNECTED || mutableState.value.preferences?.enabled != true) throw RouterApiFailure(RouterApiError.UNSUPPORTED)
        return requireNotNull(api) to requireNotNull(session)
    }

    private suspend fun operation(indicateBusy: Boolean = true, block: suspend () -> Unit) = supervisorScope {
        val epoch = generation.get()
        val child = async(start = CoroutineStart.UNDISPATCHED) { runOperation(epoch, indicateBusy, block) }
        try { child.await() }
        catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive || epoch == generation.get()) throw cancelled
        }
    }

    private suspend fun runOperation(epoch: Long, indicateBusy: Boolean, block: suspend () -> Unit) = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (epoch != generation.get()) throw CancellationException("router_control_closed")
        owner = epoch
        val ticket = owner
        val job = currentCoroutineContext()[Job]
        active.set(job)
        if (indicateBusy) publish(mutableState.value.copy(busy = true, error = null))
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: RouterUpdateFailure) {
            publish(mutableState.value.copy(updates = mutableState.value.updates.copy(plan = null, failure = failure.reason)))
        }
        catch (failure: RouterEngineFailure) {
            if (failure.reason == RouterEngineFailureReason.STALE_SESSION) {
                clearConnection()
                publish(mutableState.value.copy(stage = RouterControlStage.OFFLINE, snapshot = null,
                    engines = RouterEngineControlState(failure = failure.reason)))
            } else publish(mutableState.value.copy(engines = mutableState.value.engines.copy(plan = null, failure = failure.reason)))
        }
        catch (failure: RouterApiFailure) {
            val disconnect = failure.kind in setOf(RouterApiError.NETWORK, RouterApiError.IDENTITY, RouterApiError.PIN,
                RouterApiError.CHANGE_PIN, RouterApiError.LOCKED)
            val error = if (failure.kind == RouterApiError.NETWORK && api?.homeVpnBound == true)
                RouterWizardError.HOME_VPN else apiError(failure.kind)
            fail(ticket, error, disconnect)
        }
        catch (_: RouterHomeVpnRequired) { fail(ticket, RouterWizardError.HOME_VPN) }
        catch (_: RouterWifiRequired) { fail(ticket, RouterWizardError.WIFI) }
        catch (_: RouterVpnSocketUnavailable) { fail(ticket, RouterWizardError.VPN_ACTIVE) }
        catch (_: RouterLocalNetworkPermissionRequired) { fail(ticket, RouterWizardError.LOCAL_NETWORK_PERMISSION) }
        catch (failure: RouterSetupFailure) { fail(ticket, failure.kind, false) }
        catch (_: IOException) { fail(ticket, RouterWizardError.NETWORK) }
        catch (_: IllegalArgumentException) { fail(ticket, RouterWizardError.UNSUPPORTED, false) }
        catch (_: IllegalStateException) { fail(ticket, RouterWizardError.UNAVAILABLE) }
        finally {
            active.compareAndSet(job, null)
            if (indicateBusy && ticket == generation.get()) publish(mutableState.value.copy(busy = false))
        }
    }

    private fun apiError(kind: RouterApiError): RouterWizardError = when (kind) {
        RouterApiError.PIN, RouterApiError.CHANGE_PIN -> RouterWizardError.PIN
        RouterApiError.IDENTITY -> RouterWizardError.IDENTITY
        RouterApiError.LOCKED -> RouterWizardError.LOCKED
        RouterApiError.UNSUPPORTED -> RouterWizardError.UNSUPPORTED
        RouterApiError.INSECURE_TLS -> RouterWizardError.PROFILE
        RouterApiError.NETWORK -> RouterWizardError.NETWORK
        RouterApiError.LIMIT -> RouterWizardError.LIMIT
        else -> RouterWizardError.UNAVAILABLE
    }

    private fun fail(ticket: Long, error: RouterWizardError, disconnect: Boolean = true) {
        if (ticket != generation.get()) return
        if (!disconnect && mutableState.value.stage == RouterControlStage.CONNECTED && api != null && session != null) {
            publish(mutableState.value.copy(error = error))
            return
        }
        clearConnection()
        publish(mutableState.value.copy(stage = if (error == RouterWizardError.PIN) RouterControlStage.PAIRING_REQUIRED
            else if (mutableState.value.preferences?.enabled == true) RouterControlStage.OFFLINE else RouterControlStage.DISABLED,
            error = error, snapshot = null, engines = RouterEngineControlState()))
    }
    private suspend fun checkOwner() { currentCoroutineContext().ensureActive(); if (owner != generation.get()) throw CancellationException("router_control_closed") }
    private fun publish(value: RouterControlState) {
        if (owner != generation.get()) throw CancellationException("router_control_closed")
        mutableState.update { current ->
            val changed = current.snapshot?.revision != value.snapshot?.revision ||
                current.snapshot?.clients?.map { it.text("id") } != value.snapshot?.clients?.map { it.text("id") }
            val statuses = if (value.snapshot == null) emptyList() else if (changed)
                unknownRouterClientStatuses(value.snapshot) else value.remoteClients
            value.copy(revision = current.revision + 1, remoteClients = statuses,
                vpnServer = value.vpnServer.takeIf { it.ownerPairingId == null || it.ownerPairingId == value.selectedRouterId } ?: RouterVpnServerControlState(),
                about = value.about.takeIf { value.aboutPairingId == value.selectedRouterId },
                entryAccess = value.entryAccess ?: current.entryAccess,
                lanDevices = value.lanDevices.takeIf { value.snapshot != null }.orEmpty(),
                lanDevicesLoaded = value.lanDevicesLoaded && value.snapshot != null,
                lanDevicesLoading = value.lanDevicesLoading && value.snapshot != null,
                savedRouters = savedInventory.routers.map { it.copy(status = savedStatuses[it.id] ?: RouterSavedStatus.UNKNOWN, packageVersion = savedVersions[it.id]) },
                selectedRouterId = savedInventory.selectedId,
                routerCountryCode = value.routerCountryCode.takeIf { value.snapshot != null },
                homeAutomationEffective = value.homeAutomationEffective && observedHomeActive != false)
        }
    }
    private fun clearConnection() { session?.close(); session = null; api?.close(); api = null; verifiedIdentity = null }
    override fun close() {
        countryConfirmation.set(null)
        savedStatuses = emptyMap()
        generation.incrementAndGet(); active.getAndSet(null)?.cancel(); clearConnection(); pairing?.clear(); pairing = null
        mutableState.update { current ->
            if (current.preferences == null) current.copy(busy = false)
            else current.copy(stage = if (current.preferences.enabled) RouterControlStage.OFFLINE else RouterControlStage.DISABLED,
                snapshot = null, lanDevices = emptyList(), lanDevicesLoading = false, lanDevicesLoaded = false, lanDevicesFailed = false,
                remoteClients = emptyList(), routerCountryCode = null, busy = false,
                savedRouters = current.savedRouters.map { it.copy(status = RouterSavedStatus.UNKNOWN) }, engines = RouterEngineControlState(), updates = RouterUpdateControlState())
        }
    }
}

private fun JsonObject.text(field: String): String? = (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
