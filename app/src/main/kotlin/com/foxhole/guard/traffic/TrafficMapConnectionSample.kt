package com.foxhole.guard.traffic

data class TrafficMapConnectionSample(
    val connectionId: String,
    val countryCode: String,
    val bytes: Long,
    val connections: Int = 1,
    val kind: TrafficMapConnectionSampleKind = TrafficMapConnectionSampleKind.DESTINATION,
)

data class TrafficMapRuntimeSnapshotSamples(
    val generation: Long,
    val samples: List<TrafficMapConnectionSample>,
)

enum class TrafficMapConnectionSampleKind {
    DESTINATION,
    DNS_SERVER,
}
