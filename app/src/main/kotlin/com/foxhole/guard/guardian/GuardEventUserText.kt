package com.foxhole.guard.guardian

import android.content.Context
import androidx.annotation.StringRes
import com.foxhole.guard.R

/** Localized security-journal text; sealed payload internals never leak into the user-facing row. */
internal fun GuardJournalEntry.userFacingMessage(context: Context): String {
    val event = event ?: return context.getString(R.string.guard_event_sealed, record.seq)
    val facts = mutableListOf(context.getString(event.type.userLabelRes()))
    event.packageName?.takeIf(String::isNotBlank)?.let(facts::add)
    event.service?.takeIf(String::isNotBlank)?.let { service ->
        facts += context.getString(R.string.guard_event_service_name, service)
    }
    event.localizedDetail(context)?.let(facts::add)
    event.riskLevel?.takeIf { risk -> !risk.equals("low", ignoreCase = true) }?.let { risk ->
        facts += context.getString(R.string.guard_event_risk, context.localizedRisk(risk))
    }
    event.gapMs?.let { gap ->
        facts += context.getString(R.string.guard_event_gap, gap.coerceAtLeast(0L) / 1000L)
    }
    event.attempt?.let { attempt ->
        facts += context.getString(R.string.guard_event_attempt, attempt)
    }
    return facts.joinToString(" · ")
}

@StringRes
internal fun GuardEventType.userLabelRes(): Int =
    when (this) {
        GuardEventType.GENESIS -> R.string.guard_event_genesis
        GuardEventType.PACKAGE_ADDED -> R.string.guard_event_package_added
        GuardEventType.PACKAGE_REMOVED -> R.string.guard_event_package_removed
        GuardEventType.PACKAGE_REPLACED -> R.string.guard_event_package_replaced
        GuardEventType.BOOT_COMPLETED -> R.string.guard_event_boot_completed
        GuardEventType.MY_PACKAGE_REPLACED -> R.string.guard_event_foxhole_updated
        GuardEventType.SERVICE_STARTED -> R.string.guard_event_service_started
        GuardEventType.SERVICE_STOPPED -> R.string.guard_event_service_stopped
        GuardEventType.SERVICE_DESTROYED -> R.string.guard_event_service_destroyed
        GuardEventType.TASK_REMOVED -> R.string.guard_event_task_removed
        GuardEventType.HEARTBEAT -> R.string.guard_event_heartbeat
        GuardEventType.BLACKOUT_SUSPECTED -> R.string.guard_event_blackout
        GuardEventType.CLOCK_ANOMALY -> R.string.guard_event_clock_anomaly
        GuardEventType.SECURITY_SETTING_CHANGED -> R.string.guard_event_security_setting_changed
        GuardEventType.UNLOCK_OK -> R.string.guard_event_unlock_ok
        GuardEventType.UNLOCK_FAILED -> R.string.guard_event_unlock_failed
        GuardEventType.GUARD_ENABLED -> R.string.guard_event_guard_enabled
        GuardEventType.GUARD_DISABLE_REQUESTED -> R.string.guard_event_guard_disable_requested
        GuardEventType.GUARD_DISABLED -> R.string.guard_event_guard_disabled
        GuardEventType.STATS_PAUSED -> R.string.guard_event_stats_paused
        GuardEventType.STATS_RESUMED -> R.string.guard_event_stats_resumed
        GuardEventType.MONITORING_TOGGLE_ATTEMPT -> R.string.guard_event_monitoring_toggle_attempt
        GuardEventType.HISTORY_CLEARED -> R.string.guard_event_history_cleared
        GuardEventType.VPN_RESTORED_FROM_SNAPSHOT -> R.string.guard_event_vpn_restored
        GuardEventType.VPN_RESTORE_DEFERRED -> R.string.guard_event_vpn_restore_deferred
        GuardEventType.CORE_FLOW_BLOCKED -> R.string.guard_event_core_flow_blocked
        GuardEventType.CORE_DNS_BLOCKED -> R.string.guard_event_core_dns_blocked
        GuardEventType.CORE_CONFIG_APPLIED -> R.string.guard_event_core_config_applied
        GuardEventType.CORE_CONFIRMATION_REQUIRED -> R.string.guard_event_core_confirmation_required
        GuardEventType.CORE_CONFIRMATION_EXPIRED -> R.string.guard_event_core_confirmation_expired
        GuardEventType.CORE_OUTBOUND_UNAVAILABLE -> R.string.guard_event_core_outbound_unavailable
        GuardEventType.CORE_OUTBOUND_RESTORED -> R.string.guard_event_core_outbound_restored
        GuardEventType.CORE_FLOWS_REVOKED -> R.string.guard_event_core_flows_revoked
        GuardEventType.CORE_EVENT_GAP -> R.string.guard_event_core_event_gap
    }

private fun GuardEvent.localizedDetail(context: Context): String? {
    val value = detail?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (type == GuardEventType.GENESIS) return null
    if (value == RECONCILED_DETAIL_VALUE) return context.getString(R.string.guard_event_reconciled)
    if (type == GuardEventType.HISTORY_CLEARED) {
        return context.getString(R.string.guard_event_scope, context.localizedTechnicalValue(value))
    }
    if (type == GuardEventType.SECURITY_SETTING_CHANGED) {
        val split = value.split(':', limit = 2)
        return if (split.size == 2) {
            context.getString(
                R.string.guard_event_setting,
                split[0].replace('_', ' '),
                split[1].replace("->", " → "),
            )
        } else {
            null
        }
    }
    val fields = DETAIL_FIELD.findAll(value).mapNotNull { match ->
        val key = match.groupValues[1]
        if (key == "token" || key == "bootCount") return@mapNotNull null
        val label = context.guardDetailLabel(key) ?: return@mapNotNull null
        val raw = match.groupValues[2]
        val localizedValue =
            if (key == "message") {
                context.getString(R.string.guard_event_runtime_error)
            } else {
                context.localizedTechnicalValue(raw)
            }
        context.getString(R.string.guard_event_detail_field, label, localizedValue)
    }.take(MAX_DETAIL_FIELDS).toList()
    return fields.joinToString(", ").takeIf(String::isNotEmpty)
}

private fun Context.guardDetailLabel(key: String): String? =
    when (key) {
        "reason" -> getString(R.string.guard_event_field_reason)
        "transport" -> getString(R.string.guard_event_field_transport)
        "count" -> getString(R.string.guard_event_field_count)
        "previous" -> getString(R.string.guard_event_field_previous)
        "revision" -> getString(R.string.guard_event_field_revision)
        "interruption" -> getString(R.string.guard_event_field_interruption)
        "id" -> getString(R.string.guard_event_field_id)
        "kind" -> getString(R.string.guard_event_field_kind)
        "attempts" -> getString(R.string.guard_event_field_attempts)
        "target" -> getString(R.string.guard_event_field_target)
        "scope" -> getString(R.string.guard_event_field_scope)
        "audit_dropped" -> getString(R.string.guard_event_field_audit_dropped)
        "traffic_dropped" -> getString(R.string.guard_event_field_traffic_dropped)
        "message" -> getString(R.string.guard_event_field_reason)
        else -> null
    }

private fun Context.localizedRisk(risk: String): String =
    when (risk.lowercase()) {
        "low" -> getString(R.string.guard_risk_low)
        "medium" -> getString(R.string.guard_risk_medium)
        "high" -> getString(R.string.guard_risk_high)
        else -> getString(R.string.cli_common_unknown)
    }

private fun Context.localizedTechnicalValue(value: String): String =
    when (value.lowercase()) {
        "unknown", "none", "null" -> getString(R.string.cli_common_unknown)
        "true" -> getString(R.string.guard_value_yes)
        "false" -> getString(R.string.guard_value_no)
        else -> value.replace('_', ' ').take(MAX_DETAIL_VALUE_LENGTH)
    }

private val DETAIL_FIELD = Regex("([A-Za-z_]+)=([^\\s]+)")
private const val RECONCILED_DETAIL_VALUE = "reconciled"
private const val MAX_DETAIL_FIELDS = 4
private const val MAX_DETAIL_VALUE_LENGTH = 80
