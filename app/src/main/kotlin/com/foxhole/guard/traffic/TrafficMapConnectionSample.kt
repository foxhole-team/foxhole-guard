package com.foxhole.guard.traffic

data class TrafficMapConnectionSample(
    val connectionId: String,
    val countryCode: String,
    val bytes: Long,
    val connections: Int = 1,
    val kind: TrafficMapConnectionSampleKind = TrafficMapConnectionSampleKind.DESTINATION,
)

enum class TrafficMapConnectionSampleKind {
    DESTINATION,
    DNS_SERVER,
}
