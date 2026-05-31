package com.foxhole.beta.core.network

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException

internal class PublicRemoteDns(
    private val delegate: (String) -> List<InetAddress>,
    private val fallback: PublicDnsFallback = PublicDohDnsFallback,
) : Dns {
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
        if (publicAddresses.isEmpty()) {
            return fallbackPublicAddresses(
                hostname,
                UnknownHostException("private, reserved, or loopback hosts are not allowed: $hostname"),
            )
        }
        return publicAddresses.preferIpv4()
    }

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

internal fun List<InetAddress>.preferIpv4(): List<InetAddress> =
    sortedWith(
        compareBy<InetAddress> { it !is Inet4Address }
            .thenBy { it.hostAddress.orEmpty() },
    )
