package com.foxhole.guard.ui

import com.foxhole.core.model.ProtocolMetricEvent
import com.foxhole.core.model.ProtocolMetricEventKind

/**
 * Mirrors every smart-profile memory write with a persisted protocol metric event, so profile
 * details can chart real latency/reliability history instead of the single last-value snapshot.
 * Persistence is gated (and pruned) inside the repository; failures never break the caller.
 */
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
