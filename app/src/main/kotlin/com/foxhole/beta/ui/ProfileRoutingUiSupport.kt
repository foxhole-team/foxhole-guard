package com.foxhole.beta.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.foxhole.beta.R
import com.foxhole.beta.core.model.InstalledAppOption
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.RoutingRule
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.core.profile.EditableProfileConfig
import com.foxhole.beta.core.profile.MultiProtocolProfileSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat

@Composable
internal fun ProfileRefreshConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.profile_refresh_confirm_title),
        body = stringResource(R.string.profile_refresh_confirm_body),
        confirmLabel = stringResource(R.string.refresh),
        icon = Icons.Outlined.Refresh,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
internal fun ProfileSaveConfirmDialog(
    canReconnectNow: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onSaveAndReconnect: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.profile_save_confirm_title),
        body =
            stringResource(
                if (canReconnectNow) {
                    R.string.profile_save_confirm_body_connected
                } else {
                    R.string.profile_save_confirm_body
                },
            ),
        confirmLabel =
            stringResource(
                if (canReconnectNow) {
                    R.string.save_and_reconnect
                } else {
                    R.string.save
                },
            ),
        icon = Icons.Outlined.Edit,
        secondaryLabel = if (canReconnectNow) stringResource(R.string.save) else null,
        onDismiss = onDismiss,
        onSecondary = if (canReconnectNow) onSave else null,
        onConfirm = if (canReconnectNow) onSaveAndReconnect else onSave,
    )
}

@Composable
internal fun ProfileListLoadingCard(tag: String) {
    FoxholeCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(tag),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FoxholeSkeletonBlock(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.52f)
                            .height(22.dp),
                )
                FoxholeSkeletonBlock(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.34f)
                            .height(16.dp),
                )
                FoxholeSkeletonBlock(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.28f)
                            .height(28.dp),
                )
            }
            FoxholeSkeletonBlock(modifier = Modifier.size(42.dp))
            FoxholeSkeletonBlock(modifier = Modifier.size(42.dp))
        }
    }
}

@Composable
internal fun ProfileConfigForm(
    profile: Profile,
    draft: EditableProfileConfig,
    editable: Boolean,
    selectedProtocolOptionId: String? = null,
    onProtocolOptionSelected: ((String) -> Unit)? = null,
    onDraftChanged: (EditableProfileConfig) -> Unit,
    onEditRequested: (title: String, value: String, singleLine: Boolean, onConfirm: (String) -> Unit) -> Unit,
) {
    var protocolMenuExpanded by rememberSaveable(profile.id) { mutableStateOf(false) }
    val supportedProtocolOptions = MultiProtocolProfileSupport.supportedOptions(profile)
    val selectedProtocolOption =
        supportedProtocolOptions.firstOrNull { option -> option.id == selectedProtocolOptionId }
            ?: supportedProtocolOptions.firstOrNull { option -> option.protocolHint.name.equals(draft.type, ignoreCase = true) }
            ?: supportedProtocolOptions.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoBlock(
            title = stringResource(R.string.information_title),
            body = profileEditorInfoBody(profile, draft),
        )
        ProfileEditorSection(title = stringResource(R.string.profile_editor_connection_section)) {
            if (editable && onProtocolOptionSelected != null && supportedProtocolOptions.size > 1 && selectedProtocolOption != null) {
                DropdownSettingRow(
                    title = stringResource(R.string.protocol),
                    value = profileEditorProtocolOptionLabel(selectedProtocolOption),
                    expanded = protocolMenuExpanded,
                    onExpandedChange = { protocolMenuExpanded = it },
                    values = supportedProtocolOptions,
                    selected = selectedProtocolOption,
                    label = { option -> profileEditorProtocolOptionLabel(option) },
                    onSelect = { option -> onProtocolOptionSelected(option.id) },
                    leadingIcon = profileEditorProtocolIcon(selectedProtocolOption.protocolHint),
                    optionIcon = { option -> profileEditorProtocolIcon(option.protocolHint) },
                )
            } else {
                ProfileEditorReadOnlyRow(
                    title = stringResource(R.string.protocol),
                    value = profileProtocolLabel(draft.type),
                )
            }
            ProfileEditorTextRow(
                title = stringResource(R.string.profile_editor_server),
                value = draft.server,
                editable = editable,
                onEditRequested = onEditRequested,
            ) { value -> onDraftChanged(draft.copy(server = value)) }
            ProfileEditorTextRow(
                title = stringResource(R.string.port),
                value = draft.port,
                editable = editable,
                onEditRequested = onEditRequested,
            ) { value -> onDraftChanged(draft.copy(port = value.filter(Char::isDigit))) }
        }
        when (draft.type) {
            "vless" -> {
                ProfileEditorSection(title = stringResource(R.string.profile_editor_auth_section)) {
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_uuid),
                        value = draft.uuid,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(uuid = value)) }
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_flow),
                        value = draft.flow,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(flow = value)) }
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_packet_encoding),
                        value = draft.packetEncoding,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(packetEncoding = value)) }
                }
            }
            "trojan" -> {
                ProfileEditorSection(title = stringResource(R.string.profile_editor_auth_section)) {
                    ProfileEditorTextRow(
                        title = stringResource(R.string.password),
                        value = draft.password,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(password = value)) }
                }
            }
            "shadowsocks" -> {
                ProfileEditorSection(title = stringResource(R.string.profile_editor_auth_section)) {
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_method),
                        value = draft.method,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(method = value)) }
                    ProfileEditorTextRow(
                        title = stringResource(R.string.password),
                        value = draft.password,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(password = value)) }
                }
            }
            "vmess" -> {
                ProfileEditorSection(title = stringResource(R.string.profile_editor_auth_section)) {
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_uuid),
                        value = draft.uuid,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(uuid = value)) }
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_alter_id),
                        value = draft.alterId,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(alterId = value.filter(Char::isDigit))) }
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_security),
                        value = draft.security,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(security = value)) }
                }
            }
            "hysteria2" -> {
                ProfileEditorSection(title = stringResource(R.string.profile_editor_auth_section)) {
                    ProfileEditorTextRow(
                        title = stringResource(R.string.password),
                        value = draft.password,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(password = value)) }
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_obfs),
                        value = draft.obfs,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(obfs = value)) }
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_obfs_password),
                        value = draft.obfsPassword,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(obfsPassword = value)) }
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_up_mbps),
                        value = draft.upMbps,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(upMbps = value.filter(Char::isDigit))) }
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_down_mbps),
                        value = draft.downMbps,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(downMbps = value.filter(Char::isDigit))) }
                }
            }
            "wireguard" -> {
                ProfileEditorSection(title = stringResource(R.string.profile_editor_wireguard_section)) {
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_private_key),
                        value = draft.privateKey,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(privateKey = value)) }
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_peer_public_key),
                        value = draft.peerPublicKey,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(peerPublicKey = value)) }
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_pre_shared_key),
                        value = draft.preSharedKey,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(preSharedKey = value)) }
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_local_address),
                        value = draft.localAddress,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(localAddress = value)) }
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_allowed_ips),
                        value = draft.allowedIps,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(allowedIps = value)) }
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_keepalive),
                        value = draft.persistentKeepalive,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(persistentKeepalive = value.filter(Char::isDigit))) }
                }
            }
        }
        if (draft.tls.enabled || draft.tls.serverName.isNotBlank() || draft.tls.alpn.isNotBlank()) {
            ProfileEditorSection(title = stringResource(R.string.profile_editor_tls_section)) {
                ProfileEditorTextRow(
                    title = stringResource(R.string.profile_editor_tls_server_name),
                    value = draft.tls.serverName,
                    editable = editable,
                    onEditRequested = onEditRequested,
                ) { value -> onDraftChanged(draft.copy(tls = draft.tls.copy(serverName = value))) }
                if (draft.tls.alpn.isNotBlank()) {
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_alpn),
                        value = draft.tls.alpn,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(tls = draft.tls.copy(alpn = value))) }
                }
                if (draft.tls.fingerprint.isNotBlank()) {
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_fingerprint),
                        value = draft.tls.fingerprint,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(tls = draft.tls.copy(fingerprint = value))) }
                }
                if (draft.tls.realityPublicKey.isNotBlank() || draft.tls.realityShortId.isNotBlank()) {
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_reality_public_key),
                        value = draft.tls.realityPublicKey,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(tls = draft.tls.copy(realityPublicKey = value))) }
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_reality_short_id),
                        value = draft.tls.realityShortId,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(tls = draft.tls.copy(realityShortId = value))) }
                }
            }
        }
        if (draft.transport.type != "tcp" || draft.transport.host.isNotBlank() || draft.transport.path.isNotBlank() || draft.transport.serviceName.isNotBlank()) {
            ProfileEditorSection(title = stringResource(R.string.profile_editor_transport_section)) {
                ProfileEditorReadOnlyRow(
                    title = stringResource(R.string.profile_editor_transport),
                    value = transportLabel(draft.transport.type),
                )
                if (draft.transport.host.isNotBlank() || draft.transport.type == "ws" || draft.transport.type == "http" || draft.transport.type == "httpupgrade") {
                    ProfileEditorTextRow(
                        title = stringResource(R.string.host),
                        value = draft.transport.host,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(transport = draft.transport.copy(host = value))) }
                }
                if (draft.transport.path.isNotBlank() || draft.transport.type == "ws" || draft.transport.type == "http" || draft.transport.type == "httpupgrade") {
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_path),
                        value = draft.transport.path,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(transport = draft.transport.copy(path = value))) }
                }
                if (draft.transport.serviceName.isNotBlank() || draft.transport.type == "grpc") {
                    ProfileEditorTextRow(
                        title = stringResource(R.string.profile_editor_service_name),
                        value = draft.transport.serviceName,
                        editable = editable,
                        onEditRequested = onEditRequested,
                    ) { value -> onDraftChanged(draft.copy(transport = draft.transport.copy(serviceName = value))) }
                }
            }
        }
    }
}

@Composable
internal fun ProfileEditorSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        content()
    }
}

@Composable
internal fun ProfileEditorTextRow(
    title: String,
    value: String,
    editable: Boolean,
    onEditRequested: (title: String, value: String, singleLine: Boolean, onConfirm: (String) -> Unit) -> Unit,
    onConfirm: (String) -> Unit,
) {
    SettingValueRow(
        title = title,
        value = value.ifBlank { stringResource(R.string.none_label) },
        onClick =
            if (editable) {
                {
                    onEditRequested(title, value, true, onConfirm)
                }
            } else {
                null
            },
    )
}

@Composable
internal fun ProfileEditorReadOnlyRow(
    title: String,
    value: String,
) {
    SettingValueRow(
        title = title,
        value = value.ifBlank { stringResource(R.string.none_label) },
        onClick = null,
    )
}

@Composable
internal fun profileEditorInfoBody(
    profile: Profile,
    draft: EditableProfileConfig,
): String =
    buildList {
        add(stringResource(R.string.profile_editor_info_body))
        if (draft.nodeCount > 1) {
            add(pluralStringResource(R.plurals.profile_editor_multi_node_summary, draft.nodeCount, draft.nodeCount))
        }
        if (profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL) {
            add(stringResource(R.string.profile_editor_subscription_summary))
        }
    }.joinToString(separator = "\n\n")

@Composable
internal fun profileProtocolLabel(type: String): String =
    when (type) {
        "vless" -> "VLESS"
        "trojan" -> "Trojan"
        "shadowsocks" -> "Shadowsocks"
        "vmess" -> "VMess"
        "hysteria2" -> "Hysteria2"
        "wireguard" -> "WireGuard"
        else -> type
    }

@Composable
private fun profileEditorProtocolOptionLabel(option: ProfileProtocolOption): String =
    option.displayName.ifBlank { profileProtocolLabel(option.protocolHint.name.lowercase()) }

private fun profileEditorProtocolIcon(protocol: ProtocolHint): ImageVector =
    when (protocol) {
        ProtocolHint.VLESS,
        ProtocolHint.TROJAN,
        ProtocolHint.SHADOWSOCKS,
        ProtocolHint.WIREGUARD -> Icons.Outlined.VpnKey
        ProtocolHint.HYSTERIA2,
        ProtocolHint.VMESS,
        ProtocolHint.OUTLINE -> Icons.Outlined.VpnKey
        ProtocolHint.SING_BOX -> Icons.Outlined.Tune
        ProtocolHint.UNKNOWN -> Icons.AutoMirrored.Outlined.HelpOutline
    }

@Composable
internal fun transportLabel(type: String): String =
    when (type) {
        "ws" -> "WebSocket"
        "grpc" -> "gRPC"
        "http" -> "HTTP"
        "httpupgrade" -> "HTTP Upgrade"
        else -> "TCP"
    }

@Composable
internal fun ProfileFieldDialog(
    title: String,
    initialValue: String,
    singleLine: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(title, initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { FoxholeDialogTitle(title = title, icon = Icons.Outlined.Tune) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = singleLine,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            FoxholeDialogConfirmButton(onClick = { onConfirm(value) })
        },
        dismissButton = {
            FoxholeDialogDismissButton(onClick = onDismiss)
        },
    )
}

internal val appIconCache = LruCache<String, ImageBitmap>(128)

internal fun filterApps(
    apps: List<InstalledAppOption>,
    query: String,
): List<InstalledAppOption> {
    if (query.isBlank()) {
        return apps
    }
    return apps.filter { app ->
        app.label.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true)
    }
}

internal fun resolveSelectedApps(
    installedApps: List<InstalledAppOption>,
    selectedPackages: List<String>,
): List<InstalledAppOption> {
    val indexed = installedApps.associateBy(InstalledAppOption::packageName)
    return selectedPackages
        .map { packageName ->
            indexed[packageName]
                ?: InstalledAppOption(
                    packageName = packageName,
                    label = packageName,
                    isSystemApp = false,
                )
        }
}

@Composable
internal fun appFilterLabel(filter: InstalledAppFilter): String =
    when (filter) {
        InstalledAppFilter.ALL -> stringResource(R.string.app_filter_all)
        InstalledAppFilter.USER -> stringResource(R.string.app_filter_user)
        InstalledAppFilter.SYSTEM -> stringResource(R.string.app_filter_system)
    }

internal fun installedAppFilterIcon(filter: InstalledAppFilter) =
    when (filter) {
        InstalledAppFilter.ALL -> Icons.Outlined.Apps
        InstalledAppFilter.USER -> Icons.Outlined.Person
        InstalledAppFilter.SYSTEM -> Icons.Outlined.Security
    }

internal fun perAppRoutingModeIcon(mode: PerAppRoutingMode) =
    when (mode) {
        PerAppRoutingMode.FULL_TUNNEL -> Icons.Outlined.Security
        PerAppRoutingMode.INCLUDE_SELECTED_APPS -> Icons.Outlined.Apps
        PerAppRoutingMode.EXCLUDE_SELECTED_APPS -> Icons.Outlined.ArrowOutward
    }

@Composable
internal fun ProtocolMark(protocol: ProtocolHint) {
    ProtocolMark(protocol = protocol, modifier = Modifier)
}

@Composable
internal fun ProtocolMark(
    protocol: ProtocolHint,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    tintOverride: Color? = null,
) {
    val (_, _, label) = protocolMarkVisuals(protocol)
    val labelColor =
        if (protocol == ProtocolHint.UNKNOWN) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProtocolMarkIcon(protocol = protocol, compact = compact, tintOverride = tintOverride)
        Text(
            text = label,
            style =
                if (compact) {
                        MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.5.sp,
                            lineHeight = 13.sp,
                        )
                } else {
                    MaterialTheme.typography.labelLarge
                },
            fontWeight = FontWeight.SemiBold,
            color = labelColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ProtocolMarkIcon(
    protocol: ProtocolHint,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    tintOverride: Color? = null,
) {
    val (icon, _, label) = protocolMarkVisuals(protocol)
    val defaultTint = MaterialTheme.colorScheme.onSurfaceVariant
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = tintOverride ?: defaultTint,
        modifier = modifier.size(if (compact) 13.dp else 18.dp),
    )
}

@Composable
internal fun protocolMarkVisuals(protocol: ProtocolHint): Triple<ImageVector, Color, String> =
    when (protocol) {
        ProtocolHint.VLESS -> Triple(Icons.Outlined.VpnKey, Color(0xFF2FB060), "VLESS")
        ProtocolHint.TROJAN -> Triple(Icons.Outlined.VpnKey, Color(0xFFEA8C2C), "TROJAN")
        ProtocolHint.SHADOWSOCKS -> Triple(Icons.Outlined.VpnKey, Color(0xFF4A90E2), "SHADOWSOCKS")
        ProtocolHint.WIREGUARD -> Triple(Icons.Outlined.VpnKey, Color(0xFF5B8E55), "WIREGUARD")
        ProtocolHint.HYSTERIA2 -> Triple(Icons.Outlined.VpnKey, Color(0xFFE05A47), "HYSTERIA2")
        ProtocolHint.VMESS -> Triple(Icons.Outlined.VpnKey, Color(0xFF6A7AF7), "VMESS")
        ProtocolHint.OUTLINE -> Triple(Icons.Outlined.VpnKey, Color(0xFF00A7A0), "OUTLINE")
        ProtocolHint.SING_BOX -> Triple(Icons.Outlined.Tune, Color(0xFFB3A26D), "SING-BOX")
        ProtocolHint.UNKNOWN ->
            Triple(Icons.AutoMirrored.Outlined.HelpOutline, MaterialTheme.colorScheme.onSurfaceVariant, "UNKNOWN")
    }

@Composable
internal fun SmartProfileBadge(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    ProfileTagBadge(
        text = stringResource(R.string.smart_profile_tag),
        modifier = modifier,
        compact = compact,
    )
}

@Composable
internal fun V2RayTunProfileBadge(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    ProfileTagBadge(
        text = stringResource(R.string.v2raytun_profile_tag),
        modifier = modifier,
        compact = compact,
    )
}

@Composable
private fun ProfileTagBadge(
    text: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val tone = foxholeSystemAwareAccentColor()
    Surface(
        modifier =
            if (compact) {
                modifier.offset(y = (-3).dp)
            } else {
                modifier
            },
        shape = if (compact) MaterialTheme.shapes.small else MaterialTheme.shapes.medium,
        color = tone.copy(alpha = if (compact) 0.12f else 0.14f),
        border = BorderStroke(1.dp, tone.copy(alpha = if (compact) 0.34f else 0.26f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = if (compact) 3.dp else 10.dp, vertical = if (compact) 0.5.dp else 6.dp),
            style =
                if (compact) {
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = 7.sp,
                        lineHeight = 7.sp,
                    )
                } else {
                    MaterialTheme.typography.labelMedium
                },
            fontWeight = FontWeight.SemiBold,
            color = tone,
        )
    }
}

@Composable
internal fun InlineSmartProfileTitle(
    title: String,
    isSmartProfile: Boolean,
    modifier: Modifier = Modifier,
    showSmartBadge: Boolean = true,
    showV2RayTunBadge: Boolean = false,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    fontWeight: FontWeight = FontWeight.SemiBold,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f, fill = false),
                style = style,
                fontWeight = fontWeight,
                maxLines = maxLines,
                overflow = overflow,
            )
            if (isSmartProfile && showSmartBadge) {
                SmartProfileBadge(
                    compact = true,
                )
            } else if (showV2RayTunBadge) {
                V2RayTunProfileBadge(
                    compact = true,
                )
            }
        }
        trailing()
    }
}

internal enum class SubscriptionExpiryPlacement {
    DASHBOARD,
    PROFILE_INLINE,
}
