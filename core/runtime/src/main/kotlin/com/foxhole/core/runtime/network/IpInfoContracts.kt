package com.foxhole.core.runtime.network

enum class ProxyAccessType {
    HTTP,
    SOCKS,
}

data class HttpProxyAccess(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val type: ProxyAccessType = ProxyAccessType.HTTP,
)

enum class IpInfoFetchMode {
    FULL,
    ENTRY_QUICK,
    GEO_ENRICHMENT,
}

const val DNS_INDEPENDENT_IP_INFO_ENDPOINT = "https://1.1.1.1/cdn-cgi/trace"

const val TOR_CHECK_IP_INFO_ENDPOINT = "https://check.torproject.org/api/ip"
