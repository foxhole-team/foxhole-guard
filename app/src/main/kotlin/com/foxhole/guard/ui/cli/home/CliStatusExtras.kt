package com.foxhole.guard.ui.cli.home

import androidx.compose.runtime.Immutable
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.guard.ui.HomeViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Immutable
internal data class CliStatusExtras(
    val journal: List<DiagnosticEntry> = emptyList(),
    val anomalies: List<AnomalyEvent> = emptyList(),
)

private const val ANOMALY_READ_TIMEOUT_MS = 1_500L

internal suspend fun HomeViewModel.cliStatusExtras(): CliStatusExtras =
    CliStatusExtras(
        journal = container.diagnosticsLogger.entries.value,
        anomalies = withTimeoutOrNull(ANOMALY_READ_TIMEOUT_MS) {
            container.anomalyRepository.recentEvents.first()
        }.orEmpty(),
    )
