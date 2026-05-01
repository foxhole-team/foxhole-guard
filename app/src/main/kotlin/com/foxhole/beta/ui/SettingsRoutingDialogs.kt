package com.foxhole.beta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.data.RoutingRepository
import com.foxhole.beta.core.model.ClashApiSettings
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.RoutingCatalog
import com.foxhole.beta.core.model.RoutingPreset
import com.foxhole.beta.core.model.RoutingPresetOverrideMode
import com.foxhole.beta.core.model.RoutingRule
import com.foxhole.beta.core.model.RoutingRuleAction

@Composable
internal fun ProxySurfaceDialog(
    title: String,
    initialValue: ProxyInboundSettings,
    auth: LocalAuthSettings,
    lanAccessEnabled: Boolean,
    wifiLanAddress: String?,
    onDismiss: () -> Unit,
    onConfirm: (ProxyInboundSettings) -> Unit,
) {
    val context = LocalContext.current
    val usernameLabel = stringResource(R.string.username)
    val passwordLabel = stringResource(R.string.password)
    var enabled by rememberSaveable { mutableStateOf(initialValue.enabled) }
    var port by rememberSaveable { mutableStateOf(initialValue.port.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { FoxholeDialogTitle(title = title, icon = Icons.Outlined.Tune) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingSwitchRow(
                    title = stringResource(R.string.enabled_label),
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
                Text(
                    text =
                        if (auth.enabled) {
                            stringResource(R.string.local_surface_auth_summary)
                        } else {
                            stringResource(R.string.local_surface_auth_disabled_summary)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.port)) }, singleLine = true)
                Text(
                    text =
                        if (lanAccessEnabled) {
                            stringResource(
                                if (wifiLanAddress == null) {
                                    R.string.proxy_surface_lan_waiting_for_wifi
                                } else {
                                    R.string.proxy_surface_lan_endpoint
                                },
                                wifiLanAddress ?: "",
                                port.toIntOrNull() ?: initialValue.port,
                            )
                        } else {
                            stringResource(R.string.proxy_surface_loopback_endpoint, port.toIntOrNull() ?: initialValue.port)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!lanAccessEnabled) {
                    Text(
                        text = stringResource(R.string.proxy_surface_lan_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = auth.username,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.username)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                copyTextToClipboard(context, usernameLabel, auth.username)
                            },
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy_username))
                        }
                    },
                )
                OutlinedTextField(
                    value = auth.password,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                copyTextToClipboard(context, passwordLabel, auth.password)
                            },
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy_password))
                        }
                    },
                )
            }
        },
        confirmButton = {
            FoxholeDialogConfirmButton(
                onClick = {
                    onConfirm(
                        ProxyInboundSettings(
                            enabled = enabled,
                            host = initialValue.host,
                            port = port.toIntOrNull() ?: initialValue.port,
                        ),
                    )
                    onDismiss()
                },
            )
        },
        dismissButton = {
            FoxholeDialogDismissButton(onClick = onDismiss)
        },
    )
}

@Composable
internal fun ClashApiDialog(
    initialValue: ClashApiSettings,
    auth: LocalAuthSettings,
    onDismiss: () -> Unit,
    onConfirm: (ClashApiSettings) -> Unit,
) {
    var enabled by rememberSaveable { mutableStateOf(initialValue.enabled) }
    var host by rememberSaveable { mutableStateOf(initialValue.host) }
    var port by rememberSaveable { mutableStateOf(initialValue.port.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { FoxholeDialogTitle(title = stringResource(R.string.clash_api), icon = Icons.Outlined.Public) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingSwitchRow(
                    title = stringResource(R.string.enabled_label),
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
                Text(
                    text = stringResource(R.string.local_surface_auth_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text(stringResource(R.string.host)) }, singleLine = true)
                OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.port)) }, singleLine = true)
                OutlinedTextField(
                    value = auth.apiSecret,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.secret)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            FoxholeDialogConfirmButton(
                onClick = {
                    onConfirm(
                        ClashApiSettings(
                            enabled = enabled,
                            host = host,
                            port = port.toIntOrNull() ?: initialValue.port,
                            secret = "",
                        ),
                    )
                    onDismiss()
                },
            )
        },
        dismissButton = {
            FoxholeDialogDismissButton(onClick = onDismiss)
        },
    )
}

@Composable
internal fun PresetDialog(
    preset: RoutingPreset,
    onDismiss: () -> Unit,
    onConfirm: (String, RoutingPresetOverrideMode, Boolean) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(preset.name) }
    var overrideMode by rememberSaveable { mutableStateOf(preset.overrideMode) }
    var enabled by rememberSaveable { mutableStateOf(preset.enabled) }
    var overrideDialog by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { FoxholeDialogTitle(title = stringResource(R.string.edit_preset), icon = Icons.Outlined.Tune) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                SettingValueRow(
                    title = stringResource(R.string.override_mode),
                    value = overrideMode.name.lowercase(),
                    onClick = { overrideDialog = true },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.enabled_label),
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
            }
        },
        confirmButton = {
            FoxholeDialogConfirmButton(onClick = { onConfirm(name, overrideMode, enabled) })
        },
        dismissButton = {
            FoxholeDialogDismissButton(onClick = onDismiss)
        },
    )

    if (overrideDialog) {
        EnumChoiceDialog(
            title = stringResource(R.string.override_mode),
            values = RoutingPresetOverrideMode.entries,
            selected = overrideMode,
            label = { it.name.lowercase() },
            onSelect = { overrideMode = it },
            onDismiss = { overrideDialog = false },
        )
    }
}

@Composable
internal fun RuleEditorDialog(
    presetId: Long,
    rule: RoutingRule?,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean, Int?, RoutingRuleAction, List<String>, List<String>, List<String>, List<String>, List<String>) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(rule?.name.orEmpty()) }
    var enabled by rememberSaveable { mutableStateOf(rule?.enabled ?: true) }
    var order by rememberSaveable { mutableStateOf(rule?.order?.toString().orEmpty()) }
    var action by rememberSaveable { mutableStateOf(rule?.action ?: RoutingRuleAction.PROXY) }
    var actionDialog by rememberSaveable { mutableStateOf(false) }
    var domains by rememberSaveable { mutableStateOf(rule?.matchDomains?.joinToString("\n").orEmpty()) }
    var ipCidrs by rememberSaveable { mutableStateOf(rule?.matchIpCidrs?.joinToString("\n").orEmpty()) }
    var ports by rememberSaveable { mutableStateOf(rule?.matchPorts?.joinToString("\n").orEmpty()) }
    var protocols by rememberSaveable { mutableStateOf(rule?.matchProtocols?.joinToString("\n").orEmpty()) }
    var networks by rememberSaveable { mutableStateOf(rule?.matchNetworks?.joinToString("\n").orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            FoxholeDialogTitle(
                title = if (rule == null) stringResource(R.string.add_rule) else stringResource(R.string.edit_rule),
                icon = Icons.Outlined.AccountTree,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                SettingValueRow(
                    title = stringResource(R.string.action_label),
                    value = action.name.lowercase(),
                    onClick = { actionDialog = true },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.enabled_label),
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
                OutlinedTextField(value = order, onValueChange = { order = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.order_label)) }, singleLine = true)
                OutlinedTextField(value = domains, onValueChange = { domains = it }, label = { Text(stringResource(R.string.match_domains)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = ipCidrs, onValueChange = { ipCidrs = it }, label = { Text(stringResource(R.string.match_ip_cidrs)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = ports, onValueChange = { ports = it }, label = { Text(stringResource(R.string.match_ports)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = protocols, onValueChange = { protocols = it }, label = { Text(stringResource(R.string.match_protocols)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = networks, onValueChange = { networks = it }, label = { Text(stringResource(R.string.match_networks)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            FoxholeDialogConfirmButton(
                onClick = {
                    onConfirm(
                        name,
                        enabled,
                        order.toIntOrNull(),
                        action,
                        tokenize(domains),
                        tokenize(ipCidrs),
                        tokenize(ports),
                        tokenize(protocols),
                        tokenize(networks),
                    )
                },
            )
        },
        dismissButton = {
            FoxholeDialogDismissButton(onClick = onDismiss)
        },
    )

    if (actionDialog) {
        EnumChoiceDialog(
            title = stringResource(R.string.action_label),
            values = RoutingRuleAction.entries,
            selected = action,
            label = { it.name.lowercase() },
            onSelect = { action = it },
            onDismiss = { actionDialog = false },
        )
    }
}

@Composable
internal fun CatalogDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { FoxholeDialogTitle(title = stringResource(R.string.add_catalog), icon = Icons.Outlined.FileDownload) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.expert_warning_body), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(stringResource(R.string.url)) }, singleLine = true)
            }
        },
        confirmButton = {
            FoxholeDialogConfirmButton(onClick = { onConfirm(name, url) })
        },
        dismissButton = {
            FoxholeDialogDismissButton(onClick = onDismiss)
        },
    )
}

@Composable
internal fun CatalogPreviewDialog(
    catalog: RoutingCatalog?,
    presets: List<RoutingRepository.RoutingCatalogPresetPreview>,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            FoxholeDialogTitle(
                title = catalog?.name ?: stringResource(R.string.remote_catalogs),
                icon = Icons.Outlined.FileDownload,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (presets.isEmpty()) {
                    Text(stringResource(R.string.routing_catalog_preview_empty))
                }
                presets.forEach { preset ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(preset.name)
                            Text("${preset.overrideMode.name.lowercase()} • ${preset.ruleCount}", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { onImport(preset.id) }) {
                            Text(stringResource(R.string.import_label))
                        }
                    }
                }
            }
        },
        confirmButton = {
            FoxholeDialogDismissButton(
                onClick = onDismiss,
                label = stringResource(R.string.close),
            )
        },
    )
}

@Composable
internal fun surfaceSummary(
    value: ProxyInboundSettings,
    authEnabled: Boolean,
): String =
    if (value.enabled) {
        "${value.host}:${value.port} • ${stringResource(if (authEnabled) R.string.auth_required_label else R.string.no_auth_label)}"
    } else {
        stringResource(R.string.disabled_label)
    }

@Composable
internal fun clashSummary(value: ClashApiSettings): String =
    if (value.enabled) {
        "${value.host}:${value.port} • ${stringResource(R.string.auth_required_label)}"
    } else {
        stringResource(R.string.disabled_label)
    }
