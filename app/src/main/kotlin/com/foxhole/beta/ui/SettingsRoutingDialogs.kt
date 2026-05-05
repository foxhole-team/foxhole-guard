package com.foxhole.beta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ClashApiSettings
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.ProxyInboundSettings

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
                            if (wifiLanAddress == null) {
                                stringResource(R.string.proxy_surface_lan_waiting_for_wifi)
                            } else {
                                stringResource(R.string.proxy_surface_lan_endpoint, wifiLanAddress)
                            }
                        } else {
                            stringResource(R.string.proxy_surface_loopback_endpoint)
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
                if (auth.enabled) {
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
