package com.foxhole.guard.router

import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean

internal data class RouterSetupPayload(
    val variant: RouterInstallVariant?,
    val budgets: Map<RouterStorageMode, RouterInstallBudget>,
    val installer: RouterVerifiedInstallScript,
    val baseUrl: String,
    val packagesByMode: Map<RouterStorageMode, List<RouterPackageIdentity>>,
    val compact: RouterCompactPayload? = null,
)

internal interface RouterSetupRelease {
    val catalog: RouterInstallCatalog?
    val supportedPlatforms: Set<RouterPlatform>
    suspend fun initialize()
    suspend fun payload(system: RouterSystemInspection, selection: RouterInstallProtocolSelection): RouterSetupPayload
    suspend fun payload(system: RouterSystemInspection, selection: RouterInstallProtocolSelection,
        onProgress: (RouterInstallProgress) -> Unit): RouterSetupPayload = payload(system, selection)
}

internal class WifiRouterSetupService(
    private val release: RouterSetupRelease,
    private val connection: (String, ByteArray?) -> RouterSshClient,
    private val requireAccess: () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val commandSource: (RouterSshClient, RouterSetupPayload, RouterInstallProposal) -> RouterInstallCommandSource =
        { _, payload, _ -> RouterInstallCommandSource.github(payload.baseUrl) },
) : RouterSetupService {
    override val installCatalog: RouterInstallCatalog? get() = release.catalog

    override suspend fun inspectSshIdentity(address: String): ByteArray {
        requireAccess()
        val observed = try {
            routerIo(io) { operation ->
                connection(address, null).use { client ->
                    operation.register(client)
                    try { client.connect(null); throw RouterSetupFailure(RouterWizardError.SSH_UNAVAILABLE) }
                    catch (candidate: RouterSshTrustRequired) { candidate.key }
                }
            }
        } catch (_: RouterSshFailure) { throw RouterSetupFailure(RouterWizardError.SSH_UNAVAILABLE) }
        return try { release.initialize(); observed }
        catch (failure: Exception) { observed.fill(0); throw failure }
    }

    override suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray,
        selection: RouterInstallProtocolSelection): RouterInstallProposal = inspect(address, hostKey, password, selection, false) {}

    override suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray,
        selection: RouterInstallProtocolSelection, onProgress: (RouterInstallProgress) -> Unit): RouterInstallProposal =
        inspect(address, hostKey, password, selection, false, onProgress)

    override suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray,
        selection: RouterInstallProtocolSelection, licenseAccepted: Boolean,
        onProgress: (RouterInstallProgress) -> Unit): RouterInstallProposal {
        try {
            requireRouterSetupLicense(licenseAccepted)
            requireAccess()
            val catalog = release.catalog ?: throw RouterSetupFailure(RouterWizardError.UNAVAILABLE)
            require(selection.catalogSha256 == catalog.sha256 && catalog.accepts(selection.protocols, selection.includeServer))
            val system = retainedRouterIo(io) { register ->
                val opened = connection(address, hostKey).also { register(it) }
                opened.connect(password)
                InspectedRouter(opened, inspectRouterSystem(opened, release.supportedPlatforms).let { facts ->
                    if (catalog.schema == 2) facts.copy(compactBackupBytes = parseRouterCompactBackupBytes(opened.readCompactBackupBytes())) else facts
                })
            }
            try {
                val payload = release.payload(system.facts, selection, onProgress)
                val budget = completeRouterBudget(payload.budgets)
                val mode = chooseRouterStorage(system.facts.resources, budget) ?: throw RouterSetupFailure(RouterWizardError.STORAGE)
                val variant = payload.variant
                val compact = payload.compact?.modes?.get(mode)?.binding
                val binding = if (compact != null) RouterInstallBinding(selection, compact.platform.id, system.facts.platform.architecture,
                    mode, null, null, null, null, compact.budgetSha256, system.facts.platform.target, compact)
                else requireNotNull(variant).let { RouterInstallBinding(selection, it.id, system.facts.platform.architecture, mode,
                    it.coreProducerSha256, it.coreBinarySha256, it.coreSourceSha256, it.capabilitiesSha256,
                    requireNotNull(it.modes[mode]).sha256, system.facts.platform.target) }

                require(catalog.matches(binding, system.facts.openWrtVersion, catalog.manifestSha256))
                val transaction = SshInstallTransaction(address, system.client, system.facts, payload, budget, binding)
                return RouterInstallProposal(system.facts.openWrtVersion, binding.architecture, mode,
                    catalog.manifestSha256, transaction, binding)
            } catch (failure: Exception) { system.close(); throw failure }
        } catch (_: RouterInspectionFailure) { throw RouterSetupFailure(RouterWizardError.UNSUPPORTED) }
        catch (_: RouterSshAuthenticationFailed) { throw RouterSetupFailure(RouterWizardError.PASSWORD) }
        catch (_: RouterSshFailure) { throw RouterSetupFailure(RouterWizardError.SSH_UNAVAILABLE) }
        finally { password.fill('\u0000') }
    }

    override suspend fun install(proposal: RouterInstallProposal): RouterInstallResult = install(proposal, false) {}

    override suspend fun install(proposal: RouterInstallProposal, onProgress: (RouterInstallProgress) -> Unit): RouterInstallResult =
        install(proposal, false, onProgress)

    override suspend fun install(proposal: RouterInstallProposal, licenseAccepted: Boolean,
        onProgress: (RouterInstallProgress) -> Unit): RouterInstallResult {
        requireRouterSetupLicense(licenseAccepted)
        requireAccess()
        val catalog = release.catalog ?: throw RouterSetupFailure(RouterWizardError.UNAVAILABLE)
        val transaction = proposal.transaction as? SshInstallTransaction ?: throw RouterSetupFailure(RouterWizardError.INSTALL)
        require(transaction.binding == proposal.binding && catalog.matches(proposal.binding, proposal.openWrtVersion, proposal.manifestSha256))
        check(transaction.started.compareAndSet(false, true))
        return retainedRouterIo(io) { register ->
            register(transaction.client)
            try {
                val current = inspectRouterSystem(transaction.client, release.supportedPlatforms).let { facts ->
                    if (transaction.binding.compact != null) facts.copy(compactBackupBytes = parseRouterCompactBackupBytes(transaction.client.readCompactBackupBytes())) else facts
                }
                require(current.openWrtVersion == transaction.system.openWrtVersion && current.platform == transaction.system.platform &&
                    current.kernelVersion == transaction.system.kernelVersion)
                val budget = transaction.payload.compact?.modes?.get(proposal.storageMode)?.resources(requireNotNull(current.compactBackupBytes))
                    ?: transaction.budget
                if (!routerStorageFits(current.resources, budget, proposal.storageMode)) throw RouterSetupFailure(RouterWizardError.STORAGE)
                val source = commandSource(transaction.client, transaction.payload, proposal)
                transaction.client.install(transaction.payload.installer, RouterInstallCommand(source,
                    proposal.manifestSha256, proposal.storageMode, proposal.binding.selection.protocols, proposal.binding.selection.includeServer,
                    licenseAccepted = licenseAccepted), onProgress)
                requireAccess()
                onProgress(RouterInstallProgress(RouterInstallProgressStage.CHECK))
                verifyRouterInstalledPackage(transaction.client.read(RouterReadCommand.PACKAGE_MANIFEST), proposal.binding, current,
                    requireNotNull(transaction.payload.packagesByMode[proposal.storageMode]), transaction.payload.variant?.serverProducer?.sha256)
                val identity = routerIdentityFromSshCertificate(transaction.address, transaction.client.read(RouterReadCommand.MOBILE_CERTIFICATE))
                val bootstrap = routerBootstrapCredential(transaction.client.bootstrapEnrollment())
                RouterInstallResult(identity, bootstrap)
            } catch (_: RouterSshFailure) {
                throw RouterSetupFailure(RouterWizardError.INSTALL)
            } finally { transaction.client.close() }
        }
    }

    companion object {
        fun forWifi(manager: ConnectivityManager, release: RouterSetupRelease, protection: RouterSocketProtection,
            requireAccess: () -> Unit,
            commandSource: (RouterSshClient, RouterSetupPayload, RouterInstallProposal) -> RouterInstallCommandSource =
                { _, payload, _ -> RouterInstallCommandSource.github(payload.baseUrl) },
        ): WifiRouterSetupService = WifiRouterSetupService(release, { address, key ->
                val endpoint = RouterEndpoint(address, 22)
                val wifi = RouterWifiConnection.select(manager, endpoint, protection, requireAccess)
                RouterSshConnection(endpoint.socketAddress(), "foxhole-router-$address", key, wifi)
            }, requireAccess, commandSource = commandSource)
    }
}

private class InspectedRouter(val client: RouterSshClient, val facts: RouterSystemInspection) : AutoCloseable {
    override fun close() { client.close() }
}

private class SshInstallTransaction(val address: String, val client: RouterSshClient, val system: RouterSystemInspection,
    val payload: RouterSetupPayload, val budget: RouterInstallBudget, val binding: RouterInstallBinding) : AutoCloseable {
    val started = AtomicBoolean()
    override fun close() { client.close() }
}

internal fun completeRouterBudget(modes: Map<RouterStorageMode, RouterInstallBudget>): RouterInstallBudget {
    val flash = modes[RouterStorageMode.FLASH]
    val ram = modes[RouterStorageMode.RAM]
    require(flash != null || ram != null)
    return RouterInstallBudget(flash?.flashPersistentBytes ?: Long.MAX_VALUE, ram?.ramPersistentBytes ?: Long.MAX_VALUE,
        flash?.flashWorkingMemoryBytes ?: Long.MAX_VALUE, ram?.ramWorkingMemoryBytes ?: Long.MAX_VALUE)
}

internal fun routerStorageFits(facts: RouterResourceFacts, budget: RouterInstallBudget, mode: RouterStorageMode): Boolean {
    val flash = if (mode == RouterStorageMode.FLASH) budget.flashPersistentBytes else budget.ramPersistentBytes
    val ram = if (mode == RouterStorageMode.FLASH) budget.flashWorkingMemoryBytes else budget.ramWorkingMemoryBytes
    return flash > 0 && ram > 0 && facts.availableFlashBytes >= flash && facts.availableRamBytes >= ram
}

internal fun routerIdentityFromSshCertificate(address: String, pem: ByteArray): RouterTlsIdentity {
    if (pem.size !in 1..32768) throw IOException("router_certificate_invalid")
    val certificates = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(pem))
    val certificate = certificates.singleOrNull() as? X509Certificate ?: throw IOException("router_certificate_invalid")
    certificate.checkValidity()
    require(certificate.encoded.size <= 16384)
    return RouterTlsIdentity(RouterEndpoint(address), sha256RouterCertificate(certificate.encoded))
}
