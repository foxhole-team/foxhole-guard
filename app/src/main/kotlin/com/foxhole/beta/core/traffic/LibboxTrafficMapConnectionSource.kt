package com.foxhole.beta.core.traffic

import android.content.Context
import com.foxhole.beta.core.anomaly.DnsRuntimeStats
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.Connection
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.ConnectionIterator
import io.nekohasekai.libbox.Connections
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.nekohasekai.libbox.CommandClient as LibboxCommandClient

@OptIn(ExperimentalCoroutinesApi::class)
internal class LibboxTrafficMapConnectionSource(
    context: Context,
) : TrafficMapConnectionSource {
    private val countryResolver = TorGeoIpCountryResolver(context.applicationContext)

    override fun connectionSamples(runtimeAvailable: Flow<Boolean>): Flow<List<TrafficMapConnectionSample>> =
        runtimeAvailable
            .distinctUntilChanged()
            .flatMapLatest { available ->
                if (available) {
                    retryingConnectionSamples()
                } else {
                    flowOf(emptyList())
                }
            }
            .distinctUntilChanged()

    private fun retryingConnectionSamples(): Flow<List<TrafficMapConnectionSample>> =
        flow {
            while (currentCoroutineContext().isActive) {
                observeConnectionSamples()
                    .catch { emit(emptyList()) }
                    .collect(::emit)
                emit(emptyList())
                delay(ReconnectDelayMillis)
            }
        }.flowOn(Dispatchers.IO)

    private fun observeConnectionSamples(): Flow<List<TrafficMapConnectionSample>> =
        callbackFlow {
            val lock = Any()
            var connections = Connections()
            val handler =
                object : CommandClientHandler {
                    override fun connected() = Unit

                    override fun disconnected(message: String?) {
                        trySend(emptyList())
                        close()
                    }

                    override fun writeConnectionEvents(events: ConnectionEvents?) {
                        if (events == null) {
                            return
                        }
                        synchronized(lock) {
                            if (events.reset) {
                                connections = Connections()
                            }
                            connections.applyEvents(events)
                        }
                    }

                    override fun writeStatus(message: StatusMessage?) = Unit
                    override fun setDefaultLogLevel(level: Int) = Unit
                    override fun clearLogs() = Unit
                    override fun writeLogs(messageList: LogIterator?) = Unit
                    override fun writeGroups(message: OutboundGroupIterator?) = Unit
                    override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit
                    override fun updateClashMode(newMode: String?) = Unit
                }
            val options =
                CommandClientOptions().apply {
                    addCommand(Libbox.CommandConnections)
                    statusInterval = StatusIntervalNanos
                }
            val client = LibboxCommandClient(handler, options)
            val ticker =
                launch(Dispatchers.IO) {
                    while (isActive) {
                        val samples =
                            synchronized(lock) {
                                connections.toTrafficMapSamples(countryResolver)
                            }
                        trySend(samples)
                        delay(SampleIntervalMillis)
                    }
                }
            runCatching { client.connect() }
                .onFailure { error -> close(error) }
            awaitClose {
                ticker.cancel()
                runCatching { client.disconnect() }
            }
        }

    private companion object {
        const val SampleIntervalMillis = 3_000L
        const val ReconnectDelayMillis = 1_000L
        const val StatusIntervalNanos = 1_000_000_000L
    }
}

private fun Connections.toTrafficMapSamples(countryResolver: TorGeoIpCountryResolver): List<TrafficMapConnectionSample> =
    iterator()
        .toConnectionList()
        .asSequence()
        .onEach { connection ->
            if (connection.outboundType.equals(DNS_OUTBOUND_TYPE, ignoreCase = true)) {
                DnsRuntimeStats.recordDnsConnection(connection.stableTrafficMapConnectionId())
            }
        }
        .filterNot { connection -> connection.outboundType.equals(DNS_OUTBOUND_TYPE, ignoreCase = true) }
        .mapNotNull { connection -> connection.toTrafficMapSample(countryResolver) }
        .toList()

private fun Connection.toTrafficMapSample(countryResolver: TorGeoIpCountryResolver): TrafficMapConnectionSample? {
    val countryCode = countryResolver.countryCodeForDestination(destination) ?: return null
    return TrafficMapConnectionSample(
        connectionId = stableTrafficMapConnectionId(),
        countryCode = countryCode,
        bytes = maxOf(uplinkTotal + downlinkTotal, uplink + downlink, 0L),
    )
}

private fun Connection.stableTrafficMapConnectionId(): String =
    id.takeIf(String::isNotBlank) ?: "$network|$source|$destination|$createdAt"

private fun ConnectionIterator.toConnectionList(): List<Connection> =
    buildList {
        while (hasNext()) {
            add(next())
        }
    }

private const val DNS_OUTBOUND_TYPE = "dns"
