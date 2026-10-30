package com.foxhole.guard.router

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class RouterWizardCoordinator(
    private val transport: RouterWizardTransport,
    private val storage: RouterPairingStore,
    private val deviceName: String,
    private val setup: RouterSetupService?,
    private val provisioner: RouterWizardProvisioner,
    private val exportProfile: suspend (String) -> RouterProfileSelection?,
    private val importRemoteProfile: suspend (String) -> Boolean,
    private val tlsPort: Int = 8443,
    private val preferenceStore: RouterControlPreferenceStore? = null,
    private val beforeRouterSelection: (suspend () -> Unit)? = null,
    private val importAuthenticatedProfile: (suspend (RouterAuthenticatedProfileImport) -> Boolean)? = null,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(RouterWizardState())
    val state: StateFlow<RouterWizardState> = mutableState.asStateFlow()
    val installationCatalog: RouterInstallCatalog? get() = setup?.installCatalog
    val setupAvailable: Boolean get() = setup != null && (installationCatalog == null || installationCatalog?.defaultSelection != null ||
        installationCatalog?.let { it.schema == 2 && it.availableProtocols.isNotEmpty() } == true)
    val supportsHomeAutodetection: Boolean get() = provisioner.supportsHomeAutodetection
    val supportsRemoteProfile: Boolean get() = "hysteria2" in mutableState.value.inboundProtocols
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val progressGeneration = AtomicLong()
    private var operationOwner = -1L
    private val activeOperation = AtomicReference<Job?>()
    private var identity: RouterTlsIdentity? = null
    private var hostKey: ByteArray? = null
    private var api: RouterPanelApi? = null
    private var pinSession: RouterPanelSession? = null
    private var pairedSession: RouterPanelSession? = null
    private var pairing: RouterPairing? = null
    private var proposal: RouterInstallProposal? = null
    private var operationRecovery: RouterWizardState? = null
    private var resetFoxHoleRequested = false
    private var resetPairingId: String? = null
    private var freshPairingRequired = false
    private var autoUpdateAttempt: RouterUpdateStatus? = null
    private var autoUpdateApplied: Boolean? = null
    private var completedSetup: RouterWizardState? = null
    private var addingNew = false
    private var pairingRetry = false
    val isAddingRouter: Boolean get() = addingNew

    fun hasPhysicalWifi(): Boolean = transport.hasPhysicalWifi()

    fun begin(path: RouterWizardPath, homeCountry: String?, addingNew: Boolean = false,
        keepNotFoundPresentation: Boolean = false) {
        val notFound = mutableState.value.discovery?.takeIf { keepNotFoundPresentation && it.kind == RouterDiscoveryKind.NOT_FOUND }
        close()
        this.addingNew = addingNew
        if (addingNew) transport.beginAddition()
        mutableState.value = RouterWizardState(stage = RouterWizardStage.ADDRESS, path = path, discovery = notFound,
            address = transport.gatewayHint()?.takeIf { routerLanAddress(it) != null }.orEmpty(),
            installSelection = if (path == RouterWizardPath.SETUP) installationCatalog?.defaultSelection else null,
            options = RouterWizardOptions(countryCode = homeCountry?.uppercase()?.takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty(),
                licenseGeneration = generation.get()))
    }

    fun beginPairingRetry() {
        begin(RouterWizardPath.PAIR, null)
        pairingRetry = true
    }

    suspend fun findRouter() = operation {
        requireAdditionCapacity()
        if (!transport.hasPhysicalWifi()) throw RouterWifiRequired()
        val address = transport.gateway()?.takeIf { routerLanAddress(it) != null } ?: throw RouterWifiRequired()
        val endpoint = RouterEndpoint(address, tlsPort)
        transport.requireDiscoveryAccess(endpoint)
        ensureOwner()
        hostKey?.fill(0); hostKey = null
        identity = null
        api?.close(); api = null
        resetFoxHoleRequested = false
        resetPairingId = null
        freshPairingRequired = false
        val notFound = mutableState.value.discovery?.takeIf { it.kind == RouterDiscoveryKind.NOT_FOUND }
        publish(mutableState.value.copy(stage = RouterWizardStage.ENTRY, address = address, discovery = notFound, fingerprint = ""))
        operationRecovery = mutableState.value.copy(busy = false)
        val candidate = try { transport.inspectIdentity(endpoint) }
        catch (error: RouterLocalNetworkPermissionRequired) { throw error }
        catch (error: RouterWifiRequired) { throw error }
        catch (error: RouterVpnSocketUnavailable) { throw error }
        catch (error: RouterIdentityChanged) { throw error }
        catch (_: IOException) { null }
        ensureOwner()
        if (candidate != null) {
            rejectChangedIdentity(candidate)
            val client = transport.api(candidate)
            val status = try { client.packageStatus() }
            catch (_: RouterApiFailure) { null }
            finally { client.close() }
            ensureOwner()
            if (status != null) {
                identity = candidate
                publish(mutableState.value.copy(discovery = RouterDiscoveryResult(address, RouterDiscoveryKind.INSTALLED,
                    status.pinConfigured, status.resetSupported, UUID.randomUUID().toString())))
            } else publish(mutableState.value.copy(discovery = unknownDiscovery(address)))
            return@operation
        }
        val service = setup
        val ssh = if (service == null) null else try { service.inspectSshIdentity(address) }
        catch (error: RouterLocalNetworkPermissionRequired) { throw error }
        catch (error: RouterWifiRequired) { throw error }
        catch (error: RouterVpnSocketUnavailable) { throw error }
        catch (_: IOException) { null }
        try {
            ensureOwner()
            if (ssh != null && ssh.size in 1..8192) {
                hostKey?.fill(0); hostKey = ssh.copyOf()
                publish(mutableState.value.copy(discovery = RouterDiscoveryResult(address, RouterDiscoveryKind.SSH_AVAILABLE,
                    discoveryId = UUID.randomUUID().toString())))
            } else publish(mutableState.value.copy(discovery = RouterDiscoveryResult(address, RouterDiscoveryKind.NOT_FOUND,
                discoveryId = UUID.randomUUID().toString())))
        } finally { ssh?.fill(0) }
    }

    suspend fun connectDiscovered(resetFoxHole: Boolean = false) = operation {
        val found = mutableState.value.discovery ?: fail(RouterWizardError.UNAVAILABLE)
        if (found.kind != RouterDiscoveryKind.INSTALLED) fail(RouterWizardError.UNAVAILABLE)
        val pinned = identity ?: fail(RouterWizardError.IDENTITY)
        if (pinned.endpoint.address != found.address) fail(RouterWizardError.IDENTITY)
        rejectChangedIdentity(pinned)
        if (resetFoxHole && !found.resetSupported) fail(RouterWizardError.UNSUPPORTED)
        api?.close(); api = transport.api(pinned)
        resetPairingId = if (resetFoxHole) {
            val saved = storage.read()
            try {
                ensureOwner()
                if (saved != null && saved.identity != pinned) fail(RouterWizardError.IDENTITY)
                saved?.id
            } finally { saved?.clear() }
        } else null
        resetFoxHoleRequested = resetFoxHole
        autoUpdateAttempt = null; autoUpdateApplied = null; completedSetup = null
        publish(mutableState.value.copy(path = RouterWizardPath.PAIR, installedInThisWizard = false, autoUpdateUnconfirmed = false,
            stage = if (found.pinConfigured == true) RouterWizardStage.LOGIN else RouterWizardStage.PIN_SETUP_REQUIRED))
    }

    suspend fun installDiscovered() = operation {
        val found = mutableState.value.discovery ?: fail(RouterWizardError.UNAVAILABLE)
        if (found.kind != RouterDiscoveryKind.SSH_AVAILABLE || hostKey == null) fail(RouterWizardError.UNAVAILABLE)
        val catalog = installationCatalog ?: fail(RouterWizardError.UNAVAILABLE)
        val selected = catalog.defaultSelection
        if (selected == null && catalog.schema != 2) fail(RouterWizardError.UNAVAILABLE)
        publish(mutableState.value.copy(path = RouterWizardPath.SETUP, stage = RouterWizardStage.PROTOCOLS,
            address = found.address, installSelection = selected))
    }

    private fun unknownDiscovery(address: String) = RouterDiscoveryResult(address, RouterDiscoveryKind.UNKNOWN,
        discoveryId = UUID.randomUUID().toString())

    private suspend fun rejectChangedIdentity(candidate: RouterTlsIdentity) {
        val saved = storage.readAll()
        try {
            ensureOwner()
            val known = saved.singleOrNull { it.identity.certificateSha256 == candidate.certificateSha256 }
            if (known != null) {
                val current = storage.listSaved().selectedId
                if (current != known.id) { beforeRouterSelection?.invoke(); ensureOwner(); storage.selectSaved(known.id); ensureOwner() }
                if (known.identity != candidate) {
                    val moved = known.atVerifiedIdentity(candidate)
                    try { storage.save(moved); ensureOwner() } finally { moved.clear() }
                }
            } else if (!addingNew && saved.isNotEmpty()) fail(RouterWizardError.IDENTITY)
        } finally { saved.forEach(RouterPairing::clear) }
    }

    private suspend fun requireAdditionCapacity() {
        if (addingNew && storage.listSaved().routers.size >= MAX_SAVED_ROUTERS) fail(RouterWizardError.LIMIT)
        ensureOwner()
    }

    suspend fun inspectAddress(address: String) = operation {
        requireAdditionCapacity()
        val endpoint = try { RouterEndpoint(address.trim(), tlsPort) } catch (_: IllegalArgumentException) { fail(RouterWizardError.ADDRESS) }
        if (mutableState.value.path == RouterWizardPath.SETUP) {
            val service = setup ?: fail(RouterWizardError.UNAVAILABLE)
            val candidate = service.inspectSshIdentity(endpoint.address)
            val fingerprint = try {
                ensureOwner()
                require(candidate.size in 1..8192)
                hostKey?.fill(0)
                hostKey = candidate.copyOf()
                "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(candidate))
            } finally { candidate.fill(0) }
            publish(mutableState.value.copy(stage = RouterWizardStage.IDENTITY, address = endpoint.address, fingerprint = fingerprint))
        } else {
            val candidate = transport.inspectIdentity(endpoint)
            ensureOwner()
            val saved = storage.readAll()
            val changed = try { ensureOwner(); !addingNew && saved.isNotEmpty() &&
                saved.none { it.identity.certificateSha256 == candidate.certificateSha256 } }
                finally { saved.forEach(RouterPairing::clear) }
            if (!changed && !pairingRetry) rejectChangedIdentity(candidate)
            identity = candidate
            publish(mutableState.value.copy(stage = RouterWizardStage.IDENTITY, address = endpoint.address,
                fingerprint = candidate.displayFingerprint, identityChanged = changed))
        }
    }

    suspend fun acceptIdentity() = operation {
        requireAdditionCapacity()
        if (mutableState.value.stage != RouterWizardStage.IDENTITY) fail(RouterWizardError.UNAVAILABLE)
        if (mutableState.value.identityChanged) fail(RouterWizardError.IDENTITY)
        if (mutableState.value.path == RouterWizardPath.PAIR) {
            api = transport.api(requireNotNull(identity))
            publish(mutableState.value.copy(stage = RouterWizardStage.LOGIN, error = null))
        } else {
            val catalog = installationCatalog ?: fail(RouterWizardError.UNAVAILABLE)
            val selected = mutableState.value.installSelection?.takeIf { it.catalogSha256 == catalog.sha256 && catalog.accepts(it.protocols, it.includeServer) }
                ?: catalog.defaultSelection
            if (selected == null && catalog.schema != 2) fail(RouterWizardError.UNAVAILABLE)
            publish(mutableState.value.copy(stage = RouterWizardStage.PROTOCOLS, installSelection = selected, error = null))
        }
    }

    fun installProtocols(protocols: Set<RouterInstallProtocol>, includeServer: Boolean = false) {
        if (mutableState.value.stage != RouterWizardStage.PROTOCOLS || mutableState.value.busy) return
        val selected = installationCatalog?.selection(protocols, includeServer) ?: return
        mutableState.value = mutableState.value.copy(installSelection = selected, error = null)
    }

    suspend fun confirmInstallProtocols() = operation {
        if (mutableState.value.stage != RouterWizardStage.PROTOCOLS) fail(RouterWizardError.UNAVAILABLE)
        val selected = mutableState.value.installSelection ?: fail(RouterWizardError.UNAVAILABLE)
        val catalog = installationCatalog ?: fail(RouterWizardError.UNAVAILABLE)
        if (selected.catalogSha256 != catalog.sha256 || !catalog.accepts(selected.protocols, selected.includeServer)) fail(RouterWizardError.UNSUPPORTED)
        requireRouterSetupLicense(mutableState.value.options.licenseAccepted)
        publish(mutableState.value.copy(stage = RouterWizardStage.LOGIN))
    }

    suspend fun login(secret: CharArray) {
        try { operation {
            requireAdditionCapacity()
            if (mutableState.value.stage != RouterWizardStage.LOGIN) fail(RouterWizardError.UNAVAILABLE)
            if (mutableState.value.path == RouterWizardPath.SETUP) {
                val service = setup ?: fail(RouterWizardError.UNAVAILABLE)
                val selected = mutableState.value.installSelection ?: fail(RouterWizardError.UNAVAILABLE)
                publish(mutableState.value.copy(stage = RouterWizardStage.CHECKING))
                requireRouterSetupLicense(mutableState.value.options.licenseAccepted)
                val plan = service.inspect(mutableState.value.address, requireNotNull(hostKey), secret, selected,
                    mutableState.value.options.licenseAccepted, progressReporter())
                try {
                    ensureOwner()
                    if (plan.binding.selection != selected || !requireNotNull(installationCatalog).matches(plan.binding,
                            plan.openWrtVersion, plan.manifestSha256)) fail(RouterWizardError.UNSUPPORTED)
                    proposal = plan
                } catch (error: Exception) { plan.close(); throw error }
                publish(mutableState.value.copy(stage = RouterWizardStage.INSTALL,
                    openWrtVersion = plan.openWrtVersion, architecture = plan.architecture, storageMode = plan.storageMode))
            } else {
                pinSession?.close()
                val unlocked = requireNotNull(api).unlock(secret)
                val resetting = resetFoxHoleRequested
                val session = if (resetting) {
                    try { requireNotNull(api).resetFoxHole(unlocked) }
                    catch (failure: Exception) { unlocked.close(); throw failure }
                } else unlocked
                try {
                    ensureOwner()
                    if (resetting) {
                        resetFoxHoleRequested = false
                        clearResetPairingAfterAcknowledgment()
                        freshPairingRequired = true
                    }
                    pinSession = session
                } catch (error: Exception) { session.close(); throw error }
                if (requireNotNull(pinSession).changePinRequired) publish(mutableState.value.copy(stage = RouterWizardStage.NEW_PIN,
                    licenseRequired = requireNotNull(pinSession).licenseRequired, licenseVersion = requireNotNull(pinSession).licenseVersion))
                else completePairing()
            }
        } } finally { secret.fill('\u0000') }
    }

    suspend fun install() = operation {
        if (mutableState.value.stage != RouterWizardStage.INSTALL) fail(RouterWizardError.UNAVAILABLE)
        requireRouterSetupLicense(mutableState.value.options.licenseAccepted)
        val service = setup ?: fail(RouterWizardError.UNAVAILABLE)
        val plan = requireNotNull(proposal)
        if (plan.binding.selection != mutableState.value.installSelection ||
            !requireNotNull(installationCatalog).matches(plan.binding, plan.openWrtVersion, plan.manifestSha256)) fail(RouterWizardError.UNSUPPORTED)
        val result = try { service.install(plan, mutableState.value.options.licenseAccepted, progressReporter()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            ensureOwner()
            proposal?.close(); proposal = null
            hostKey?.fill(0); hostKey = null
            operationRecovery = mutableState.value.copy(stage = RouterWizardStage.ADDRESS, fingerprint = "",
                openWrtVersion = "", architecture = "", storageMode = null)
            throw failure
        }
        try {
            ensureOwner()
            proposal?.close(); proposal = null
            identity = result.identity
            publish(mutableState.value.copy(installedInThisWizard = true))
            operationRecovery = mutableState.value.copy(stage = RouterWizardStage.LOGIN, path = RouterWizardPath.PAIR,
                fingerprint = result.identity.displayFingerprint, identityChanged = false)
            api = transport.api(result.identity)
            val bootstrap = result.takeBootstrapCredential()
            if (bootstrap == null) {
                publish(mutableState.value.copy(stage = RouterWizardStage.LOGIN, path = RouterWizardPath.PAIR,
                    fingerprint = result.identity.displayFingerprint, identityChanged = false))
            } else try {
                val session = requireNotNull(api).unlock(bootstrap)
                try {
                    ensureOwner()
                    if (!session.changePinRequired) fail(RouterWizardError.IDENTITY)
                    pinSession?.close(); pinSession = session
                } catch (error: Exception) { session.close(); throw error }
                publish(mutableState.value.copy(stage = RouterWizardStage.NEW_PIN, path = RouterWizardPath.PAIR,
                    fingerprint = result.identity.displayFingerprint, identityChanged = false,
                    licenseRequired = requireNotNull(pinSession).licenseRequired, licenseVersion = requireNotNull(pinSession).licenseVersion))
            } finally { bootstrap.fill('\u0000') }
        } finally { result.close() }
    }

    suspend fun changePin(newPin: CharArray) {
        try { operation {
            if (mutableState.value.stage != RouterWizardStage.NEW_PIN) fail(RouterWizardError.UNAVAILABLE)
            val pending = requireNotNull(pinSession)
            val needsLicense = pending.licenseRequired || mutableState.value.installedInThisWizard
            val accepted = needsLicense && mutableState.value.options.licenseAccepted
            if (needsLicense) requireRouterSetupLicense(accepted)
            val session = requireNotNull(api).changePin(pending, newPin, accepted,
                if (accepted) ROUTER_SETUP_LICENSE_VERSION else 0)
            try {
                ensureOwner(); pinSession = session
                publish(mutableState.value.copy(licenseRequired = session.licenseRequired, licenseVersion = session.licenseVersion))
            } catch (error: Exception) { session.close(); throw error }
            completePairing()
        } } finally { newPin.fill('\u0000') }
    }

    fun options(options: RouterWizardOptions) {
        val current = mutableState.value
        if (current.busy) return
        if (current.path == RouterWizardPath.SETUP && current.stage in setOf(RouterWizardStage.ADDRESS, RouterWizardStage.IDENTITY,
                RouterWizardStage.PROTOCOLS, RouterWizardStage.LOGIN, RouterWizardStage.INSTALL)) {
            // An outgoing wizard's choice cannot authorize a fresh setup.
            if (options.licenseGeneration != current.options.licenseGeneration) return
            mutableState.value = current.copy(options = current.options.copy(
                autoUpdateEnabled = if (current.stage == RouterWizardStage.PROTOCOLS) options.autoUpdateEnabled else current.options.autoUpdateEnabled,
                licenseAccepted = options.licenseAccepted), error = null)
            return
        }
        if (current.stage == RouterWizardStage.NEW_PIN && (current.licenseRequired || current.installedInThisWizard)) {
            if (options.licenseGeneration != current.options.licenseGeneration) return
            mutableState.value = current.copy(options = current.options.copy(licenseAccepted = options.licenseAccepted), error = null)
            return
        }
        if (current.stage != RouterWizardStage.OPTIONS || current.autoUpdateUnconfirmed) return
        val normalized = options.copy(
            countryCode = if (options.countryAuto) mutableState.value.routerCountryCode.orEmpty() else options.countryCode,
            createRemoteProfile = options.publicIp && supportsRemoteProfile && options.createRemoteProfile,
            homeAutodetection = supportsHomeAutodetection && options.homeAutodetection,
            licenseAccepted = current.options.licenseAccepted, licenseGeneration = current.options.licenseGeneration)
        if (current.options != normalized) {
            completedSetup = completedSetup
                ?.takeIf { it.options.copy(autoUpdateEnabled = normalized.autoUpdateEnabled) == normalized }
                ?.copy(options = normalized)
        }
        if (normalized.autoUpdateEnabled != current.options.autoUpdateEnabled) { autoUpdateAttempt = null; autoUpdateApplied = null }
        mutableState.value = current.copy(options = normalized, error = null, autoUpdateFailure = null)
    }

    suspend fun finish() = operation {
        val options = mutableState.value.options
        if (mutableState.value.stage != RouterWizardStage.OPTIONS || !options.valid) fail(RouterWizardError.UNAVAILABLE)
        publish(mutableState.value.copy(stage = RouterWizardStage.FINISHING))
        completedSetup?.let { completed ->
            if (completed.options != options || completed.pairedId != pairing?.id) fail(RouterWizardError.UNAVAILABLE)
            configureSetupAutoUpdate(options.autoUpdateEnabled)
            publish(completed.copy(autoUpdateUnconfirmed = false, autoUpdateFailure = null))
            completedSetup = null
            return@operation
        }
        val selected = options.profileId?.let { exportProfile(it) ?: fail(RouterWizardError.PROFILE) }
        ensureOwner()
        val actualPairing = requireNotNull(pairing)
        val result = provisioner.finish(requireNotNull(api), requireNotNull(pairedSession), actualPairing, options, selected)
        ensureOwner()
        if (result.pairedId != actualPairing.id || (options.createRemoteProfile && result.remoteProfileUri == null)) fail(RouterWizardError.PROFILE)
        result.remoteProfileUri?.let { uri ->
            ensureOwner()
            val marker = result.remoteProfileMarker
            val imported = if (marker != null && importAuthenticatedProfile != null)
                importAuthenticatedProfile.invoke(RouterAuthenticatedProfileImport(uri, uri, marker, actualPairing.identity))
            else importRemoteProfile(uri)
            if (!imported) fail(RouterWizardError.PROFILE)
            ensureOwner()
        }
        preferenceStore?.let { store ->
            val old = store.read(actualPairing.id)
            ensureOwner()
            store.save(old.copy(publicIpConfirmed = options.publicIp, homeVpnManagementEnabled = options.publicIp && old.homeVpnManagementEnabled,
                countryCode = options.countryCode.ifEmpty { old.countryCode },
                countryAuto = options.countryAuto, countrySitesDirect = options.countrySitesDirect))
            ensureOwner()
        }
        val completed = mutableState.value.copy(stage = RouterWizardStage.COMPLETE, pairedId = result.pairedId,
            remoteProfileCreated = result.remoteProfileUri != null, remoteReady = result.remoteReady)
        if (mutableState.value.installedInThisWizard) {
            completedSetup = completed
            configureSetupAutoUpdate(options.autoUpdateEnabled)
        }
        publish(completed.copy(autoUpdateUnconfirmed = false, autoUpdateFailure = null))
        completedSetup = null
    }

    private suspend fun configureSetupAutoUpdate(enabled: Boolean) {
        if (!mutableState.value.installedInThisWizard || autoUpdateApplied == enabled) return
        val client = requireNotNull(api)
        val session = requireNotNull(pairedSession)
        val observed = provisioner.updateStatus(client, session)
        ensureOwner()
        if (!observed.supported) fail(RouterWizardError.UNSUPPORTED)
        val attempted = autoUpdateAttempt
        if (attempted != null) {
            // A lost acknowledgement permits status reconciliation, never a second write.
            if (observed.routerId != attempted.routerId || observed.revision < attempted.revision || observed.autoUpdate.enabled != enabled) {
                fail(RouterWizardError.SAVE)
            }
        } else {
            autoUpdateAttempt = observed
            operationRecovery = mutableState.value.copy(stage = RouterWizardStage.OPTIONS, autoUpdateUnconfirmed = true)
            publish(mutableState.value.copy(autoUpdateUnconfirmed = true))
            val saved = provisioner.setUpdateAuto(client, session, observed, enabled)
            ensureOwner()
            if (saved.routerId != observed.routerId || saved.revision < observed.revision || saved.autoUpdate.enabled != enabled) {
                fail(RouterWizardError.SAVE)
            }
        }
        autoUpdateApplied = enabled
        publish(mutableState.value.copy(autoUpdateUnconfirmed = false))
        operationRecovery = mutableState.value.copy(stage = RouterWizardStage.OPTIONS)
    }

    private suspend fun clearResetPairingAfterAcknowledgment() {
        val saved = resetPairingId?.let { storage.readById(it) }
        try {
            ensureOwner()
            if (saved != null) {
                if (saved.id != resetPairingId || saved.identity != identity) fail(RouterWizardError.UNAVAILABLE)
                storage.remove(saved.id)
                ensureOwner()
            }
        } finally { saved?.clear() }
        val remaining = storage.readAll()
        try {
            ensureOwner()
            if (remaining.any { it.id == resetPairingId || it.identity.certificateSha256 == identity?.certificateSha256 }) {
                fail(RouterWizardError.UNAVAILABLE)
            }
        } finally { remaining.forEach(RouterPairing::clear) }
        resetPairingId = null
        pairing?.clear(); pairing = null
        pairedSession?.close(); pairedSession = null
    }

    private suspend fun completePairing() {
        val actualApi = requireNotNull(api)
        val actualIdentity = requireNotNull(identity)
        val all = storage.readAll()
        val saved = all.singleOrNull { it.identity.certificateSha256 == actualIdentity.certificateSha256 }?.copy()
        all.forEach(RouterPairing::clear)
        try { ensureOwner() } catch (error: Exception) { saved?.clear(); throw error }
        if (freshPairingRequired && saved != null) {
            saved.clear()
            fail(RouterWizardError.UNAVAILABLE)
        }
        val pending = if (saved != null && saved.identity == actualIdentity) saved
        else if (pairingRetry && saved != null && saved.identity.certificateSha256 == actualIdentity.certificateSha256) {
            // PIN-authenticated retry keeps the old record until pair authentication succeeds below.
            saved.atVerifiedIdentity(actualIdentity).also { saved.clear() }
        } else {
            saved?.clear()
            if (storage.listSaved().routers.size >= MAX_SAVED_ROUTERS) fail(RouterWizardError.LIMIT)
            beforeRouterSelection?.invoke(); ensureOwner()
            RouterPairing.create(actualIdentity, deviceName.take(128)).also { ensureOwner(); storage.save(it); ensureOwner() }
        }
        storage.selectSaved(pending.id)
        ensureOwner()
        freshPairingRequired = false
        pairing?.clear(); pairing = pending
        ensureOwner()
        actualApi.createPairing(requireNotNull(pinSession), pending)
        ensureOwner()
        pairedSession?.close()
        val session = actualApi.unlockPairing(pending)
        try { ensureOwner(); pairedSession = session } catch (error: Exception) { session.close(); throw error }
        val active = pending.activated()
        try { ensureOwner(); storage.save(active); ensureOwner() } finally { active.clear() }
        actualApi.lock(requireNotNull(pinSession)); pinSession = null
        ensureOwner()
        val inbound = provisioner.availableInboundProtocols(actualApi, requireNotNull(pairedSession))
        ensureOwner()
        val country = provisioner.routerCountry(actualApi, requireNotNull(pairedSession))
        ensureOwner()
        val priorPreferences = if (saved?.id == pending.id) preferenceStore?.read(pending.id) else null
        ensureOwner()
        val options = mutableState.value.options.let { if (it.countryAuto) it.copy(countryCode = country.orEmpty()) else it }
            .let { if (priorPreferences != null) it.copy(publicIp = priorPreferences.publicIpConfirmed) else it }
        publish(mutableState.value.copy(stage = RouterWizardStage.OPTIONS, pairedId = pending.id, inboundProtocols = inbound,
            options = options, routerCountryCode = country))
    }

    private suspend fun operation(block: suspend () -> Unit) = mutex.withLock {
        val owner = generation.get()
        operationOwner = owner
        progressGeneration.incrementAndGet()
        operationRecovery = null
        val job = currentCoroutineContext()[Job]
        activeOperation.set(job)
        val start = mutableState.value.copy(autoUpdateFailure = null)
        mutableState.value = start.copy(busy = true, error = null)
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: WizardFailure) { if (owner == generation.get()) mutableState.value = (operationRecovery ?: start).copy(error = failure.kind) }
        catch (_: RouterLicenseRequired) { if (owner == generation.get()) mutableState.value = (operationRecovery ?: start).copy(error = RouterWizardError.LICENSE) }
        catch (failure: RouterSetupFailure) { if (owner == generation.get()) mutableState.value = (operationRecovery ?: start).copy(error = failure.kind) }
        catch (_: RouterLocalNetworkPermissionRequired) { if (owner == generation.get()) mutableState.value = (operationRecovery ?: start).copy(error = RouterWizardError.LOCAL_NETWORK_PERMISSION) }
        catch (_: RouterWifiRequired) { if (owner == generation.get()) mutableState.value = (operationRecovery ?: start).copy(error = RouterWizardError.WIFI) }
        catch (_: RouterVpnSocketUnavailable) { if (owner == generation.get()) mutableState.value = (operationRecovery ?: start).copy(error = RouterWizardError.VPN_ACTIVE) }
        catch (_: RouterIdentityChanged) { if (owner == generation.get()) mutableState.value = (operationRecovery ?: start).copy(error = RouterWizardError.IDENTITY) }
        catch (_: RouterSshFailure) { if (owner == generation.get()) mutableState.value = (operationRecovery ?: start).copy(error = RouterWizardError.PASSWORD) }
        catch (failure: RouterUpdateFailure) {
            if (owner == generation.get()) mutableState.value = (operationRecovery ?: start).copy(
                error = RouterWizardError.SAVE, autoUpdateFailure = failure.reason)
        }
        catch (failure: RouterApiFailure) {
            if (owner == generation.get() && failure.kind == RouterApiError.PIN_SETUP) {
                mutableState.value = (operationRecovery ?: start).copy(stage = RouterWizardStage.PIN_SETUP_REQUIRED, error = null)
            } else if (owner == generation.get()) mutableState.value = (operationRecovery ?: start).copy(error = when (failure.kind) {
                RouterApiError.PIN -> RouterWizardError.PIN
                RouterApiError.LOCKED -> RouterWizardError.LOCKED
                RouterApiError.IDENTITY -> RouterWizardError.IDENTITY
                RouterApiError.UNSUPPORTED -> RouterWizardError.UNSUPPORTED
                RouterApiError.LIMIT -> RouterWizardError.LIMIT
                RouterApiError.LICENSE -> RouterWizardError.LICENSE
                RouterApiError.INSECURE_TLS -> RouterWizardError.PROFILE
                else -> RouterWizardError.NETWORK
            })
        }
        catch (_: IOException) { if (owner == generation.get()) mutableState.value = (operationRecovery ?: start).copy(error = RouterWizardError.NETWORK) }
        catch (_: IllegalArgumentException) { if (owner == generation.get()) mutableState.value = (operationRecovery ?: start).copy(error = RouterWizardError.UNSUPPORTED) }
        catch (_: IllegalStateException) { if (owner == generation.get()) mutableState.value = (operationRecovery ?: start).copy(error = RouterWizardError.UNAVAILABLE) }
        finally {
            activeOperation.compareAndSet(job, null)
            if (owner == generation.get()) mutableState.value = mutableState.value.copy(busy = false, installProgress = null)
        }
    }

    private suspend fun progressReporter(): (RouterInstallProgress) -> Unit {
        val token = generation.get()
        val epoch = progressGeneration.get()
        val job = currentCoroutineContext()[Job]
        return { progress ->
            mutableState.update { current ->
                if (progressActive(token, epoch, job)) current.copy(installProgress = progress) else current
            }
        }
    }

    private fun progressActive(token: Long, epoch: Long, job: Job?): Boolean {
        if (token != generation.get() || epoch != progressGeneration.get()) return false
        if (job == null || job !== activeOperation.get()) return false
        return job.isActive
    }

    private suspend fun ensureOwner() {
        currentCoroutineContext().ensureActive()
        if (operationOwner != generation.get()) throw CancellationException("router_wizard_closed")
    }

    private fun publish(value: RouterWizardState) {
        if (operationOwner != generation.get()) throw CancellationException("router_wizard_closed")
        mutableState.value = value
    }

    override fun close() {
        generation.incrementAndGet()
        progressGeneration.incrementAndGet()
        activeOperation.getAndSet(null)?.cancel()
        hostKey?.fill(0); hostKey = null
        pinSession?.close(); pinSession = null
        pairedSession?.close(); pairedSession = null
        pairing?.clear(); pairing = null
        proposal?.close(); proposal = null
        api?.close(); api = null
        addingNew = false
        pairingRetry = false
        autoUpdateAttempt = null; autoUpdateApplied = null; completedSetup = null
        identity = null
        resetFoxHoleRequested = false
        resetPairingId = null
        freshPairingRequired = false
        operationRecovery = null
        mutableState.update { it.copy(installProgress = null, busy = false, installedInThisWizard = false, autoUpdateUnconfirmed = false,
            autoUpdateFailure = null, licenseRequired = false, licenseVersion = 0, options = it.options.copy(licenseAccepted = false, licenseGeneration = generation.get())) }
    }

    private fun fail(kind: RouterWizardError): Nothing = throw WizardFailure(kind)
    private class WizardFailure(val kind: RouterWizardError) : IOException()
}
