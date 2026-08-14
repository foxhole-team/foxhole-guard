package com.foxhole.core.runtime

fun dnsIndependentConnectivityProbeTargets(): List<VpnHealthProbeTarget> =
    DNS_INDEPENDENT_CONNECTIVITY_PROBE_TARGETS

private val DNS_INDEPENDENT_CONNECTIVITY_PROBE_TARGETS =
    listOf(
        VpnHealthProbeTarget(
            host = "1.1.1.1",
            port = 443,
            transport = VpnHealthProbeTransport.TCP,
        ),
        VpnHealthProbeTarget(
            host = "1.0.0.1",
            port = 443,
            transport = VpnHealthProbeTransport.TCP,
        ),
        VpnHealthProbeTarget(
            host = "2606:4700:4700::1111",
            port = 443,
            transport = VpnHealthProbeTransport.TCP,
        ),
        VpnHealthProbeTarget(
            host = "2606:4700:4700::1001",
            port = 443,
            transport = VpnHealthProbeTransport.TCP,
        ),
    )
