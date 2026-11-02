package com.foxhole.guard.router

import android.content.Context
import android.net.ConnectivityManager
import com.foxhole.guard.core.data.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class GuardRouterPersonalVpnStart(context: Context, repository: ProfileRepository) {
    private val access = GuardRouterRepositoryProfileAccess(repository)
    private val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
    private val protection = object : RouterSocketProtection {
        override fun protect(socket: java.net.Socket) = GuardRouterSocketOwners.protect(socket)
        override fun isCurrent(socket: java.net.Socket) = GuardRouterSocketOwners.isCurrent(socket)
    }
    private val guard = RouterPersonalVpnStartGuard(EncryptedRouterPairingStore(context),
        WifiRouterWizardTransport(manager, socketProtection = protection,
            accessCheck = { requireRouterLocalNetworkAccess(context) },
            nsd = context.getSystemService(android.net.nsd.NsdManager::class.java)))

    suspend fun requireAway(profileId: Long, optionId: String?, currentStart: () -> Boolean) {
        val capture = try { access.capture(profileId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return }
        if (optionId != null && optionId != capture.selected?.id) return
        currentCoroutineContext().ensureActive()
        guard.requireAway(profileId.toString(), routerProfileDigest(capture.rawConfig)) {
            currentStart() && access.isCurrent(capture)
        }
    }
}
