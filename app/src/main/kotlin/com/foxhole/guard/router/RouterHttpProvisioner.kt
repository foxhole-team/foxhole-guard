package com.foxhole.guard.router

import android.net.ConnectivityManager
import android.os.SystemClock
import com.foxhole.guard.router.profile.VpnProfileImporter
import com.foxhole.guard.router.profile.exportVpnProfile
import com.foxhole.guard.router.profile.InsecureTlsConsentRequired
import com.foxhole.guard.router.profile.ProfileImportFailure
import com.foxhole.core.network.ensurePublicSubscriptionUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Locale
import java.security.MessageDigest

internal enum class RouterMobileAction(val action: String) {
    UPDATES_STATUS("updates.status"), UPDATES_CHECK("updates.check"), UPDATES_PLAN("updates.plan"),
    UPDATES_APPLY("updates.apply"), UPDATES_JOB("updates.job"), UPDATES_SETTINGS("updates.settings"),
    ENGINES_STATUS("engines.status"), ENGINES_PLAN("engines.plan"), ENGINES_APPLY("engines.apply"), ENGINES_JOB("engines.job"),
    PROFILE_TEST("server.test"), PROFILE_SAVE("server.save"), PROFILE_GET("server.get"), PROFILE_START("server.start"),
    PROFILE_STOP("server.stop"), PROFILE_DELETE("server.delete"), SETTINGS("settings"), PHONE_PROVISION("phone.provision"), PHONE_STATUS("phone.status"), PHONE_CAPABILITIES("phone.capabilities"), PHONE_HANDOFF("phone.handoff"), PHONE_RESOLVE("phone.resolve"),
    CLIENT_GET("client.get"), CLIENT_DELETE("client.delete"), CLIENT_CREATE("client.save"),
    DEVICE_LIST("device.list"), CLIENT_STATUS("client.status"), PACKAGE_REMOVE("package.remove"),
}

internal typealias RouterMobileCall = suspend (RouterPanelApi, RouterMobileAction, JsonObject) -> JsonObject

internal class RouterControlSnapshot(
    val revision: Long,
    val settings: JsonObject,
    val countries: JsonArray,
    val devices: JsonArray,
    val sites: JsonArray,
    val servers: List<JsonObject>,
    val clients: List<JsonObject>,
    val desiredServerId: String?,
    val connected: Boolean,
    val packageRemovalAvailable: Boolean = false,
) {
    override fun toString(): String = "RouterControlSnapshot(revision=$revision, servers=${servers.size}, clients=${clients.size})"
}

internal data class RouterPhoneHandoffStatus(val claimId: String, val expiresAtElapsedMs: Long, val policyRevision: Long)
internal data class RouterPhoneStatus(val pairingId: String, val lanDeviceId: String?, val address: String,
    val proof: RouterHomeProof?, val confirmedOff: RouterHomeOffEvidence? = null,
    val handoff: RouterPhoneHandoffStatus? = null, val handoffKnown: Boolean = false,
    val observedUptimeMs: Long? = null, val receivedAtElapsedMs: Long? = null, val requestDurationMs: Long? = null)

internal data class RouterPanelCapabilities(val coreProtocols: Set<String>, val legacyProtocols: Set<String>,
    val inboundProtocols: Set<String>, val subscriptionSelection: Boolean, val independentPhoneChoices: Boolean,
    val phoneRegistration: Boolean, val homeProof: Boolean, val phoneHandoff: Boolean = false, val providerResolve: Boolean = false,
    val profileSourceImport: Boolean = false, val packageUpdates: Boolean = false)

internal data class RouterClientCreation(val snapshot: RouterControlSnapshot, val clientId: String, val provisioned: Boolean)

internal fun validRouterClientName(value: String): Boolean = value.trim().isNotEmpty() &&
    value.toByteArray(Charsets.UTF_8).size <= 48 && value.none(Char::isISOControl)

internal class RouterHttpProvisioner(
    private val mobileCall: RouterMobileCall = { api, action, payload -> api.mobileRequest(action, payload) },
    private val localAddress: (RouterTlsIdentity) -> String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val coreProtocols: Set<String>? = null,
    private val supportsSubscriptionSelection: Boolean? = null,
    private val supportsIndependentPhoneChoices: Boolean? = null,
    private val pairingStore: RouterPairingStore? = null,
) : RouterWizardProvisioner {
    override val supportedInboundProtocols = setOf("hysteria2")
    override val supportsHomeAutodetection = false

    override suspend fun availableInboundProtocols(api: RouterPanelApi, session: RouterPanelSession): Set<String> {
        return capabilities(api, session).inboundProtocols
    }

    override suspend fun routerCountry(api: RouterPanelApi, session: RouterPanelSession): String? = try {
        parseRouterWanCountry(api.telemetry(session))
    } catch (failure: RouterApiFailure) {
        if (failure.kind in setOf(RouterApiError.UNAVAILABLE, RouterApiError.UNSUPPORTED, RouterApiError.INVALID_RESPONSE)) null else throw failure
    }

    override suspend fun updateStatus(api: RouterPanelApi, session: RouterPanelSession): RouterUpdateStatus =
        parseRouterUpdateStatus(call(api, session, RouterMobileAction.UPDATES_STATUS, JsonObject(emptyMap())))

    suspend fun checkUpdates(api: RouterPanelApi, session: RouterPanelSession, status: RouterUpdateStatus): RouterUpdateJob {
        val job = parseRouterUpdateJob(call(api, session, RouterMobileAction.UPDATES_CHECK,
            buildJsonObject { put("expectedRevision", status.revision) }), status.routerId)
        if (job.progress.operation != RouterUpdateOperation.CHECK) invalidEngineResponse()
        return job
    }

    suspend fun updatePlan(api: RouterPanelApi, session: RouterPanelSession, status: RouterUpdateStatus): RouterUpdatePlan =
        parseRouterUpdatePlan(call(api, session, RouterMobileAction.UPDATES_PLAN,
            buildJsonObject { put("expectedRevision", status.revision) }), status)

    suspend fun applyUpdate(api: RouterPanelApi, session: RouterPanelSession, status: RouterUpdateStatus, plan: RouterUpdatePlan): RouterUpdateJob {
        if (status.busy || status.routerId != plan.routerId || status.revision != plan.revision ||
            status.availability != RouterUpdateAvailability.AVAILABLE) throw RouterUpdateFailure(RouterUpdateFailureReason.STALE_PLAN)
        val job = parseRouterUpdateJob(call(api, session, RouterMobileAction.UPDATES_APPLY, buildJsonObject {
            put("planId", plan.planId); put("planSha256", plan.planSha256); put("expectedRevision", plan.revision); put("confirmed", true)
        }), status.routerId)
        if (job.progress.operation != RouterUpdateOperation.APPLY || job.planId != plan.planId) invalidEngineResponse()
        return job
    }

    suspend fun updateJob(api: RouterPanelApi, session: RouterPanelSession, status: RouterUpdateStatus, ref: RouterUpdateJobRef): RouterUpdateJob =
        parseRouterUpdateJob(call(api, session, RouterMobileAction.UPDATES_JOB,
            buildJsonObject { put("jobId", ref.jobId) }), status.routerId, ref)

    override suspend fun setUpdateAuto(api: RouterPanelApi, session: RouterPanelSession, status: RouterUpdateStatus, enabled: Boolean): RouterUpdateStatus {
        val updated = parseRouterUpdateStatus(call(api, session, RouterMobileAction.UPDATES_SETTINGS, buildJsonObject {
            put("expectedRevision", status.revision); put("autoUpdateEnabled", enabled)
        }))
        if (updated.routerId != status.routerId || updated.revision < status.revision || updated.autoUpdate.enabled != enabled) invalidEngineResponse()
        return updated
    }

    suspend fun engineStatus(api: RouterPanelApi, session: RouterPanelSession): RouterEngineStatus =
        parseRouterEngineStatus(call(api, session, RouterMobileAction.ENGINES_STATUS, JsonObject(emptyMap())))

    suspend fun planEngines(api: RouterPanelApi, session: RouterPanelSession, status: RouterEngineStatus,
        operation: RouterEngineOperation, protocols: Set<RouterInstallProtocol>): RouterEnginePlan {
        if (protocols.isEmpty()) throw RouterEngineFailure(RouterEngineFailureReason.INVALID_REQUEST)
        if (status.busy) throw RouterEngineFailure(RouterEngineFailureReason.BUSY)
        protocols.forEach { protocol ->
            val entry = status.entries.singleOrNull { it.protocol == protocol }
                ?: throw RouterEngineFailure(RouterEngineFailureReason.UNKNOWN_PROTOCOL)
            if (operation == RouterEngineOperation.REMOVE && entry.active) throw RouterEngineFailure(RouterEngineFailureReason.IN_USE)
            if (operation != RouterEngineOperation.REMOVE && entry.availability != RouterEngineAvailability.AVAILABLE)
                throw RouterEngineFailure(RouterEngineFailureReason.NOT_RELEASED)
            if (operation == RouterEngineOperation.ADD && !entry.canAdd || operation == RouterEngineOperation.UPDATE && !entry.canUpdate ||
                operation == RouterEngineOperation.REMOVE && !entry.installed) throw RouterEngineFailure(RouterEngineFailureReason.INVALID_REQUEST)
        }
        val result = call(api, session, RouterMobileAction.ENGINES_PLAN, buildJsonObject {
            put("operation", operation.name)
            put("engineIds", JsonArray(protocols.sortedBy { it.canonical }.map { JsonPrimitive(it.canonical) }))
            put("storage", "AUTO"); put("expectedRevision", status.revision); put("catalogSha256", status.catalogSha256)
        })
        return parseRouterEnginePlan(result, status, operation, protocols)
    }

    suspend fun applyEngines(api: RouterPanelApi, session: RouterPanelSession, status: RouterEngineStatus,
        plan: RouterEnginePlan): RouterEngineJob {
        if (plan.routerId != status.routerId || plan.revision != status.revision || plan.catalogSha256 != status.catalogSha256 || status.busy)
            throw RouterEngineFailure(RouterEngineFailureReason.STALE_PLAN)
        return parseRouterEngineJob(call(api, session, RouterMobileAction.ENGINES_APPLY, buildJsonObject {
            put("planId", plan.planId); put("planSha256", plan.planSha256); put("expectedRevision", plan.revision); put("confirmed", true)
        }), status.routerId, planId = plan.planId)
    }

    suspend fun engineJob(api: RouterPanelApi, session: RouterPanelSession, status: RouterEngineStatus,
        jobId: String, planId: String): RouterEngineJob {
        requireEngineId(jobId); requireEngineId(planId)
        return parseRouterEngineJob(call(api, session, RouterMobileAction.ENGINES_JOB, buildJsonObject { put("jobId", jobId) }),
            status.routerId, planId = planId, jobId = jobId)
    }

    suspend fun clientStatuses(api: RouterPanelApi, session: RouterPanelSession, snapshot: RouterControlSnapshot): List<RouterRemoteClientStatus> = try {
        parseRouterClientStatuses(call(api, session, RouterMobileAction.CLIENT_STATUS, JsonObject(emptyMap())), snapshot)
    } catch (failure: RouterApiFailure) {
        if (failure.kind in setOf(RouterApiError.UNAVAILABLE, RouterApiError.UNSUPPORTED)) unknownRouterClientStatuses(snapshot) else throw failure
    }

    suspend fun setCountryRule(api: RouterPanelApi, session: RouterPanelSession, current: RouterControlSnapshot,
        code: String, direct: Boolean): RouterControlSnapshot {
        if (code !in COUNTRIES) unsupported()
        val updated = updateCountry(api, session, current, code, direct)
        val verified = snapshot(api, session)
        if (verified.revision < updated.revision || verified.settings["localRules"] != JsonPrimitive(true) ||
            verified.countries.mapNotNull { it as? JsonObject }.singleOrNull { it.optionalString("code") == code }
                ?.optionalString("route") != (if (direct) "direct" else "vpn")) malformed()
        return verified
    }

    suspend fun capabilities(api: RouterPanelApi, session: RouterPanelSession): RouterPanelCapabilities {
        val result = call(api, session, RouterMobileAction.PHONE_CAPABILITIES, JsonObject(emptyMap()))
        val actual = parseRouterPanelCapabilities(result)
        return actual.copy(coreProtocols = coreProtocols?.let { actual.coreProtocols.intersect(it) } ?: actual.coreProtocols,
            subscriptionSelection = actual.subscriptionSelection && supportsSubscriptionSelection != false,
            independentPhoneChoices = actual.independentPhoneChoices && supportsIndependentPhoneChoices != false)
    }

    override suspend fun finish(api: RouterPanelApi, session: RouterPanelSession, pairing: RouterPairing,
        options: RouterWizardOptions, selectedProfile: RouterProfileSelection?): RouterWizardResult {
        requirePaired(session)
        require(pairing.identity == api.identity)
        if (!options.valid) unsupported()
        if (options.homeAutodetection) unsupported()
        val country = options.countryCode
        val applyCountry = country in COUNTRIES
        if (!applyCountry && !(options.countryAuto && country.isEmpty())) unsupported()
        if (selectedProfile != null && pairingStore == null) unsupported()
        val capability = capabilities(api, session)
        if (options.createRemoteProfile && !options.registerPhone && !capability.independentPhoneChoices ||
            options.registerPhone && !capability.phoneRegistration) unsupported()
        if (selectedProfile?.sourceUrl != null && !capability.subscriptionSelection) unsupported()
        val subscription = selectedProfile?.sourceUrl?.let { subscriptionMetadata(selectedProfile) }
        val selected = selectedProfile?.let { compatibleConfiguration(it, capability) }
        val configuration = selected?.value
        if (options.createRemoteProfile && "hysteria2" !in capability.inboundProtocols) unsupported()
        currentCoroutineContext().ensureActive()
        var snapshot = snapshot(api, session)
        if (applyCountry) validateSettingsPayload(countryPayload(snapshot, country, options.countrySitesDirect), session)
        if (configuration != null) {
            snapshot = savePhoneSelection(api, session, pairing, requireNotNull(selectedProfile), requireNotNull(selected),
                subscription, snapshot).snapshot
        }
        if (applyCountry) snapshot = updateCountry(api, session, snapshot, country, options.countrySitesDirect)
        if (!options.registerPhone && !options.createRemoteProfile) return RouterWizardResult(pairing.id)
        val address = localAddress(api.identity).also { if (routerLanAddress(it) == null) malformed() }
        val provisioned = call(api, session, RouterMobileAction.PHONE_PROVISION, buildJsonObject {
            put("name", pairing.name.take(48)); put("address", address); put("createRemote", options.createRemoteProfile)
            if (capability.independentPhoneChoices) put("registerPhone", options.registerPhone)
        })
        val lanId = provisioned.optionalString("lanDeviceId")
        if (provisioned.number("revision") < snapshot.revision || options.registerPhone && (lanId == null || !lanId.matches(DEVICE_ID)) ||
            !options.registerPhone && lanId != null) malformed()
        val client = provisioned.optionalString("remoteClientId")
        val profile = provisioned.optionalString("profileUri")
        val ready = provisioned.boolean("remoteReady")
        if (ready || options.createRemoteProfile && (client == null || profile == null) ||
            !options.createRemoteProfile && (client != null || profile != null)) malformed()
        if (client != null && !client.matches(CLIENT_ID)) malformed()
        if (profile != null) {
            if (profile.length !in 1..16384 || !profile.startsWith("hysteria2://") || profile.any(Char::isISOControl)) malformed()
            try { withContext(io) { if (VpnProfileImporter().import(profile).profiles.singleOrNull()?.protocol != "HYSTERIA2") malformed() } }
            catch (_: InsecureTlsConsentRequired) { throw RouterApiFailure(RouterApiError.INSECURE_TLS) }
            catch (_: ProfileImportFailure) { malformed() }
        }
        val registered = phoneStatus(api, session, pairing.id)
        if (registered.lanDeviceId != lanId) malformed()
        val marker = profile?.let { routerProfileMarker(provisioned["appMetadata"], it, pairing.id) }
        return RouterWizardResult(pairing.id, profile, remoteReady = false, remoteProfileMarker = marker)
    }

    suspend fun snapshot(api: RouterPanelApi, session: RouterPanelSession): RouterControlSnapshot {
        requirePaired(session)
        return parseSnapshot(api.state(session))
    }

    suspend fun sendSelection(api: RouterPanelApi, session: RouterPanelSession, pairing: RouterPairing,
        selection: RouterProfileSelection): RouterProfileTransfer {
        requirePaired(session)
        require(pairing.identity == api.identity)
        if (pairingStore == null) unsupported()
        val capability = capabilities(api, session)
        if (selection.sourceUrl != null && !capability.subscriptionSelection) unsupported()
        val subscription = selection.sourceUrl?.let { subscriptionMetadata(selection) }
        val selected = compatibleConfiguration(selection, capability)
        val saved = savePhoneSelection(api, session, pairing, selection, selected, subscription, snapshot(api, session))
        val verified = snapshot(api, session)
        if (verified.revision < saved.snapshot.revision || verified.servers.none { it.string("id") == saved.profileId }) malformed()
        return RouterProfileTransfer(verified, saved.profileId)
    }

    private suspend fun savePhoneSelection(api: RouterPanelApi, session: RouterPanelSession, pairing: RouterPairing,
        selectedProfile: RouterProfileSelection, selected: SelectedConfiguration, subscription: JsonObject?,
        initial: RouterControlSnapshot): RouterProfileTransfer {
        var snapshot = initial
        val configuration = selected.value
        val core = selected.core
        val tested = call(api, session, RouterMobileAction.PROFILE_TEST, buildJsonObject {
            put("config", configuration); if (core) put("engine", "core")
        })
        if (!tested.boolean("valid") || tested.string("scope") != "config" || tested.string("code") != "validated") malformed()
        val endpoint = tested.string("endpoint")
        val name = selectedProfile.displayName.take(64)
        if (endpoint.length !in 1..320 || endpoint.any(Char::isISOControl)) malformed()
        val admittedDigest = tested["configSha256"]?.let { (it as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: malformed() }
        if (admittedDigest != null && !admittedDigest.matches(Regex("[a-f0-9]{64}")) || core && admittedDigest == null) malformed()
        var matchingId: String? = null
        val syncIdentity = syncIdentity(configuration, subscription, core)
        val savedPairing = requireNotNull(pairingStore).read() ?: throw RouterApiFailure(RouterApiError.CONFLICT)
        val pendingId = try {
            if (savedPairing.id != pairing.id || savedPairing.identity != pairing.identity) throw RouterApiFailure(RouterApiError.CONFLICT)
            savedPairing.pendingServerId.takeIf { savedPairing.pendingSyncSha256 == syncIdentity }
        } finally { savedPairing.clear() }
        if (pendingId != null) {
            val candidate = snapshot.servers.singleOrNull { it.string("id") == pendingId } ?: throw RouterApiFailure(RouterApiError.CONFLICT)
            val read = readProfile(api, session, pendingId)
            if (!configurationMatches(read, configuration, admittedDigest, core)) throw RouterApiFailure(RouterApiError.CONFLICT)
            if (read["subscription"].let { if (it == JsonNull) null else it } != subscription) {
                snapshot = parseSnapshot(call(api, session, RouterMobileAction.PROFILE_SAVE, profilePayload(snapshot, configuration,
                    candidate.string("id"), name, core, subscription)))
            }
            verifyProfile(api, session, pendingId, configuration, admittedDigest, core, subscription)
            matchingId = pendingId
        }
        for (server in snapshot.servers.filter { pendingId == null && it.string("endpoint") == endpoint }) {
            val existing = call(api, session, RouterMobileAction.PROFILE_GET, buildJsonObject { put("id", server.string("id")) })
            if (existing.string("id") != server.string("id")) malformed()
            val stored = existing.string("config")
            if (stored.length !in 1..262144) malformed()
            val digest = existing["configSha256"]?.let { (it as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: malformed() }
            if (digest != null && !digest.matches(Regex("[a-f0-9]{64}"))) malformed()
            val storedEngine = (existing["engine"] as? JsonPrimitive)?.contentOrNull
            if (core && storedEngine != "core" || !core && storedEngine == "core") continue
            val storedSubscription = existing["subscription"]?.let { if (it == JsonNull) null else it as? JsonObject ?: malformed() }
            if (storedSubscription != subscription) continue
            if (admittedDigest != null && digest == admittedDigest || !core && stored.trim() == configuration) {
                if (matchingId != null) throw RouterApiFailure(RouterApiError.CONFLICT)
                matchingId = server.string("id")
            }
        }
        if (matchingId == null) {
            val previousIds = snapshot.servers.map { it.string("id") }.toSet()
            snapshot = parseSnapshot(call(api, session, RouterMobileAction.PROFILE_SAVE, profilePayload(snapshot, configuration, "", name, core, subscription)))
            val createdId = snapshot.servers.map { it.string("id") }.filterNot { it in previousIds }.singleOrNull() ?: malformed()
            savePendingSync(pairing, createdId, syncIdentity)
            verifyProfile(api, session, createdId, configuration, admittedDigest, core, subscription)
            matchingId = createdId
        }
        savePendingSync(pairing, null, null)
        return RouterProfileTransfer(snapshot, requireNotNull(matchingId))
    }

    suspend fun sendProfile(api: RouterPanelApi, session: RouterPanelSession, name: String, configuration: String): RouterProfileTransfer {
        requirePaired(session)
        if (!validTransferredProfile(name, configuration)) unsupported()
        val capability = capabilities(api, session)
        if (configuration.startsWith("https://", true) || configuration.startsWith("ssconf://", true)) {
            configuration.ensurePublicSubscriptionUrl(resolveHost = false)
            if (!capability.profileSourceImport) unsupported()
        }
        val core = capability.coreProtocols.isNotEmpty()
        val tested = call(api, session, RouterMobileAction.PROFILE_TEST, buildJsonObject {
            put("config", configuration)
            if (core) put("engine", "core")
        })
        val admission = transferredAdmission(tested, capability, core)
        val current = snapshot(api, session)
        val matches = mutableListOf<String>()
        for (server in current.servers.filter { it.string("endpoint") == admission.first }) {
            val id = server.string("id")
            if (transferredProfileMatches(readProfile(api, session, id), configuration, admission.second, core)) matches += id
        }
        if (matches.size > 1) throw RouterApiFailure(RouterApiError.CONFLICT)
        matches.singleOrNull()?.let { return RouterProfileTransfer(current, it) }
        val prior = current.servers.map { it.string("id") }.toSet()
        val saved = parseSnapshot(call(api, session, RouterMobileAction.PROFILE_SAVE,
            profilePayload(current, configuration, "", name.trim(), core, null)))
        val id = saved.servers.filter { it.string("id") !in prior }.singleOrNull()?.string("id") ?: malformed()
        if (saved.revision <= current.revision || !saved.servers.map { it.string("id") }.toSet().containsAll(prior)) malformed()
        if (!transferredProfileMatches(readProfile(api, session, id), configuration, admission.second, core)) malformed()
        val verified = snapshot(api, session)
        if (verified.revision < saved.revision || verified.servers.none { it.string("id") == id }) malformed()
        return RouterProfileTransfer(verified, id)
    }

    private fun validTransferredProfile(name: String, configuration: String): Boolean =
        name.trim().isNotEmpty() && name.toByteArray(Charsets.UTF_8).size <= 64 && name.none(Char::isISOControl) &&
            configuration.isNotBlank() && configuration.toByteArray(Charsets.UTF_8).size <= 262144 && !configuration.contains('\u0000')

    private fun transferredAdmission(value: JsonObject, capability: RouterPanelCapabilities, core: Boolean): Pair<String, String?> {
        if (!value.boolean("valid") || value.string("scope") != "config" || value.string("code") != "validated") malformed()
        val protocols = if (core) capability.coreProtocols else capability.legacyProtocols
        if (value.string("protocol") !in protocols) unsupported()
        val endpoint = value.string("endpoint")
        if (endpoint.length !in 1..320 || endpoint.any(Char::isISOControl)) malformed()
        val digest = value["configSha256"]?.let { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.contentOrNull ?: malformed() }
        if (digest != null && !digest.matches(Regex("[a-f0-9]{64}"))) malformed()
        if (core && digest == null) malformed()
        return endpoint to digest
    }

    private fun transferredProfileMatches(value: JsonObject, raw: String, digest: String?, core: Boolean): Boolean {
        if (!configurationMatches(value, raw, digest, core)) return false
        if (!core) return true
        val portable = value["portableProfile"] as? JsonObject ?: return false
        return portable.string("content") == raw && portable.string("sha256") == contentDigest(raw) &&
            portable.string("configSha256") == digest
    }

    suspend fun devices(api: RouterPanelApi, session: RouterPanelSession, current: RouterControlSnapshot): List<RouterLanDevice> =
        parseRouterLanDevices(call(api, session, RouterMobileAction.DEVICE_LIST, JsonObject(emptyMap())), current)

    suspend fun removePackage(api: RouterPanelApi, session: RouterPanelSession, pairingId: String, expectedRevision: Long) {
        val current = snapshot(api, session)
        if (!current.packageRemovalAvailable) unsupported()
        if (current.revision != expectedRevision) throw RouterApiFailure(RouterApiError.CONFLICT)
        val result = call(api, session, RouterMobileAction.PACKAGE_REMOVE, buildJsonObject {
            put("revision", expectedRevision); put("pairingId", pairingId)
        })
        if (result.string("pairingId") != pairingId || result.number("revision") < expectedRevision ||
            !result.boolean("packageRemoved") || !result.boolean("ownedSettingsRestored")) malformed()
    }

    private fun contentDigest(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    suspend fun setRunning(api: RouterPanelApi, session: RouterPanelSession, serverId: String?, enabled: Boolean): RouterControlSnapshot {
        var current = snapshot(api, session)
        if (enabled) {
            if (serverId == null || !serverId.matches(SERVER_ID) || current.servers.none { it.string("id") == serverId }) unsupported()
            current = parseSnapshot(call(api, session, RouterMobileAction.PROFILE_START, buildJsonObject { put("revision", current.revision); put("id", serverId) }))
        } else current = parseSnapshot(call(api, session, RouterMobileAction.PROFILE_STOP, buildJsonObject { put("revision", current.revision) }))
        return current
    }

    suspend fun removeProfile(api: RouterPanelApi, session: RouterPanelSession, serverId: String): RouterControlSnapshot {
        val current = snapshot(api, session)
        if (!serverId.matches(SERVER_ID) || current.servers.none { it.string("id") == serverId }) unsupported()
        return parseSnapshot(call(api, session, RouterMobileAction.PROFILE_DELETE, buildJsonObject { put("revision", current.revision); put("id", serverId) }))
    }

    suspend fun remoteClientProfile(api: RouterPanelApi, session: RouterPanelSession, clientId: String, provisioned: Boolean): String =
        remoteClientExport(api, session, clientId, provisioned).uri

    suspend fun remoteClientExport(api: RouterPanelApi, session: RouterPanelSession, clientId: String, provisioned: Boolean,
        expectedPairingId: String? = null): RouterClientExport {
        if (!clientId.matches(CLIENT_ID)) unsupported()
        val result = call(api, session, RouterMobileAction.CLIENT_GET, buildJsonObject { put("id", clientId) })
        val export = result["export"] as? JsonObject ?: malformed()
        if (export.string("id") != clientId || export.boolean("provisioned") != provisioned || export.boolean("inactive")) malformed()
        val uri = export.string("uri")
        if (!uri.startsWith("hysteria2://") || uri != export.string("config") || uri.toByteArray(Charsets.UTF_8).size !in 1..262144 ||
            uri.any { it.code in 0..0x1f || it.code in 0x7f..0x9f }) malformed()
        val checked = routerWholeShareContent(buildJsonObject { put("config", uri) })
        val marker = expectedPairingId?.let { routerProfileMarker(export["appMetadata"], uri, it) }
        return RouterClientExport(checked, marker)
    }

    suspend fun createRemoteClient(api: RouterPanelApi, session: RouterPanelSession, name: String): RouterClientCreation {
        val normalized = name.trim()
        if (!validRouterClientName(name)) unsupported()
        if ("hysteria2" !in capabilities(api, session).inboundProtocols) unsupported()
        val before = snapshot(api, session)
        if (before.clients.size >= 256) unsupported()
        val response = call(api, session, RouterMobileAction.CLIENT_CREATE, buildJsonObject {
            put("revision", before.revision); put("name", normalized); put("deviceType", "smartphone")
        })
        val acknowledged = parseSnapshot(response["state"] as? JsonObject ?: malformed())
        val export = response["client"] as? JsonObject ?: malformed()
        val priorIds = before.clients.map { it.string("id") }.toSet()
        val id = acknowledged.clients.filter { it.string("id") !in priorIds }.singleOrNull()?.string("id") ?: malformed()
        if (acknowledged.revision <= before.revision || acknowledged.clients.size != before.clients.size + 1 ||
            !acknowledged.clients.map { it.string("id") }.toSet().containsAll(priorIds)) malformed()
        if (export.string("id") != id || export.string("name") != normalized || export.string("deviceType") != "smartphone") malformed()
        val provisioned = export.boolean("provisioned")
        if (acknowledged.clients.single { it.string("id") == id }.boolean("provisioned") != provisioned) malformed()
        val actual = snapshot(api, session)
        val created = actual.clients.singleOrNull { it.string("id") == id } ?: malformed()
        if (actual.revision < acknowledged.revision || created.string("name") != normalized ||
            created.boolean("provisioned") != provisioned) malformed()
        // An older/unconfigured router may save a historical template. Keep that fact, never claim ready.
        if (provisioned) remoteClientProfile(api, session, id, true)
        return RouterClientCreation(actual, id, provisioned)
    }

    suspend fun removeRemoteClient(api: RouterPanelApi, session: RouterPanelSession, clientId: String, expectedRevision: Long, provisioned: Boolean): RouterControlSnapshot {
        if (!clientId.matches(CLIENT_ID)) unsupported()
        val current = snapshot(api, session)
        val client = current.clients.singleOrNull { it.string("id") == clientId } ?: throw RouterApiFailure(RouterApiError.CONFLICT)
        if (current.revision != expectedRevision || client.boolean("provisioned") != provisioned) throw RouterApiFailure(RouterApiError.CONFLICT)
        val response = call(api, session, RouterMobileAction.CLIENT_DELETE, buildJsonObject { put("revision", expectedRevision); put("id", clientId) })
        val proof = response["clientRevocation"] as? JsonObject ?: malformed()
        if (proof.string("id") != clientId || !proof.boolean("applied") || proof.boolean("targetRevoked") != provisioned) malformed()
        val acknowledged = parseSnapshot(response)
        if (acknowledged.revision <= expectedRevision || acknowledged.clients.any { it.string("id") == clientId }) malformed()
        val verified = snapshot(api, session)
        if (verified.revision < acknowledged.revision || verified.clients.any { it.string("id") == clientId }) malformed()
        return verified
    }

    suspend fun setLanClientVpn(api: RouterPanelApi, session: RouterPanelSession, deviceId: String, enabled: Boolean,
        observed: RouterLanDevice? = null): RouterControlSnapshot {
        val current = snapshot(api, session)
        if (!deviceId.matches(DEVICE_ID)) unsupported()
        val existing = current.devices.mapNotNull { it as? JsonObject }.singleOrNull { it.string("id") == deviceId }
        val route = if (enabled) "vpn" else "direct"
        val target = existing ?: newDeviceRule(api, session, current, deviceId, route, observed)
        val settings = JsonObject(current.settings + ("localRules" to JsonPrimitive(true)))
        val devices = JsonArray(current.devices.map { value ->
            val device = value as? JsonObject ?: malformed()
            if (device.string("id") == deviceId) JsonObject(device + ("route" to JsonPrimitive(route))) else device
        } + if (existing == null) listOf(target) else emptyList())
        val acknowledged = parseSnapshot(call(api, session, RouterMobileAction.SETTINGS, buildJsonObject {
            put("revision", current.revision); put("settings", settings); put("countries", current.countries)
            put("devices", devices); put("sites", current.sites)
        }))
        val verified = snapshot(api, session)
        val actual = verified.devices.mapNotNull { it as? JsonObject }.singleOrNull { it.string("id") == deviceId } ?: malformed()
        if (verified.revision < acknowledged.revision || actual.string("route") != route) malformed()
        if (existing == null && actual["mac"] != target["mac"]) malformed()
        return verified
    }

    private suspend fun newDeviceRule(api: RouterPanelApi, session: RouterPanelSession, current: RouterControlSnapshot,
        id: String, route: String, observed: RouterLanDevice?): JsonObject {
        if (current.devices.size >= 512 || observed == null || observed.id != id) unsupported()
        val fresh = devices(api, session, current).singleOrNull { it.id == id } ?: throw RouterApiFailure(RouterApiError.CONFLICT)
        if (fresh.mac == null || fresh.mac != observed.mac) throw RouterApiFailure(RouterApiError.CONFLICT)
        return buildJsonObject {
            put("id", id); put("name", fresh.name); put("address", fresh.address); put("mac", fresh.mac)
            put("bindMac", true); put("route", route)
        }
    }

    suspend fun phoneStatus(api: RouterPanelApi, session: RouterPanelSession, expectedPairId: String): RouterPhoneStatus {
        requirePaired(session)
        val address = localAddress(api.identity).also { if (routerLanAddress(it) == null) malformed() }
        val requestedAt = elapsedMs()
        val result = call(api, session, RouterMobileAction.PHONE_STATUS, buildJsonObject { put("address", address) })
        val receivedAt = elapsedMs()
        return parsePhoneStatus(result, expectedPairId, address, receivedAt, receivedAt - requestedAt)
    }

    suspend fun resolveProvider(api: RouterPanelApi, session: RouterPanelSession, expectedPair: String, host: String,
        routerUptimeNow: () -> Long): RouterProviderResolution {
        requirePaired(session)
        val normalized = routerProviderHostname(host)
        val address = localAddress(api.identity).also { if (routerLanAddress(it) == null) malformed() }
        val at = elapsedMs()
        val value = call(api, session, RouterMobileAction.PHONE_RESOLVE, buildJsonObject { put("address", address); put("host", normalized) })
        currentCoroutineContext().ensureActive()
        val received = elapsedMs()
        return parseRouterProviderResolution(value, expectedPair, normalized, routerUptimeNow(), received, received - at)
    }

    private data class SelectedConfiguration(val value: String, val core: Boolean)

    private suspend fun compatibleConfiguration(selection: RouterProfileSelection, capability: RouterPanelCapabilities): SelectedConfiguration = withContext(io) {
        val export = selection.selectedConfiguration
        if (export.toByteArray(Charsets.UTF_8).size !in 1..262144) unsupported()
        val importer = VpnProfileImporter()
        val imported = try { importer.import(export) }
        catch (_: InsecureTlsConsentRequired) { throw RouterApiFailure(RouterApiError.INSECURE_TLS) }
        catch (_: ProfileImportFailure) { unsupported() }
        val profile = imported.profiles.singleOrNull() ?: unsupported()
        val protocol = if (profile.protocol == "OUTLINE") "shadowsocks" else profile.protocol.lowercase()
        val core = protocol in capability.coreProtocols
        val supported = capability.coreProtocols + capability.legacyProtocols
        if (imported.rejected != 0 || protocol != selection.selectedProtocol || protocol !in supported || profile.protocolOptions.size > 1) unsupported()
        val configuration = exportVpnProfile(profile).trim()
        if (configuration.toByteArray(Charsets.UTF_8).size !in 1..262144 || !core &&
            (configuration.length > 16384 || configuration.any(Char::isISOControl) || !configuration.startsWith("hysteria2://"))) unsupported()
        SelectedConfiguration(configuration, core)
    }

    private suspend fun updateCountry(api: RouterPanelApi, session: RouterPanelSession, current: RouterControlSnapshot,
        code: String, direct: Boolean): RouterControlSnapshot {
        val payload = countryPayload(current, code, direct)
        if (payload["countries"] == current.countries && payload["settings"] == current.settings) return current
        return parseSnapshot(call(api, session, RouterMobileAction.SETTINGS, payload))
    }

    private fun countryPayload(current: RouterControlSnapshot, code: String, direct: Boolean): JsonObject {
        val rule = buildJsonObject { put("code", code); put("route", if (direct) "direct" else "vpn") }
        val rest = current.countries.filter { value -> (value as? JsonObject)?.optionalString("code") != code &&
            (value as? JsonPrimitive)?.contentOrNull != code }
        val countries = JsonArray(rest + rule)
        val settings = JsonObject(current.settings + ("localRules" to JsonPrimitive(true)))
        return buildJsonObject {
            put("revision", current.revision); put("settings", settings); put("countries", countries)
            put("devices", current.devices); put("sites", current.sites)
        }
    }

    private fun validateSettingsPayload(payload: JsonObject, session: RouterPanelSession) {
        val body = buildJsonObject {
            put("action", RouterMobileAction.SETTINGS.action)
            put("payload", JsonObject(payload + ("token" to JsonPrimitive(session.token())) + ("revision" to JsonPrimitive(Long.MAX_VALUE))))
        }.toString().toByteArray(Charsets.UTF_8)
        try { if (body.size > 300000) unsupported(); requireBoundedRouterJson(body) } finally { body.fill(0) }
    }

    private fun subscriptionMetadata(selection: RouterProfileSelection): JsonObject {
        val original = requireNotNull(selection.sourceUrl)
        original.ensurePublicSubscriptionUrl(resolveHost = false)
        val source = original
        val optionId = selection.selectedOptionId ?: selection.phoneProfileId
        val fields = listOf(source to 4096, selection.selectedProtocol to 32, selection.selectedNodeName to 512,
            optionId to 128, selection.phoneProfileId to 128)
        if (fields.any { (value, limit) -> value.isBlank() || value.any(Char::isISOControl) || value.toByteArray(Charsets.UTF_8).size > limit }) unsupported()
        return buildJsonObject {
            put("sourceUrl", source); put("selectedProtocol", selection.selectedProtocol); put("selectedNodeName", selection.selectedNodeName)
            put("selectedOptionId", optionId); put("phoneProfileId", selection.phoneProfileId)
        }
    }

    private fun profilePayload(snapshot: RouterControlSnapshot, configuration: String, id: String, name: String,
        core: Boolean, subscription: JsonObject?): JsonObject = buildJsonObject {
        put("revision", snapshot.revision); put("config", configuration); put("id", id); put("name", name)
        if (core) put("engine", "core")
        if (subscription != null) put("subscription", subscription)
    }

    private suspend fun readProfile(api: RouterPanelApi, session: RouterPanelSession, id: String): JsonObject =
        call(api, session, RouterMobileAction.PROFILE_GET, buildJsonObject { put("id", id) }).also { if (it.string("id") != id) malformed() }

    private fun configurationMatches(value: JsonObject, configuration: String, admittedDigest: String?, core: Boolean): Boolean {
        val engine = (value["engine"] as? JsonPrimitive)?.contentOrNull
        if (core && engine != "core" || !core && engine == "core") return false
        val digest = (value["configSha256"] as? JsonPrimitive)?.contentOrNull
        if (digest != null && !digest.matches(Regex("[a-f0-9]{64}"))) malformed()
        return admittedDigest != null && digest == admittedDigest || !core && value.string("config").trim() == configuration
    }

    private suspend fun verifyProfile(api: RouterPanelApi, session: RouterPanelSession, id: String, configuration: String,
        admittedDigest: String?, core: Boolean, subscription: JsonObject?) {
        val actual = readProfile(api, session, id)
        if (!configurationMatches(actual, configuration, admittedDigest, core) ||
            actual["subscription"].let { if (it == JsonNull) null else it } != subscription) throw RouterApiFailure(RouterApiError.UNSUPPORTED)
    }

    private suspend fun savePendingSync(pairing: RouterPairing, id: String?, digest: String?) {
        val store = pairingStore ?: unsupported()
        val saved = store.read() ?: throw RouterApiFailure(RouterApiError.CONFLICT)
        try {
            if (saved.id != pairing.id || saved.identity != pairing.identity) throw RouterApiFailure(RouterApiError.CONFLICT)
            val updated = saved.withPendingSync(id, digest)
            try { store.save(updated) } finally { updated.clear() }
        } finally { saved.clear() }
    }

    private fun syncIdentity(configuration: String, subscription: JsonObject?, core: Boolean): String = MessageDigest.getInstance("SHA-256")
        .digest(buildJsonObject { put("config", configuration); put("subscription", subscription ?: JsonNull); put("core", core) }
            .toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private suspend fun call(api: RouterPanelApi, session: RouterPanelSession, action: RouterMobileAction, payload: JsonObject): JsonObject {
        requirePaired(session)
        currentCoroutineContext().ensureActive()
        return mobileCall(api, action, JsonObject(payload + ("token" to JsonPrimitive(session.token())))).also {
            currentCoroutineContext().ensureActive()
        }
    }

    companion object {
        fun wifiAddress(manager: ConnectivityManager): (RouterTlsIdentity) -> String = { identity ->
            RouterWifiConnection.select(manager, identity.endpoint).also { it.requireCurrent() }.localAddress.hostAddress ?: malformed()
        }
    }
}

internal fun parseRouterPhoneStatus(value: JsonObject, expectedPairId: String, expectedAddress: String, nowElapsedMs: Long,
    requestDurationMs: Long = 0): RouterPhoneStatus = parsePhoneStatus(value, expectedPairId, expectedAddress, nowElapsedMs, requestDurationMs)

internal fun parseRouterPanelCapabilities(value: JsonObject): RouterPanelCapabilities {
    if (value.number("schema") != 1L) malformed()
    fun protocols(field: String, allowed: Set<String>): Set<String> {
        val names = (value[field] as? JsonArray)?.map {
            (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.contentOrNull ?: malformed()
        } ?: malformed()
        if (names.size > allowed.size || names.distinct().size != names.size || names.any { it !in allowed }) malformed()
        return names.toSet()
    }
    val core = protocols("coreProtocols", RouterInstallProtocol.entries.map { it.canonical }.toSet())
    val legacy = protocols("legacyProtocols", setOf("hysteria2"))
    val inbound = protocols("inboundProtocols", setOf("hysteria2"))
    if (value.boolean("phoneRemoteProvisioning") != inbound.isNotEmpty() || inbound.isNotEmpty() && "hysteria2" !in legacy) malformed()
    return RouterPanelCapabilities(core, legacy, inbound, value.boolean("subscriptionSelection"),
        value.boolean("independentPhoneChoices"), value.boolean("phoneRegistration"), value.boolean("homeProof"),
        value["phoneHandoff"]?.let { (it as? JsonPrimitive)?.takeIf { item -> !item.isString }?.booleanOrNull ?: malformed() } ?: false,
        value["providerResolve"]?.let { (it as? JsonPrimitive)?.takeIf { item -> !item.isString }?.booleanOrNull ?: malformed() } ?: false,
        value["profileSourceImport"]?.let { (it as? JsonPrimitive)?.takeIf { item -> !item.isString }?.booleanOrNull ?: malformed() } ?: false,
        value["packageUpdates"]?.let { (it as? JsonPrimitive)?.takeIf { item -> !item.isString }?.booleanOrNull ?: malformed() } ?: false)
}

private fun parsePhoneStatus(value: JsonObject, expectedPairId: String, address: String, now: Long, requestDurationMs: Long): RouterPhoneStatus {
    if (value.number("schema") != 1L || value.string("pairingId") != expectedPairId || value.string("address") != address ||
        !expectedPairId.matches(PAIR_ID) || now < 0 || requestDurationMs < 0) malformed()
    val deviceId = value.optionalString("lanDeviceId")
    val serverId = value.optionalString("serverId")
    val generation = value.optionalString("generation")
    if (deviceId != null && !deviceId.matches(DEVICE_ID) || serverId != null && !serverId.matches(SERVER_ID) ||
        generation != null && !generation.matches(Regex("[a-f0-9]{16}"))) malformed()
    val age = value["checkedAgeMs"].let { if (it == JsonNull) null else (it as? JsonPrimitive)?.takeIf { item -> !item.isString }?.longOrNull ?: malformed() }
    if (age != null && age < 0) malformed()
    val validated = value.boolean("validated")
    val bound = value.boolean("vpnBound")
    val routed = value.boolean("routed")
    val failClosed = value.boolean("failClosed")
    val loss = value["lossConfirmed"]?.let { (it as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: malformed() } ?: false
    val uptime = value["observedUptimeMs"].let { if (it == null || it == JsonNull) null else
        (it as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 } ?: malformed() }
    val proof = if (validated && bound && routed && failClosed && deviceId != null && serverId != null && generation != null &&
        !loss && age != null && requestDurationMs <= 5000 && age <= 5000 - requestDurationMs && age + requestDurationMs <= now)
        RouterHomeProof(expectedPairId, serverId, generation, now - age - requestDurationMs, true, true) else null
    val off = if (loss && uptime != null && requestDurationMs <= 5000) RouterHomeOffEvidence(expectedPairId, now, uptime) else null
    val handoff = value["handoff"].let { item ->
        if (item == null || item == JsonNull) null else {
            val entry = item as? JsonObject ?: malformed()
            val claim = entry.string("claimId")
            val remaining = entry.number("remainingMs")
            val revision = entry.number("policyRevision")
            if (!claim.matches(Regex("[a-f0-9]{32}")) || entry.string("mode") != "phone_vpn" || entry.string("phase") != "active" ||
                remaining !in 1..60000 || revision < 0 || now > Long.MAX_VALUE - remaining) malformed()
            RouterPhoneHandoffStatus(claim, now + (remaining - requestDurationMs).coerceAtLeast(0), revision)
        }
    }
    return RouterPhoneStatus(expectedPairId, deviceId, address, proof, off, handoff, value.containsKey("handoff"), uptime, now, requestDurationMs)
}

private fun parseSnapshot(value: JsonObject): RouterControlSnapshot {
    if (value.number("schema") != 1L) malformed()
    val revision = value.number("revision").also { if (it < 0) malformed() }
    val settings = value["settings"] as? JsonObject ?: malformed()
    settings.boolean("localRules")
    if (settings.string("localScope") !in setOf("all", "selected")) malformed()
    val countries = value.array("countries", 249)
    val devices = value.array("devices", 512)
    val sites = value.array("sites", 2048)
    val countryCodes = countries.map { entry ->
        val code = if (entry is JsonPrimitive && entry.isString) entry.content else (entry as? JsonObject)?.string("code") ?: malformed()
        val route = if (entry is JsonObject) entry.string("route") else "direct"
        if (code !in COUNTRIES || route !in setOf("direct", "vpn")) malformed()
        code
    }
    if (countryCodes.distinct().size != countryCodes.size) malformed()
    val deviceIds = devices.map { entry ->
        val device = entry as? JsonObject ?: malformed()
        device.boolean("bindMac")
        if (device.string("route") !in setOf("direct", "vpn")) malformed()
        device.string("id").also { if (!it.matches(DEVICE_ID)) malformed() }
    }
    val siteIds = sites.map { entry ->
        val site = entry as? JsonObject ?: malformed()
        if (site.string("route") !in setOf("direct", "vpn")) malformed()
        site.string("id").also { if (!it.matches(SITE_ID)) malformed() }
    }
    if (deviceIds.distinct().size != deviceIds.size || siteIds.distinct().size != siteIds.size) malformed()
    val servers = value.array("servers", 256).map { it as? JsonObject ?: malformed() }
    val clients = value.array("clients", 256).map { it as? JsonObject ?: malformed() }
    if (servers.map { it.string("id") }.distinct().size != servers.size || servers.any { !it.string("id").matches(SERVER_ID) }) malformed()
    if (clients.map { it.string("id") }.distinct().size != clients.size || clients.any { !it.string("id").matches(CLIENT_ID) }) malformed()
    val runtime = value["runtime"] as? JsonObject ?: malformed()
    return RouterControlSnapshot(revision, settings, countries, devices, sites, servers, clients,
        runtime.optionalString("desiredServerId"), runtime.boolean("connected"),
        value["packageRemovalAvailable"]?.let { (it as? JsonPrimitive)?.takeIf { field -> !field.isString }?.booleanOrNull ?: malformed() } ?: false)
}

private fun JsonObject.string(field: String): String = (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: malformed()
private fun JsonObject.optionalString(field: String): String? = this[field].let { if (it == JsonNull) null else (it as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: malformed() }
private fun JsonObject.number(field: String): Long = (this[field] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: malformed()
private fun JsonObject.boolean(field: String): Boolean = (this[field] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: malformed()
private fun JsonObject.array(field: String, limit: Int): JsonArray = (this[field] as? JsonArray)?.also { if (it.size > limit) malformed() } ?: malformed()
private fun requirePaired(session: RouterPanelSession) { if (!session.token().matches(Regex("p1\\.[a-f0-9]{64}")) || session.changePinRequired) unsupported() }
private fun malformed(): Nothing = throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
private fun unsupported(): Nothing = throw RouterApiFailure(RouterApiError.UNSUPPORTED)
private val PAIR_ID = Regex("pair-[a-f0-9]{32}")
private val SERVER_ID = Regex("server-[a-f0-9]{12}")
private val DEVICE_ID = Regex("device-[a-f0-9]{12}")
private val CLIENT_ID = Regex("client-[a-f0-9]{12}")
private val SITE_ID = Regex("site-[a-f0-9]{12}")
private val COUNTRIES = Locale.getISOCountries().toSet()
