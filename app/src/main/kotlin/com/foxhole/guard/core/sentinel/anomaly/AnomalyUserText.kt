package com.foxhole.guard.core.sentinel.anomaly

import android.content.Context
import android.text.format.Formatter
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalyType
import com.foxhole.guard.R

/** One localized explanation shared by notifications, the security journal and extended STATUS. */
internal fun AnomalyEvent.userFacingMessage(context: Context): String {
    val appLabel = packageName?.let(context::labelForPackageName) ?: packageName
    val subject = appLabel ?: context.getString(R.string.statistics_anomaly_whole_tunnel)
    return when (type) {
        AnomalyType.APP_UPLOAD_SPIKE ->
            context.getString(
                R.string.anomaly_reason_app_upload,
                subject,
                evidence["tx_per_min"].asBytesText(context),
                evidence["upload_ratio"].asPercentText(context),
            )
        AnomalyType.APP_BACKGROUND_TRAFFIC ->
            context.getString(
                R.string.anomaly_reason_background,
                subject,
                evidence["app_share"].asPercentText(context),
                evidence["upload_ratio"].asPercentText(context),
            )
        AnomalyType.TOTAL_TRAFFIC_SPIKE ->
            context.getString(
                R.string.anomaly_reason_total_spike,
                evidence["bytes_per_min"].asBytesText(context),
                evidence["robust_z"].orEmpty().ifBlank { context.getString(R.string.statistics_no_data) },
            )
        AnomalyType.NEW_DESTINATION_COUNTRY ->
            context.getString(
                R.string.anomaly_reason_new_country,
                evidence["country"].orEmpty().ifBlank { context.getString(R.string.statistics_no_data) },
                evidence["traffic_share"].asPercentText(context),
            )
        AnomalyType.DNS_BLOCK_RATIO_SPIKE ->
            context.getString(
                R.string.anomaly_reason_dns_blocks,
                evidence["blocked_dns"].orEmpty().ifBlank { "0" },
                evidence["allowed_dns"].orEmpty().ifBlank { "0" },
                evidence["blocked_ratio"].asPercentText(context),
            )
        AnomalyType.RECONNECT_STORM ->
            context.getString(
                R.string.anomaly_reason_reconnects,
                evidence["reconnects"].orEmpty().ifBlank { "0" },
            )
        AnomalyType.LATENCY_SHIFT ->
            context.getString(
                R.string.anomaly_reason_latency,
                evidence["latency_ms"].orEmpty().ifBlank { "0" },
                evidence["robust_z"].orEmpty().ifBlank { context.getString(R.string.statistics_no_data) },
            )
        // Legacy compatibility only: the retired destination-country heuristic wrote
        // `route_share`; do not infer the current Tor/I2P state from these persisted events.
        AnomalyType.TOR_OR_I2P_ROUTE_MISMATCH ->
            context.getString(
                R.string.anomaly_reason_privacy_route,
                evidence["route_share"].asPercentText(context),
            )
        AnomalyType.DORMANT_APP_NETWORK_ACTIVITY ->
            context.getString(
                R.string.anomaly_reason_dormant_app,
                subject,
                evidence["idle_days"].orEmpty().ifBlank { "0" },
                evidence["bytes"].asBytesText(context),
            )
        AnomalyType.KNOWN_THREAT_DESTINATION ->
            context.getString(
                R.string.anomaly_reason_known_threat_destination,
                subject,
                evidence["indicator"].orEmpty().ifBlank { context.getString(R.string.statistics_no_data) },
            )
    }
}

private fun String?.asPercentText(context: Context): String =
    this
        ?.toFloatOrNull()
        ?.let { value -> "${(value.coerceIn(0f, 1f) * 100f).toInt()}%" }
        ?: context.getString(R.string.statistics_no_data)

private fun String?.asBytesText(context: Context): String =
    this
        ?.toLongOrNull()
        ?.let { value -> Formatter.formatShortFileSize(context, value) }
        ?: context.getString(R.string.statistics_no_data)

private fun Context.labelForPackageName(packageName: String): String? =
    runCatching {
        packageManager
            .getApplicationInfo(packageName, 0)
            .loadLabel(packageManager)
            .toString()
            .takeIf(String::isNotBlank)
    }.getOrNull()
