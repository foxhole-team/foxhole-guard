package com.foxhole.guard.router

internal enum class RouterWizardPath { SETUP, PAIR }
internal enum class RouterWizardStage { ENTRY, ADDRESS, IDENTITY, PROTOCOLS, LOGIN, CHECKING, INSTALL, NEW_PIN, OPTIONS, FINISHING, COMPLETE, PIN_SETUP_REQUIRED }
internal enum class RouterWizardError { WIFI, VPN_ACTIVE, HOME_VPN, LOCAL_NETWORK_PERMISSION, SSH_UNAVAILABLE, ADDRESS, IDENTITY, PASSWORD, PIN, LOCKED, UNSUPPORTED, STORAGE, INSTALL, NETWORK, SAVE, PROFILE, PROFILE_EXPORT, UNAVAILABLE, LICENSE, LIMIT }
internal class RouterSetupFailure(val kind: RouterWizardError) : java.io.IOException("router_setup_failed")

internal data class RouterWizardOptions(
    val profileId: String? = null,
    val countryCode: String = "",
    val countryAuto: Boolean = true,
    val countrySitesDirect: Boolean = true,
    val homeAutodetection: Boolean = false,
    val registerPhone: Boolean = true,
    val publicIp: Boolean = false,
    val createRemoteProfile: Boolean = false,
    val autoUpdateEnabled: Boolean = true,
    val licenseAccepted: Boolean = false,
    val licenseGeneration: Long = 0,
) {
    val valid: Boolean get() = (countryAuto && countryCode.isEmpty() || countryCode.matches(Regex("[A-Z]{2}"))) &&
        (!createRemoteProfile || publicIp)
}

internal data class RouterWizardState(
    val stage: RouterWizardStage = RouterWizardStage.ENTRY,
    val path: RouterWizardPath = RouterWizardPath.PAIR,
    val address: String = "192.168.1.1",
    val fingerprint: String = "",
    val identityChanged: Boolean = false,
    val openWrtVersion: String = "",
    val architecture: String = "",
    val storageMode: RouterStorageMode? = null,
    val installSelection: RouterInstallProtocolSelection? = null,
    val options: RouterWizardOptions = RouterWizardOptions(),
    val routerCountryCode: String? = null,
    val installProgress: RouterInstallProgress? = null,
    val discovery: RouterDiscoveryResult? = null,
    val error: RouterWizardError? = null,
    val busy: Boolean = false,
    val remoteProfileCreated: Boolean = false,
    val remoteReady: Boolean = false,
    val inboundProtocols: Set<String> = emptySet(),
    val pairedId: String? = null,
    val installedInThisWizard: Boolean = false,
    val autoUpdateUnconfirmed: Boolean = false,
    val autoUpdateFailure: RouterUpdateFailureReason? = null,
    val licenseRequired: Boolean = false,
    val licenseVersion: Int = 0,
)

internal class RouterInstallProposal(
    val openWrtVersion: String,
    val architecture: String,
    val storageMode: RouterStorageMode,
    val manifestSha256: String,
    val transaction: AutoCloseable,
    val binding: RouterInstallBinding,
) : AutoCloseable {
    init {
        require(openWrtVersion.length in 1..32 && architecture.length in 1..64 &&
            openWrtVersion.none(Char::isISOControl) && architecture.none(Char::isISOControl))
        require(manifestSha256.matches(Regex("[a-f0-9]{64}")))
        require(binding.architecture == architecture && binding.storageMode == storageMode)
    }
    override fun close() { transaction.close() }
}

internal interface RouterSetupService {
    val installCatalog: RouterInstallCatalog?
    suspend fun inspectSshIdentity(address: String): ByteArray
    suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray,
        selection: RouterInstallProtocolSelection): RouterInstallProposal
    suspend fun install(proposal: RouterInstallProposal): RouterInstallResult
    suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray, selection: RouterInstallProtocolSelection,
        onProgress: (RouterInstallProgress) -> Unit): RouterInstallProposal = inspect(address, hostKey, password, selection)
    suspend fun install(proposal: RouterInstallProposal, onProgress: (RouterInstallProgress) -> Unit): RouterInstallResult = install(proposal)
    suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray, selection: RouterInstallProtocolSelection,
        licenseAccepted: Boolean): RouterInstallProposal = inspect(address, hostKey, password, selection, licenseAccepted) {}
    suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray, selection: RouterInstallProtocolSelection,
        licenseAccepted: Boolean, onProgress: (RouterInstallProgress) -> Unit): RouterInstallProposal {
        try { requireRouterSetupLicense(licenseAccepted) } catch (failure: Exception) { password.fill('\u0000'); throw failure }
        return inspect(address, hostKey, password, selection, onProgress)
    }
    suspend fun install(proposal: RouterInstallProposal, licenseAccepted: Boolean): RouterInstallResult =
        install(proposal, licenseAccepted) {}
    suspend fun install(proposal: RouterInstallProposal, licenseAccepted: Boolean,
        onProgress: (RouterInstallProgress) -> Unit): RouterInstallResult {
        requireRouterSetupLicense(licenseAccepted)
        return install(proposal, onProgress)
    }
}

internal class RouterInstallResult(val identity: RouterTlsIdentity, bootstrapCredential: CharArray? = null) : AutoCloseable {
    private var bootstrap: CharArray? = try {
        bootstrapCredential?.also { require(it.size == 4 && it.all { digit -> digit in '0'..'9' }) }?.copyOf()
    } finally { bootstrapCredential?.fill('\u0000') }
    fun takeBootstrapCredential(): CharArray? = bootstrap.also { bootstrap = null }
    override fun close() { bootstrap?.fill('\u0000'); bootstrap = null }
    override fun toString(): String = "RouterInstallResult"
}

internal data class RouterWizardResult(val pairedId: String, val remoteProfileUri: String? = null, val remoteReady: Boolean = false,
    val remoteProfileMarker: RouterProfileMarker? = null)

internal interface RouterWizardProvisioner {
    val supportedInboundProtocols: Set<String>
    val supportsHomeAutodetection: Boolean
    suspend fun routerCountry(api: RouterPanelApi, session: RouterPanelSession): String? = null
    suspend fun availableInboundProtocols(api: RouterPanelApi, session: RouterPanelSession): Set<String> = emptySet()
    suspend fun updateStatus(api: RouterPanelApi, session: RouterPanelSession): RouterUpdateStatus =
        throw RouterApiFailure(RouterApiError.UNSUPPORTED)
    suspend fun setUpdateAuto(api: RouterPanelApi, session: RouterPanelSession, status: RouterUpdateStatus,
        enabled: Boolean): RouterUpdateStatus = throw RouterApiFailure(RouterApiError.UNSUPPORTED)
    suspend fun finish(api: RouterPanelApi, session: RouterPanelSession, pairing: RouterPairing,
        options: RouterWizardOptions, selectedProfile: RouterProfileSelection?): RouterWizardResult
}
