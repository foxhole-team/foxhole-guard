package com.foxhole.guard.router

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.PersistableBundle
import com.foxhole.guard.core.data.ProfileRepository
import com.foxhole.guard.FoxholeApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.security.MessageDigest

internal class GuardRouterRuntime(context: Context, repository: ProfileRepository) : AutoCloseable {
    private val application = context.applicationContext
    private val manager = requireNotNull(application.getSystemService(ConnectivityManager::class.java))
    private val pairing = EncryptedRouterPairingStore(application)
    private val preferences = LocalRouterControlPreferenceStore(application, pairings = pairing)
    private val protection = object : RouterSocketProtection {
        override fun protect(socket: java.net.Socket): Boolean = GuardRouterSocketOwners.protect(socket)
        override fun isCurrent(socket: java.net.Socket): Boolean = GuardRouterSocketOwners.isCurrent(socket)
    }
    private val access = { requireRouterLocalNetworkAccess(application) }
    private val transport = WifiRouterWizardTransport(manager, socketProtection = protection, accessCheck = access)
    private val provisioner = RouterHttpProvisioner(localAddress = { identity ->
        requireNotNull(RouterWifiConnection.select(manager, identity.endpoint, protection, access).localAddress.hostAddress)
    }, pairingStore = pairing)
    private val profiles = GuardRouterProfiles(repository, pairing)
    private val setup = readGuardRouterReleaseAnchor(application)?.let {
        WifiRouterSetupService.forWifi(manager, it, protection, access)
    }
    val wizard = RouterWizardCoordinator(transport, pairing, Build.MODEL.take(128).ifBlank { "Android" }, setup, provisioner,
        exportProfile = profiles::selected, importRemoteProfile = profiles::importProfile, preferenceStore = preferences,
        importAuthenticatedProfile = profiles::importAuthenticated)
    val control = RouterControlCoordinator(PairedRouterManagementTransport(transport, manager, access), pairing, preferences, provisioner,
        // This is also disabled in VPN until physical foreground-service acceptance.
        homeAutomationAvailable = false, copyProfile = { value ->
            withContext(Dispatchers.Main) {
                val clipboard = requireNotNull(application.getSystemService(ClipboardManager::class.java))
                val clip = ClipData.newPlainText("VPN", value)
                if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
                clipboard.setPrimaryClip(clip)
            }
        }, importProfile = profiles::importProfile, importAuthenticatedProfile = profiles::importAuthenticated)

    val profileProbe get() = (application as FoxholeApplication).routerProfileMonitor.profileProbe

    override fun close() { wizard.close(); control.close() }
}

private fun readGuardRouterReleaseAnchor(context: Context): RouterSetupRelease? {
    val expected = GUARD_ROUTER_QUALIFIED_ANCHOR_SHA256 ?: return null
    val bytes = try {
        context.assets.open("router-release-anchor.json").use { input ->
            val buffer = ByteArray(4097)
            var size = 0
            while (size < buffer.size) {
                val read = input.read(buffer, size, buffer.size - size)
                if (read < 0) break
                require(read > 0)
                size += read
            }
            require(size in 1..4096)
            buffer.copyOf(size)
        }
    } catch (_: FileNotFoundException) { return null }
    require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } == expected)
    val anchor = routerInstallDocument(bytes, 4096)
    require(anchor.installNumber("schema") == 1)
    return GithubRouterSetupRelease(anchor.installText("baseUrl"), anchor.installText("manifestSha256"))
}

// Root binds only an actually qualified final router anchor; old intermediate anchors are not reused.
internal val GUARD_ROUTER_QUALIFIED_ANCHOR_SHA256: String? = null
