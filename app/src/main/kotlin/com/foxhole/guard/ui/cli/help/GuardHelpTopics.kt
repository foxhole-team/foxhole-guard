package com.foxhole.guard.ui.cli.help

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.foxhole.guard.R

internal data class GuardHelpTopic(val id: String, @StringRes val title: Int,
    @DrawableRes val icon: Int, val blocks: List<HelpBlock>)

internal fun guardHelpTopics(canInstallRouter: Boolean = false, canInspectRouter: Boolean = true): List<GuardHelpTopic> = listOf(
    GuardHelpTopic("quick_actions", R.string.cli_help_start_title, R.drawable.lin_power, listOf(
        HelpBlock.Comparison(null, listOf(
            resourceRow(R.string.help_quick_0_area, R.string.help_quick_0_actions),
            resourceRow(R.string.help_quick_1_area, R.string.help_quick_1_actions),
            resourceRow(R.string.help_quick_2_area, R.string.help_quick_2_actions),
            resourceRow(R.string.help_quick_3_area, R.string.help_quick_3_actions))),
        HelpBlock.Notice(R.string.help_modules_compatible))),
    GuardHelpTopic("modes", R.string.cli_help_modes_title, R.drawable.lin_shield, listOf(
        HelpBlock.Items(listOf(R.string.help_connect_select, R.string.help_connect_start, R.string.help_connect_result), numbered = true),
        HelpBlock.Paragraph(R.string.cli_help_modes_body), HelpBlock.Notice(R.string.help_modules_compatible))),
    GuardHelpTopic("tor", R.string.cli_help_tor_title, R.drawable.lin_tor, listOf(
        HelpBlock.Paragraph(R.string.cli_help_tor_body), HelpBlock.Notice(R.string.help_tor_udp),
        HelpBlock.Notice(R.string.help_i2p_limits))),
    GuardHelpTopic("smart", R.string.cli_help_smart_title, R.drawable.lin_star, listOf(
        HelpBlock.Paragraph(R.string.help_smart_intro),
        HelpBlock.Comparison(R.string.help_smart_metrics, listOf(
            codeRow("T", R.string.help_smart_connection), codeRow("P", R.string.help_smart_ping), codeRow("L", R.string.help_smart_latency)), twoColumns = true, contentSizedDetails = true),
        HelpBlock.Comparison(R.string.help_smart_bands, listOf(
            codeRow("≤ 150 ms", R.string.help_smart_low, 150L), codeRow("151–300 ms", R.string.help_smart_medium, 300L),
            codeRow("301–600 ms", R.string.help_smart_high, 600L), codeRow("> 600 ms", R.string.help_smart_very_high, 601L)), twoColumns = true, labelFraction = .6f),
        HelpBlock.Paragraph(R.string.help_smart_result))),
    GuardHelpTopic("editor", R.string.cli_help_editor_title, R.drawable.lin_edit, listOf(
        HelpBlock.Paragraph(R.string.help_editor_detail), HelpBlock.Notice(R.string.help_profile_secret))),
    GuardHelpTopic("transfer", R.string.cli_help_transfer_title, R.drawable.lin_import, listOf(
        HelpBlock.Items(listOf(R.string.help_import_link, R.string.help_import_file, R.string.help_import_qr, R.string.help_import_manual)),
        HelpBlock.Notice(R.string.help_profile_secret),
        HelpBlock.Details("formats", R.string.help_details, listOf(HelpBlock.Paragraph(R.string.help_transfer_detail))))),
    GuardHelpTopic("routing", R.string.cli_help_routing_title, R.drawable.lin_link, listOf(
        HelpBlock.Paragraph(R.string.help_atomic_limits), HelpBlock.Paragraph(R.string.cli_help_modes_body),
        HelpBlock.Notice(R.string.cli_help_firewall_body), HelpBlock.Notice(R.string.help_i2p_transparent))),
    GuardHelpTopic("dns", R.string.cli_help_dns_title, R.drawable.lin_dns, listOf(
        HelpBlock.Paragraph(R.string.help_dns_intro), HelpBlock.Notice(R.string.help_packet_dns),
        HelpBlock.Details("dns-details", R.string.help_details, listOf(HelpBlock.Paragraph(R.string.cli_help_dns_body))))),
    GuardHelpTopic("net_rules", R.string.cli_help_net_rules_title, R.drawable.lin_globe,
        listOf(HelpBlock.Paragraph(R.string.help_network_rules))),
    GuardHelpTopic("lan_proxy", R.string.cli_help_lan_proxy_title, R.drawable.lin_device, listOf(
        HelpBlock.Paragraph(R.string.cli_help_lan_proxy_body), HelpBlock.Notice(R.string.help_lan_packet))),
    GuardHelpTopic("logs", R.string.cli_help_logs_title, R.drawable.lin_journal, listOf(HelpBlock.Paragraph(R.string.cli_help_logs_body))),
    GuardHelpTopic("stats", R.string.cli_help_stats_title, R.drawable.lin_stats, listOf(HelpBlock.Paragraph(R.string.cli_help_stats_body))),
    GuardHelpTopic("firewall", R.string.cli_help_firewall_title, R.drawable.lin_fire, listOf(HelpBlock.Paragraph(R.string.cli_help_firewall_body))),
    GuardHelpTopic("security", R.string.cli_help_security_title, R.drawable.lin_lock, listOf(HelpBlock.Paragraph(R.string.cli_help_security_body))),
    GuardHelpTopic("sentinel", R.string.cli_help_sentinel_title, R.drawable.lin_incognito, listOf(
        HelpBlock.Paragraph(R.string.help_sentinel_intro), HelpBlock.Notice(R.string.help_sentinel_results),
        HelpBlock.Details("sentinel-checked", R.string.help_sentinel_checked_title, listOf(HelpBlock.Paragraph(R.string.help_sentinel_checked_detail))),
        HelpBlock.Details("sentinel-run", R.string.help_sentinel_howto_title, listOf(HelpBlock.Items(listOf(
            R.string.help_sentinel_choose, R.string.help_sentinel_run_actions, R.string.help_sentinel_open_report), numbered = true))),
        HelpBlock.Details("sentinel-results", R.string.help_sentinel_results_title, listOf(HelpBlock.Paragraph(R.string.help_sentinel_assessment))),
        HelpBlock.Details("sentinel-monitoring", R.string.help_sentinel_monitoring_title, listOf(HelpBlock.Paragraph(R.string.help_sentinel_monitoring_detail))),
        HelpBlock.Details("sentinel-privacy", R.string.help_sentinel_privacy_title, listOf(
            HelpBlock.Paragraph(R.string.help_sentinel_privacy_detail), HelpBlock.Paragraph(R.string.help_sentinel_network_detail))),
        HelpBlock.Details("sentinel-expert", R.string.help_sentinel_expert_title, listOf(HelpBlock.Paragraph(R.string.help_sentinel_expert_detail))))),
    GuardHelpTopic("webapps", R.string.cli_help_webapps_title, R.drawable.lin_webapps, listOf(HelpBlock.Paragraph(R.string.cli_help_webapps_body), HelpBlock.Notice(R.string.help_webapps_limits))),
    GuardHelpTopic("tile", R.string.cli_help_tile_title, R.drawable.lin_power, listOf(HelpBlock.Paragraph(R.string.cli_help_tile_body))),
    GuardHelpTopic("killswitch", R.string.cli_help_killswitch_title, R.drawable.lin_forbidden, listOf(
        HelpBlock.Paragraph(R.string.cli_help_killswitch_body),
        HelpBlock.Link(R.string.cli_help_killswitch_link, HelpDestination.ANDROID_VPN_SETTINGS))),
    GuardHelpTopic("updates", R.string.cli_help_updates_title, R.drawable.lin_update, listOf(
        HelpBlock.Items(listOf(R.string.help_updates_subscriptions, R.string.help_updates_db, R.string.help_updates_app)),
        HelpBlock.Details("db-details", R.string.help_details, listOf(HelpBlock.Paragraph(R.string.cli_help_updates_body))))),
    GuardHelpTopic("backup", R.string.cli_help_backup_title, R.drawable.lin_export, listOf(
        HelpBlock.Heading(R.string.help_backup_contents_title), HelpBlock.Paragraph(R.string.help_backup_contents),
        HelpBlock.Heading(R.string.help_backup_local_title), HelpBlock.Paragraph(R.string.help_backup_local), HelpBlock.Notice(R.string.help_backup_actions))),
    GuardHelpTopic("router", R.string.help_router_title, R.drawable.lin_router, routerHelpBlocks(canInstallRouter, canInspectRouter)),
)

internal fun guardHelpTopic(id: String, canInstallRouter: Boolean = false, canInspectRouter: Boolean = true): GuardHelpTopic =
    guardHelpTopics(canInstallRouter, canInspectRouter).first { it.id == id }

internal fun guardHelpTopicForResource(@StringRes resource: Int, canInstallRouter: Boolean = false, canInspectRouter: Boolean = true): GuardHelpTopic? {
    val id = when (resource) {
        R.string.cli_help_start_body, R.string.cli_help_start_details -> "quick_actions"
        R.string.cli_help_modes_body -> "modes"
        R.string.cli_help_tor_body -> "tor"
        R.string.cli_help_smart_body -> "smart"
        R.string.cli_help_editor_body -> "editor"
        R.string.cli_help_transfer_body -> "transfer"
        R.string.cli_help_routing_body -> "routing"
        R.string.cli_help_dns_body -> "dns"
        R.string.cli_help_net_rules_body -> "net_rules"
        R.string.cli_help_lan_proxy_body -> "lan_proxy"
        R.string.cli_help_logs_body -> "logs"
        R.string.cli_help_stats_body -> "stats"
        R.string.cli_help_firewall_body -> "firewall"
        R.string.cli_help_security_body -> "security"
        R.string.cli_help_sentinel_body -> "sentinel"
        R.string.cli_help_webapps_body -> "webapps"
        R.string.cli_help_tile_body -> "tile"
        R.string.cli_help_killswitch_body -> "killswitch"
        R.string.cli_help_updates_body -> "updates"
        R.string.cli_help_backup_body -> "backup"
        R.string.guard_router_router_help_setup_pair -> "router"
        else -> return null
    }
    return guardHelpTopic(id, canInstallRouter, canInspectRouter)
}

internal fun guardRouterContextBlocks(@StringRes resource: Int, canInstallRouter: Boolean = false, canInspectRouter: Boolean = true): List<HelpBlock>? = when (resource) {
    R.string.guard_router_router_help_setup_pair -> routerHelpBlocks(canInstallRouter, canInspectRouter)
    R.string.guard_router_router_help_profile -> listOf(HelpBlock.Paragraph(R.string.help_router_transfer), HelpBlock.Notice(R.string.help_router_profile_secret))
    R.string.guard_router_router_help_home -> listOf(HelpBlock.Notice(R.string.help_router_home))
    R.string.guard_router_router_help_public_ip, R.string.guard_router_router_review_remote_help,
    R.string.guard_router_router_control_clients_help -> listOf(HelpBlock.Paragraph(R.string.help_router_reverse), HelpBlock.Notice(R.string.help_router_public_ip))
    else -> null
}

private fun routerHelpBlocks(canInstallRouter: Boolean, canInspectRouter: Boolean = true): List<HelpBlock> = listOf(
    HelpBlock.Heading(R.string.help_router_preparation, R.drawable.lin_router),
    HelpBlock.Items(listOf(R.string.help_router_wifi,
        when { canInstallRouter -> R.string.help_router_install_available; canInspectRouter -> R.string.help_router_install_unavailable; else -> R.string.help_router_install_manual }), numbered = true, links = routerInstallationLinks()),
    HelpBlock.Heading(R.string.help_router_connection, R.drawable.lin_link),
    HelpBlock.Items(listOf(R.string.help_router_pin, R.string.help_router_pair), numbered = true, start = 3),
    HelpBlock.Heading(R.string.help_router_profiles, R.drawable.lin_profiles),
    HelpBlock.Items(listOf(R.string.help_router_transfer), numbered = true, start = 5),
    HelpBlock.Paragraph(R.string.help_router_protocols),
    HelpBlock.Notice(R.string.help_router_footer),
    HelpBlock.Details("router-details", R.string.help_details, listOf(
        HelpBlock.Paragraph(R.string.help_router_reverse), HelpBlock.Notice(R.string.help_router_profile_secret),
        HelpBlock.Notice(R.string.help_router_public_ip), HelpBlock.Notice(R.string.help_router_home),
        HelpBlock.Notice(R.string.help_router_trust))),
)

private fun resourceRow(label: Int, detail: Int): HelpRow = HelpRow(HelpText.Resource(label), HelpText.Resource(detail))
private fun codeRow(label: String, detail: Int, latencyMs: Long? = null): HelpRow =
    HelpRow(HelpText.Literal(label), HelpText.Resource(detail), code = true, latencyMs = latencyMs)

private fun routerInstallationLinks(): Map<Int, HelpInlineLink> = listOf(
    R.string.help_router_install_available, R.string.help_router_install_unavailable, R.string.help_router_install_manual,
).associateWith { HelpInlineLink(R.string.help_router_install_link, "https://github.com/foxhole-team/foxhole-openwrt-client") }
