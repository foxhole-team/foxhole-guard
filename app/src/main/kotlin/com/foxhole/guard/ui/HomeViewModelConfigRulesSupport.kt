package com.foxhole.guard.ui

import android.app.Application
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.RoutingPresetSource
import com.foxhole.core.model.RoutingRuleAction
import com.foxhole.guard.R
import com.foxhole.guard.userFacingErrorMessage
import kotlinx.coroutines.launch
import java.io.File

internal fun HomeViewModel.importPresetText(
    raw: String,
    source: RoutingPresetSource,
) {
    viewModelScope.launch {
        runCatching { container.routingRepository.importPresetDocument(raw, source) }
            .onSuccess { emitSuccess(getApplication<Application>().getString(R.string.routing_preset_imported)) }
            .onFailure {
                emitError(
                    getApplication<Application>().userFacingErrorMessage(
                        it,
                        R.string.routing_preset_import_failed,
                    ),
                )
            }
    }
}

internal suspend fun HomeViewModel.getResolvedConfig(
    profileId: Long,
    protocolOptionIdOverride: String? = null,
): String = container.profileRepository.getResolvedConfig(profileId, protocolOptionIdOverride)

internal suspend fun HomeViewModel.updateResolvedConfig(
    profileId: Long,
    editedJson: String,
    reconnectAfterSave: Boolean = false,
    protocolOptionIdOverride: String? = null,
): Boolean =
    runCatching {
        container.profileRepository.updateResolvedConfig(profileId, editedJson, protocolOptionIdOverride)
    }.onSuccess {
        val reconnected = reconnectProfileIfRequested(profileId, reconnectAfterSave)
        val message =
            if (reconnected) {
                getApplication<Application>().getString(R.string.profile_config_saved_reconnecting)
            } else if (controlUiState.value.activeProfile?.id == profileId &&
                controlUiState.value.connection.state in setOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.RECONNECTING)
            ) {
                getApplication<Application>().getString(R.string.reconnect_required)
            } else {
                getApplication<Application>().getString(R.string.profile_config_saved)
            }
        if (message == getApplication<Application>().getString(R.string.reconnect_required)) {
            emitInfo(message)
        } else {
            emitSuccess(message)
        }
    }.onFailure {
        emitError(
            getApplication<Application>().userFacingErrorMessage(
                it,
                R.string.profile_config_save_failed,
            ),
        )
    }.isSuccess

internal fun HomeViewModel.saveSiteRule(
    ruleId: Long?,
    domains: List<String>,
    action: RoutingRuleAction,
) {
    viewModelScope.launch {
        runCatching {
            val normalizedTokens =
                domains
                    .mapNotNull(::normalizedSiteMaskToken)
                    .distinct()
            require(normalizedTokens.isNotEmpty()) {
                getApplication<Application>().getString(R.string.site_exception_validation_error)
            }
            require(normalizedTokens.all { siteMaskValidationErrorRes(it) == null }) {
                getApplication<Application>().getString(R.string.site_exception_invalid_error)
            }
            val normalizedDomains = normalizedTokens.filterNot { it.startsWith(SITE_CIDR_PREFIX) }
            val normalizedIpCidrs =
                normalizedTokens
                    .filter { it.startsWith(SITE_CIDR_PREFIX) }
                    .map { it.removePrefix(SITE_CIDR_PREFIX) }
            val presetId =
                controlUiState.value.activePreset?.id ?: container.routingRepository.createPreset(
                    name = getApplication<Application>().getString(R.string.local_rules_preset_name),
                    activate = true,
                )
            container.routingRepository.upsertRule(
                presetId = presetId,
                ruleId = ruleId,
                name = siteRuleName(action, normalizedTokens.firstOrNull()),
                enabled = true,
                order = controlUiState.value.activePreset?.rules?.firstOrNull { it.id == ruleId }?.order,
                action = action,
                matchDomains = normalizedDomains,
                matchIpCidrs = normalizedIpCidrs,
                matchPorts = emptyList(),
                matchProtocols = emptyList(),
                matchNetworks = emptyList(),
            )
        }.onSuccess {
            maybeReloadActiveRuntime()
            emitSuccess(getApplication<Application>().getString(R.string.routing_rule_saved))
        }.onFailure {
            emitError(
                getApplication<Application>().userFacingErrorMessage(
                    it,
                    R.string.routing_rule_save_failed,
                ),
            )
        }
    }
}

internal fun HomeViewModel.onSiteRuleMoved(
    ruleId: Long,
    action: RoutingRuleAction,
    ruleIdsInOrder: List<Long>,
) {
    viewModelScope.launch {
        runCatching {
            val rule = controlUiState.value.activePreset?.rules?.firstOrNull { it.id == ruleId }
                ?: error(getApplication<Application>().getString(R.string.routing_rule_save_failed))
            val firstToken = (rule.matchDomains + rule.matchIpCidrs.map { "$SITE_CIDR_PREFIX$it" }).firstOrNull()
            container.routingRepository.updateRuleActionAndOrder(
                ruleId = ruleId,
                name = siteRuleName(action, firstToken),
                action = action,
                ruleIdsInOrder = ruleIdsInOrder,
            )
        }.onSuccess {
            maybeReloadActiveRuntime()
        }.onFailure {
            emitError(
                getApplication<Application>().userFacingErrorMessage(
                    it,
                    R.string.routing_rule_save_failed,
                ),
            )
        }
    }
}

private const val SITE_CIDR_PREFIX = "cidr:"

private fun HomeViewModel.siteRuleName(
    action: RoutingRuleAction,
    token: String?,
): String {
    val prefix =
        when (action) {
            RoutingRuleAction.BLOCK -> "Foxhole blocked site"
            RoutingRuleAction.TOR -> "Foxhole tor site"
            RoutingRuleAction.PROXY,
            RoutingRuleAction.DIRECT,
            -> "Foxhole selected site"
        }
    return "$prefix: ${token ?: getApplication<Application>().getString(R.string.site_exception_default_name)}"
}

internal fun HomeViewModel.createDiagnosticsArchive(): File =
    container.diagnosticsLogger.createExportFile()

internal fun HomeViewModel.exportDiagnostics(file: File = createDiagnosticsArchive()): Intent {
    val uri =
        FileProvider.getUriForFile(
            getApplication(),
            "${getApplication<Application>().packageName}.fileprovider",
            file,
        )
    val subject = getApplication<Application>().getString(R.string.export_diagnostics_share_subject)
    return Intent(Intent.ACTION_SEND).apply {
        type = "application/gzip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, getApplication<Application>().getString(R.string.export_diagnostics_share_text))
        clipData = ClipData.newRawUri(subject, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
