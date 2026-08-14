package com.foxhole.guard.ui.cli.home

import androidx.compose.runtime.Immutable
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.guard.ui.HomeViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The two journal sources the extended status quotes.
 *
 * Deliberately not a field of the home route state: the live diagnostics stream publishes a fresh
 * list per recorded line and the anomaly flow opens the database, so subscribing to either from the
 * home screen would pay for both continuously to answer one long press.
 */
@Immutable
internal data class CliStatusExtras(
    val journal: List<DiagnosticEntry> = emptyList(),
    val anomalies: List<AnomalyEvent> = emptyList(),
)

// The anomaly history is a database observation behind the unlock gate. A bounded wait keeps a
// long press from hanging the block forever if the key is not installed yet; the report simply
// arrives without that section.
private const val ANOMALY_READ_TIMEOUT_MS = 1_500L

/** Reads both journal tails on demand — nothing here is observed between presses. */
internal suspend fun HomeViewModel.cliStatusExtras(): CliStatusExtras =
    CliStatusExtras(
        journal = container.diagnosticsLogger.entries.value,
        anomalies = withTimeoutOrNull(ANOMALY_READ_TIMEOUT_MS) {
            container.anomalyRepository.recentEvents.first()
        }.orEmpty(),
    )
