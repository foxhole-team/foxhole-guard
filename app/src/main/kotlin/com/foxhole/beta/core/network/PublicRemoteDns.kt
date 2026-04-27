package com.foxhole.beta.core.network

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

internal class PublicRemoteDns(
    private val delegate: (String) -> List<InetAddress>,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        hostname.requirePublicRemoteHost(resolveHost = false)
        val addresses =
            try {
                delegate(hostname)
            } catch (error: UnknownHostException) {
                throw error
            } catch (error: RuntimeException) {
                throw UnknownHostException("unable to resolve remote host: $hostname").apply { initCause(error) }
            }
        if (addresses.isEmpty()) {
            throw UnknownHostException("unable to resolve remote host: $hostname")
        }
        val publicAddresses = addresses.filterNot(InetAddress::isPrivateOrLocalAddress)
        if (publicAddresses.isEmpty()) {
            throw UnknownHostException("private, reserved, or loopback hosts are not allowed: $hostname")
        }
        return publicAddresses
    }
}
