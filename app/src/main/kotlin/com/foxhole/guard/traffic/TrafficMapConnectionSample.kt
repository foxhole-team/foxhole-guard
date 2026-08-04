package com.foxhole.guard.traffic

data class TrafficMapConnectionSample(
    val connectionId: String,
    val countryCode: String,
    val bytes: Long,
    val connections: Int = 1,
    val kind: TrafficMapConnectionSampleKind = TrafficMapConnectionSampleKind.DESTINATION,
)

// What a sampled connection represents on the map: an app's destination, or the DNS resolver
// egress (kept out of the destination country aggregation and shown as its own node).
enum class TrafficMapConnectionSampleKind {
    DESTINATION,
    DNS_SERVER,
}
