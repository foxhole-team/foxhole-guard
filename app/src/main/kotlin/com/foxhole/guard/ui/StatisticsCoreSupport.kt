package com.foxhole.guard.ui

import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProfileTrafficUiItem
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.StatisticsRange
import com.foxhole.core.model.StatisticsRetention
import com.foxhole.core.model.TransportProtocol
import com.foxhole.guard.statistics.StatisticsDisplayRange

internal fun List<ProfileTrafficUiItem>.singleKnownTransportOrUnknown(): TransportProtocol =
    map(ProfileTrafficUiItem::transport)
        .filterNot { it == TransportProtocol.UNKNOWN }
        .distinct()
        .singleOrNull()
        ?: TransportProtocol.UNKNOWN

internal val StatisticsRetention.durationMs: Long?
    get() = when (this) {
        StatisticsRetention.WEEK -> 7L * DAY_MS
        StatisticsRetention.MONTH -> 31L * DAY_MS
        StatisticsRetention.MONTHS_3 -> 93L * DAY_MS
        StatisticsRetention.FOREVER -> null
    }

internal val StatisticsDisplayRange.durationMs: Long?
    get() = when (this) {
        StatisticsDisplayRange.HOURS_24 -> DAY_MS
        StatisticsDisplayRange.WEEK -> 7L * DAY_MS
        StatisticsDisplayRange.MONTH -> 31L * DAY_MS
        StatisticsDisplayRange.ALL -> null
    }

internal fun Profile.statisticsProtocolHints(): List<ProtocolHint> =
    (protocolOptions.map(ProfileProtocolOption::protocolHint) + protocolHint).distinct()

internal fun Profile.runtimeProtocolHint(): ProtocolHint = selectedProtocolOptionId
    ?.takeIf(String::isNotBlank)
    ?.let { selected -> protocolOptions.firstOrNull { it.id == selected } }
    ?.protocolHint
    ?: protocolOptions.firstOrNull(ProfileProtocolOption::isSelected)?.protocolHint
    ?: protocolOptions.firstOrNull()?.protocolHint
    ?: protocolHint

internal fun StatisticsRetention.toStatisticsRange(): StatisticsRange = when (this) {
    StatisticsRetention.WEEK -> StatisticsRange.WEEK
    StatisticsRetention.MONTH -> StatisticsRange.MONTH
    StatisticsRetention.MONTHS_3 -> StatisticsRange.MONTHS_3
    StatisticsRetention.FOREVER -> StatisticsRange.FOREVER
}

private const val DAY_MS = 24L * 60L * 60L * 1_000L
