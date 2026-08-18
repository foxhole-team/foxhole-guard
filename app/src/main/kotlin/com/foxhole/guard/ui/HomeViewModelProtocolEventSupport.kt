package com.foxhole.guard.ui

import com.foxhole.core.model.ProtocolMetricEvent
import com.foxhole.core.model.ProtocolMetricEventKind

internal suspend fun HomeViewModel.recordProtocolMetricEventInternal(
    profileId: Long,
    optionId: String?,
    kind: ProtocolMetricEventKind,
    latencyMs: Long?,
    protocol: String = "",
    reasonCode: String? = null,
) {
    container.anomalyRepository.recordProtocolMetricEvent(
        ProtocolMetricEvent(
            timestampMs = System.currentTimeMillis(),
            profileId = profileId,
            optionId = optionId,
            protocol = protocol,
            kind = kind,
            latencyMs = latencyMs,
            reasonCode = reasonCode,
        ),
    )
}
