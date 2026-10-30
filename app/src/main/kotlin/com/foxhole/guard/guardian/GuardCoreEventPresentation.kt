package com.foxhole.guard.guardian

import androidx.annotation.StringRes
import com.foxhole.core.model.DiagnosticSanitizer
import com.foxhole.guard.R

internal typealias JournalText = (Int, List<Any>) -> String

internal fun GuardEvent.coreUserFacingMessage(text: JournalText, forExport: Boolean = false): String? {
    if (type !in CORE_EVENT_TYPES) return null
    val fields = parseCoreJournalFields(detail)
    val title = coreTitleRes(fields["reason"])
    val facts = mutableListOf(text(title, emptyList()))
    packageName?.takeIf(String::isNotBlank)?.let { value ->
        facts += text(R.string.journal_network_app, listOf(if (forExport) text(R.string.journal_redacted, emptyList()) else safeJournalPackage(value)))
    }
    facts += coreFacts(fields, text, forExport)
    return facts.joinToString(" · ")
}

private fun GuardEvent.coreTitleRes(reason: String?): Int {
    if (type != GuardEventType.CORE_FLOW_BLOCKED) return type.userLabelRes()
    return when (reason) {
        "continuity_held" -> R.string.journal_waiting_title
        "tunnel_peer_unresponsive" -> R.string.journal_peer_title
        "tunnel_socket_unavailable" -> R.string.journal_socket_wait_title
        "flow_backlog_exceeded" -> R.string.journal_stalled_title
        else -> if (reason in BLOCK_REASONS) type.userLabelRes() else R.string.journal_unknown_flow_title
    }
}

private fun GuardEvent.coreFacts(fields: Map<String, String>, text: JournalText, forExport: Boolean): List<String> = when (type) {
    GuardEventType.CORE_FLOW_BLOCKED, GuardEventType.CORE_DNS_BLOCKED -> blockFacts(fields, type, text)
    GuardEventType.CORE_CONFIG_APPLIED -> fieldFacts(
        fields,
        text,
        listOf("previous" to R.string.journal_previous, "revision" to R.string.journal_revision)
    )
    GuardEventType.CORE_CONFIRMATION_REQUIRED, GuardEventType.CORE_CONFIRMATION_EXPIRED -> confirmationFacts(
        fields,
        type,
        text
    )
    GuardEventType.CORE_OUTBOUND_UNAVAILABLE, GuardEventType.CORE_OUTBOUND_RESTORED -> outboundFacts(
        fields,
        type,
        text,
        forExport
    )
    GuardEventType.CORE_FLOWS_REVOKED -> revokedFacts(fields, text, forExport)
    GuardEventType.CORE_EVENT_GAP -> fieldFacts(fields, text, listOf("audit_dropped" to R.string.journal_audit_dropped, "traffic_dropped" to R.string.journal_traffic_dropped)) + text(R.string.journal_gap, emptyList())
    else -> emptyList()
}

internal fun parseCoreJournalFields(detail: String?): Map<String, String> {
    if (detail.orEmpty().length > MAX_CORE_DETAIL_LENGTH) return emptyMap()
    val bounded = detail.orEmpty()
    val fields = linkedMapOf<String, String>()
    val duplicates = mutableSetOf<String>()
    val seen = mutableSetOf<String>()
    CORE_FIELD.findAll(bounded).take(MAX_CORE_FIELDS).forEach { match ->
        val key = match.groupValues[1]
        if (key in CORE_ALLOWED_FIELDS) {
            if (!seen.add(key)) duplicates += key
            if (match.groupValues[2].length <= MAX_CORE_VALUE_LENGTH) {
                fields[key] = match.groupValues[2]
            } else {
                duplicates += key
            }
        }
    }
    duplicates.forEach(fields::remove)
    return fields
}

internal fun safeJournalValue(value: String): String =
    DiagnosticSanitizer.sanitizeForExport(
        DiagnosticSanitizer.normalizeForStorage(value.take(MAX_CORE_VALUE_LENGTH)),
    ).replace('·', ' ').replace('•', ' ').take(MAX_CORE_VALUE_LENGTH)

private fun blockFacts(fields: Map<String, String>, type: GuardEventType, text: JournalText): List<String> {
    val code = fields["reason"]
    val reason = BLOCK_REASONS[code]
    val outcome = when (code) {
        "continuity_held" -> R.string.journal_held
        "tunnel_peer_unresponsive", "tunnel_socket_unavailable" -> R.string.journal_retrying
        "flow_backlog_exceeded" -> R.string.journal_ended
        else -> if (reason == null) R.string.journal_unknown_outcome else R.string.journal_refused
    }
    return buildList {
        addAll(reasonFacts(code, reason, text))
        add(text(R.string.journal_outcome, listOf(text(outcome, emptyList()))))
        if (type == GuardEventType.CORE_FLOW_BLOCKED) {
            fields["transport"]?.let { raw ->
                val transport = raw.uppercase().takeIf { it == "TCP" || it == "UDP" } ?: text(R.string.journal_unknown, emptyList())
                add(text(R.string.journal_transport, listOf(transport)))
            }
        } else {
            val category = CATEGORY_RES[fields["category"]?.lowercase()] ?: R.string.journal_category_none
            add(text(R.string.journal_category, listOf(text(category, emptyList()))))
        }
        addAll(fieldFacts(fields, text, listOf("count" to R.string.journal_count)))
    }
}

private fun reasonFacts(code: String?, reason: CoreReason?, text: JournalText): List<String> = buildList {
    add(text(R.string.journal_reason, listOf(text(reason?.reasonRes ?: R.string.journal_unknown_reason, emptyList()))))
    add(text(R.string.journal_action, listOf(text(reason?.actionRes ?: R.string.journal_unknown_action, emptyList()))))
    if (reason == null && code != null) add(text(R.string.journal_diagnostic_code, listOf(safeJournalValue(code))))
}

private fun confirmationFacts(fields: Map<String, String>, type: GuardEventType, text: JournalText): List<String> = buildList {
    add(
        text(
            if (type == GuardEventType.CORE_CONFIRMATION_REQUIRED) R.string.journal_confirmation_required else R.string.journal_confirmation_expired,
            emptyList()
        )
    )
    add(text(R.string.journal_confirmation_still_held, emptyList()))
    if (type == GuardEventType.CORE_CONFIRMATION_EXPIRED) {
        add(text(R.string.journal_confirmation_tunnel_up, emptyList()))
        add(text(R.string.journal_confirmation_still_valid, emptyList()))
    }
    val interruption = INTERRUPTION_RES[fields["interruption"]]
    add(text(R.string.journal_reason, listOf(text(interruption ?: R.string.journal_unknown_reason, emptyList()))))
    if (interruption == null) {
        fields["interruption"]?.let {
            add(
                text(R.string.journal_diagnostic_code, listOf(safeJournalValue(it)))
            )
        }
    }
    add(text(R.string.journal_action, listOf(text(R.string.journal_confirmation_action, emptyList()))))
}

private fun outboundFacts(fields: Map<String, String>, type: GuardEventType, text: JournalText, forExport: Boolean): List<String> = buildList {
    if (type == GuardEventType.CORE_OUTBOUND_UNAVAILABLE) {
        addAll(reasonFacts(fields["reason"], UNAVAILABLE_REASONS[fields["reason"]], text))
        add(text(R.string.journal_outbound_missing, emptyList()))
    } else {
        add(text(R.string.journal_restored, emptyList()))
    }
    fields["id"]?.let {
        add(
            text(
                R.string.journal_id,
                listOf(if (forExport) text(R.string.journal_redacted, emptyList()) else safeJournalValue(it))
            )
        )
    }
    fields["kind"]?.let { add(text(R.string.journal_kind, listOf(safeJournalValue(it)))) }
    addAll(fieldFacts(fields, text, listOf("attempts" to R.string.journal_attempts)))
}

private fun revokedFacts(fields: Map<String, String>, text: JournalText, forExport: Boolean): List<String> = buildList {
    val target = TARGET_RES[fields["target"]] ?: R.string.journal_unknown
    add(text(R.string.journal_target, listOf(text(target, emptyList()))))
    fields["scope"]?.takeUnless { it == "none" || it == "null" }?.let { value ->
        add(
            text(
                R.string.journal_scope,
                listOf(if (forExport) text(R.string.journal_redacted, emptyList()) else safeJournalValue(value))
            )
        )
    }
    addAll(fieldFacts(fields, text, listOf("count" to R.string.journal_revoked_count)))
    add(text(R.string.journal_revoked, emptyList()))
}

private fun fieldFacts(fields: Map<String, String>, text: JournalText, labels: List<Pair<String, Int>>): List<String> =
    labels.mapNotNull { (key, label) ->
        fields[key]?.takeIf { it.toULongOrNull() != null }?.let { text(label, listOf(it)) }
    }

private data class CoreReason(@StringRes val reasonRes: Int, @StringRes val actionRes: Int)

private val BLOCK_REASONS = mapOf(
    "kill_switch" to CoreReason(R.string.journal_block_kill_switch, R.string.journal_block_kill_switch_action),
    "policy" to CoreReason(R.string.journal_block_policy, R.string.journal_block_policy_action),
    "unattributed" to CoreReason(R.string.journal_block_unattributed, R.string.journal_block_unattributed_action),
    "flow_limit" to CoreReason(R.string.journal_block_flow_limit, R.string.journal_block_flow_limit_action),
    "dns_blocklist" to CoreReason(R.string.journal_block_dns_blocklist, R.string.journal_block_dns_blocklist_action),
    "dns_overlay_gate" to CoreReason(R.string.journal_block_dns_overlay_gate, R.string.journal_block_dns_overlay_gate_action),
    "udp_unsupported" to CoreReason(R.string.journal_block_udp_unsupported, R.string.journal_block_udp_unsupported_action),
    "continuity_held" to CoreReason(R.string.journal_block_continuity_held, R.string.journal_block_continuity_held_action),
    "tunnel_address_mismatch" to CoreReason(R.string.journal_block_tunnel_address_mismatch, R.string.journal_block_tunnel_address_mismatch_action),
    "tunnel_family_unmapped" to CoreReason(R.string.journal_block_tunnel_family_unmapped, R.string.journal_block_tunnel_family_unmapped_action),
    "tunnel_fake_ip_unroutable" to CoreReason(R.string.journal_block_tunnel_fake_ip_unroutable, R.string.journal_block_tunnel_fake_ip_unroutable_action),
    "tunnel_socket_unavailable" to CoreReason(R.string.journal_block_tunnel_socket_unavailable, R.string.journal_block_tunnel_socket_unavailable_action),
    "flow_backlog_exceeded" to CoreReason(R.string.journal_block_flow_backlog_exceeded, R.string.journal_block_flow_backlog_exceeded_action),
    "lane_unavailable" to CoreReason(R.string.journal_block_lane_unavailable, R.string.journal_block_lane_unavailable_action),
    "tunnel_routing_loop" to CoreReason(R.string.journal_block_tunnel_routing_loop, R.string.journal_block_tunnel_routing_loop_action),
    "tunnel_peer_unresponsive" to CoreReason(R.string.journal_block_tunnel_peer_unresponsive, R.string.journal_block_tunnel_peer_unresponsive_action),
    "dns_encrypted_bypass" to CoreReason(R.string.journal_block_dns_encrypted_bypass, R.string.journal_block_dns_encrypted_bypass_action),
)
private val UNAVAILABLE_REASONS = mapOf(
    "timeout" to CoreReason(R.string.journal_unavailable_timeout, R.string.journal_unavailable_timeout_action),
    "permissions" to CoreReason(R.string.journal_unavailable_permissions, R.string.journal_unavailable_permissions_action),
    "network" to CoreReason(R.string.journal_unavailable_network, R.string.journal_unavailable_network_action),
    "config" to CoreReason(R.string.journal_unavailable_config, R.string.journal_unavailable_config_action),
    "unsupported" to CoreReason(R.string.journal_unavailable_unsupported, R.string.journal_unavailable_unsupported_action),
    "disabled" to CoreReason(R.string.journal_unavailable_disabled, R.string.journal_unavailable_disabled_action),
    "internal" to CoreReason(R.string.journal_unavailable_internal, R.string.journal_unavailable_internal_action),
)
private val CATEGORY_RES = mapOf(
    "malicious" to R.string.journal_category_malicious,
    "telemetry" to R.string.journal_category_telemetry,
    "trackers" to R.string.journal_category_trackers,
    "ads" to R.string.journal_category_ads,
)
private val INTERRUPTION_RES = mapOf(
    "proxy_session" to R.string.journal_interruption_proxy_session,
    "selector_member" to R.string.journal_interruption_selector_member,
    "network_switch" to R.string.journal_interruption_network_switch,
    "vpn_failure" to R.string.journal_interruption_vpn_failure,
)
private val TARGET_RES = mapOf(
    "all" to R.string.journal_target_all,
    "lane" to R.string.journal_target_lane,
    "uid" to R.string.journal_target_uid,
    "package" to R.string.journal_target_package,
    "outbound" to R.string.journal_target_outbound,
    "flow" to R.string.journal_target_flow,
)
private val CORE_EVENT_TYPES = setOf(
    GuardEventType.CORE_FLOW_BLOCKED, GuardEventType.CORE_DNS_BLOCKED, GuardEventType.CORE_CONFIG_APPLIED,
    GuardEventType.CORE_CONFIRMATION_REQUIRED, GuardEventType.CORE_CONFIRMATION_EXPIRED,
    GuardEventType.CORE_OUTBOUND_UNAVAILABLE, GuardEventType.CORE_OUTBOUND_RESTORED,
    GuardEventType.CORE_FLOWS_REVOKED, GuardEventType.CORE_EVENT_GAP,
)
private val CORE_ALLOWED_FIELDS =
    setOf("reason", "transport", "category", "count", "previous", "revision", "interruption", "id", "kind", "attempts", "target", "scope", "audit_dropped", "traffic_dropped", "dropped", "fullRepairRequired")
private val CORE_FIELD = Regex("(?:^|\\s)([A-Za-z_]{1,32})=([^\\s]+)")
private const val MAX_CORE_DETAIL_LENGTH = 2048
private const val MAX_CORE_VALUE_LENGTH = 128
private const val MAX_CORE_FIELDS = 32

private fun safeJournalPackage(value: String): String =
    value.takeIf { it.length <= MAX_CORE_VALUE_LENGTH && CORE_PACKAGE.matches(it) } ?: safeJournalValue(value)

private val CORE_PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*")

internal fun GuardEvent.packageGapUserFacingMessage(text: JournalText): String? {
    if (type != GuardEventType.PACKAGE_EVENT_GAP) return null
    val fields = parseCoreJournalFields(detail?.replace(';', ' '))
    return buildList {
        add(text(R.string.journal_package_gap_title, emptyList()))
        fields["dropped"]?.takeIf { it.toULongOrNull() != null }?.let {
            add(
                text(R.string.journal_package_gap_dropped, listOf(it))
            )
        }
        add(text(R.string.journal_package_gap_incomplete, emptyList()))
        if (fields["fullRepairRequired"] == "true") add(text(R.string.journal_package_gap_repair, emptyList()))
    }.joinToString(" · ")
}
