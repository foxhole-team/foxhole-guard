package com.foxhole.beta.core.traffic

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface TrafficMapConnectionSource {
    fun connectionSamples(runtimeAvailable: Flow<Boolean>): Flow<List<TrafficMapConnectionSample>>
}

data class TrafficMapConnectionSample(
    val connectionId: String,
    val countryCode: String,
    val bytes: Long,
    val connections: Int = 1,
)

object EmptyTrafficMapConnectionSource : TrafficMapConnectionSource {
    override fun connectionSamples(runtimeAvailable: Flow<Boolean>): Flow<List<TrafficMapConnectionSample>> =
        flowOf(emptyList())
}
