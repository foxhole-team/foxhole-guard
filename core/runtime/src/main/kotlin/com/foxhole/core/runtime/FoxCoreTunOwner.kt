package com.foxhole.core.runtime

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.foxhole.core.model.FoxCoreTunPlan

/**
 * Android's side of the kernel TUN for one [FoxCoreRuntime]: it builds the interface from a
 * translated plan, closes the master descriptor, and keeps the descriptor number after the session
 * is gone. Extracted from FoxCoreRuntime unchanged (class split by domain) — the runtime remains
 * the only caller, so the master still has exactly one owner.
 */
internal class FoxCoreTunOwner(
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
) {
    /**
     * Master TUN descriptor number, kept after the session is cleared: the teardown watchdog runs
     * when the session is gone and otherwise cannot tell the master we hold by design from a
     * descriptor the core failed to close — and its answer to a leak is killing the process.
     * Cleared only where the master is actually closed.
     */
    @Volatile
    var masterTunFd: Int? = null
        private set

    fun establish(
        host: RuntimeServiceHost,
        underlying: Network,
        plan: FoxCoreTunPlan,
    ): ParcelFileDescriptor? {
        if (!host.hasVpnPermission()) {
            return null
        }
        val builder =
            host.createTunBuilder()
                ?.setSession("FoxHole")
                ?.setMtu(plan.mtu)
                ?: return null
        builder.setUnderlyingNetworks(arrayOf(underlying))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(isMetered(host, underlying))
        }
        builder.addAddress(plan.ipv4Address, plan.ipv4PrefixLength)
        val ipv6Address = plan.ipv6Address
        val ipv6PrefixLength = plan.ipv6PrefixLength
        if (ipv6Address != null && ipv6PrefixLength != null) {
            builder.addAddress(ipv6Address, ipv6PrefixLength)
        }
        plan.routes.forEach { route ->
            builder.addRoute(route.address, route.prefixLength)
        }
        if (!applyApplicationSplit(builder, plan)) {
            return null
        }
        if (plan.advertisedDnsServers.isEmpty()) {
            return null
        }
        plan.advertisedDnsServers.forEach(builder::addDnsServer)
        return runCatching { builder.establish() }
            .onFailure { diagnosticsLogger.record("foxcore", "TUN establish failed") }
            .getOrNull()
            ?.also { established -> masterTunFd = established.fd }
    }

    fun close(tun: ParcelFileDescriptor): Boolean =
        runCatching {
            val closingFd = tun.fd
            tun.close()
            val closed = !tun.fileDescriptor.valid()
            if (closed && masterTunFd == closingFd) {
                // Only here, and only on a close that took: while this points at a descriptor,
                // the teardown watchdog must treat it as ours, not a leak. A timed-out start may
                // finish after its successor established another TUN, so the old close must not
                // erase the successor's ownership marker.
                masterTunFd = null
            }
            closed
        }.onFailure {
            diagnosticsLogger.record("foxcore", "TUN close failed")
        }.getOrDefault(false)

    fun recordOwnershipAfterClose(
        session: ActiveFoxCoreSession,
        nativeTunProbe: NativeTunDescriptorProbe,
        masterTunClosed: Boolean,
        reason: String,
    ) {
        val masterTunProbe = probeTunDescriptor(session.masterTunFd)
        diagnosticsLogger.recordStructured(
            "foxcore",
            "TUN ownership after native close",
            "reason=$reason",
            "master_fd=${session.masterTunFd}",
            "master_fd_state=${masterTunProbe.name.lowercase()}",
            "master_fd_open=${masterTunProbe == NativeTunDescriptorProbe.OPEN}",
            "master_close_ok=$masterTunClosed",
            "native_fd=${session.nativeTunFd}",
            "native_fd_state=${nativeTunProbe.name.lowercase()}",
            "native_fd_open=${nativeTunProbe == NativeTunDescriptorProbe.OPEN}",
            // Only when something is still held: clean teardowns must not add a line per stop to
            // an exportable journal; unclean ones need to say whether the core gave up waiting
            // for its own descriptor.
            "core=${foxCoreLastStopDiagnostics()}"
                .takeIf { nativeTunProbe == NativeTunDescriptorProbe.OPEN || !masterTunClosed },
        )
    }

    private fun applyApplicationSplit(
        builder: android.net.VpnService.Builder,
        plan: FoxCoreTunPlan,
    ): Boolean {
        val include = plan.allowedApplications
        val exclude = plan.disallowedApplications
        if (include.isNotEmpty() && exclude.isNotEmpty()) {
            return false
        }
        var applied = 0
        var skipped = 0
        val packages = if (include.isNotEmpty()) include else exclude
        packages.forEach { packageName ->
            try {
                if (include.isNotEmpty()) {
                    builder.addAllowedApplication(packageName)
                } else {
                    builder.addDisallowedApplication(packageName)
                }
                applied += 1
            } catch (_: PackageManager.NameNotFoundException) {
                skipped += 1
            }
        }
        diagnosticsLogger.recordStructured(
            "split",
            "FoxCore application split prepared",
            "mode=${if (include.isNotEmpty()) "include" else if (exclude.isNotEmpty()) "exclude" else "all"}",
            "requested=${packages.size}",
            "applied=$applied",
            "skipped=$skipped",
        )
        return include.isEmpty() || applied > 0
    }

    private fun isMetered(
        host: RuntimeServiceHost,
        network: Network,
    ): Boolean {
        val connectivity =
            host.runtimeContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.getNetworkCapabilities(network)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
    }
}

/** Whether a descriptor number still points at a live tun device (see [shouldCloseMasterTun]). */
internal fun probeTunDescriptor(fd: Int): NativeTunDescriptorProbe =
    try {
        if (Os.readlink("/proc/self/fd/$fd").startsWith("/dev/tun")) {
            NativeTunDescriptorProbe.OPEN
        } else {
            NativeTunDescriptorProbe.CLOSED
        }
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.EBADF) {
            NativeTunDescriptorProbe.CLOSED
        } else {
            NativeTunDescriptorProbe.UNKNOWN
        }
    } catch (_: RuntimeException) {
        NativeTunDescriptorProbe.UNKNOWN
    }
