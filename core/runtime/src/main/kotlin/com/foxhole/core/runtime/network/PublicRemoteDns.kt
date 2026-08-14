package com.foxhole.core.runtime.network

import com.foxhole.core.network.isPrivateOrLocalAddress
import com.foxhole.core.network.isTunnelSynthesizedAddress
import com.foxhole.core.network.requirePublicRemoteHost
import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException

class PublicRemoteDns(
    private val delegate: (String) -> List<InetAddress>,
    private val fallback: PublicDnsFallback = PublicDohDnsFallback,
) : Dns {
    @Suppress("ReturnCount")
    override fun lookup(hostname: String): List<InetAddress> {
        hostname.requirePublicRemoteHost(resolveHost = false)
        val delegateAddresses =
            try {
                delegate(hostname)
            } catch (error: UnknownHostException) {
                return fallbackPublicAddresses(hostname, error)
            } catch (error: RuntimeException) {
                return fallbackPublicAddresses(
                    hostname,
                    UnknownHostException("unable to resolve remote host: $hostname").apply { initCause(error) },
                )
            }
        if (delegateAddresses.isEmpty()) {
            return fallbackPublicAddresses(hostname, UnknownHostException("unable to resolve remote host: $hostname"))
        }
        val publicAddresses = delegateAddresses.filterNot(InetAddress::isPrivateOrLocalAddress)
        if (publicAddresses.isNotEmpty()) {
            return publicAddresses.preferIpv4()
        }
        // The system resolver answered out of FoxCore's own fake-IP pool, which happens to every
        // name on the device while an overlay tunnel is up — this app's own control plane
        // included, because the profile TUN captures the whole device by design
        // (RuntimeTunInbound: the split lives in package_name route rules, not in the interface).
        //
        // That is not a private host and must not be reported as one. It is also the address that
        // actually works right now: the stack terminates the flow and dials the name it stands
        // for, so handing it back is what lets a subscription refresh — and the resolved-config
        // sanitiser on the connect path — keep working through the tunnel. Folding it into the
        // refusal below made every session rebuild throw `UnknownHostException` the moment a
        // fake-IP tunnel was up, and the reload path answers that by tearing the tunnel down.
        val synthesizedAddresses = delegateAddresses.filter(InetAddress::isTunnelSynthesizedAddress)
        if (synthesizedAddresses.isNotEmpty()) {
            return synthesizedAddresses.preferIpv4()
        }
        return fallbackPublicAddresses(
            hostname,
            UnknownHostException("private, reserved, or loopback hosts are not allowed: $hostname"),
        )
    }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private fun fallbackPublicAddresses(
        hostname: String,
        primaryFailure: UnknownHostException,
    ): List<InetAddress> {
        val fallbackAddresses =
            try {
                fallback.lookup(hostname)
            } catch (fallbackFailure: UnknownHostException) {
                throw primaryFailure.apply { addSuppressed(fallbackFailure) }
            } catch (fallbackFailure: RuntimeException) {
                throw primaryFailure.apply { addSuppressed(fallbackFailure) }
            }
        val publicFallbackAddresses = fallbackAddresses.filterNot(InetAddress::isPrivateOrLocalAddress)
        if (publicFallbackAddresses.isEmpty()) {
            throw primaryFailure
        }
        return publicFallbackAddresses.preferIpv4()
    }
}

fun List<InetAddress>.preferIpv4(): List<InetAddress> =
    sortedWith(
        compareBy<InetAddress> { it !is Inet4Address }
            .thenBy { it.hostAddress.orEmpty() },
    )
