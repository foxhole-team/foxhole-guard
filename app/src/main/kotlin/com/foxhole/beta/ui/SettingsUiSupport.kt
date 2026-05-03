package com.foxhole.beta.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.getSystemService
import com.foxhole.beta.R
import com.foxhole.beta.core.data.RoutingRepository
import com.foxhole.beta.core.diagnostics.DiagnosticEntry
import com.foxhole.beta.core.model.ClashApiSettings
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.DomainStrategy
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.RoutingCatalog
import com.foxhole.beta.core.model.RoutingPreset
import com.foxhole.beta.core.model.RoutingPresetOverrideMode
import com.foxhole.beta.core.model.RoutingRule
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TunStack
import com.foxhole.beta.ui.theme.LocalFoxholeUiPalette
import com.foxhole.beta.vpn.AndroidLanProxyAddressProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SettingsScaffold(
    title: String,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: (() -> Unit)?,
    tag: String = "settings_screen",
    actions: @Composable RowScope.() -> Unit = {},
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    FoxholeLazyScaffold(
        title = title,
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        actions = actions,
        tag = tag,
        content = content,
    )
}

@Composable
internal fun SettingsNavigationRow(
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String,
    summary: String? = null,
    showAlertDot: Boolean = false,
    containerColor: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    leadingIconContainerColor: Color = Color.Unspecified,
    leadingIconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    summaryMaxLines: Int = 1,
    grouped: Boolean = false,
    onClick: () -> Unit,
) {
    val trailingContent: @Composable RowScope.() -> Unit = {
        if (showAlertDot) {
            Box(
                modifier =
                    Modifier
                        .padding(end = 8.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
            )
        }
    }
    if (grouped) {
        SettingsControlRow(
            modifier = modifier,
            title = title,
            summary = summary?.trimMenuSummary(),
            leadingIcon = icon,
            leadingIconContainerColor = leadingIconContainerColor,
            leadingIconTint = leadingIconTint,
            summaryMaxLines = summaryMaxLines,
            onClick = onClick,
            trailingContent = trailingContent,
        )
        return
    }
    FoxholePreferenceCard(
        modifier = modifier,
        title = title,
        summary = summary?.trimMenuSummary(),
        leadingIcon = icon,
        onClick = onClick,
        containerColor = containerColor,
        borderColor = borderColor,
        leadingIconContainerColor = leadingIconContainerColor,
        leadingIconTint = leadingIconTint,
        summaryMaxLines = summaryMaxLines,
        trailingContent = trailingContent,
    )
}

private fun String.trimMenuSummary(): String = trimEnd().removeSuffix(".")

@Composable
internal fun SettingValueRow(
    title: String,
    value: String,
    summary: String? = null,
    leadingIcon: ImageVector? = null,
    onClick: (() -> Unit)?,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    summaryMaxLines: Int = 1,
    grouped: Boolean = false,
) {
    if (grouped) {
        SettingsControlRow(
            title = title,
            summary = summary,
            leadingIcon = leadingIcon,
            onClick = onClick,
            summaryMaxLines = summaryMaxLines,
            trailingContent = {
                trailingContent?.invoke(this) ?: FoxholeValuePill(value = value)
            },
        )
        return
    }
    FoxholePreferenceCard(
        title = title,
        summary = summary,
        leadingIcon = leadingIcon,
        onClick = onClick,
        summaryMaxLines = summaryMaxLines,
        trailingContent = {
            trailingContent?.invoke(this) ?: FoxholeValuePill(value = value)
        },
    )
}

@Composable
internal fun <T> DropdownSettingRow(
    title: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    summary: String? = null,
    leadingIcon: ImageVector? = null,
    optionIcon: ((T) -> ImageVector)? = null,
    summaryMaxLines: Int = 1,
    grouped: Boolean = false,
) {
    val optionLabels = values.map { option -> label(option) }
    val baseMenuWidth =
        rememberFoxholeDropdownMenuWidth(
            labels = optionLabels,
            textStyle = MaterialTheme.typography.bodyMedium,
            hasIcons = optionIcon != null,
            minWidth = 72.dp,
        )
    val triggerWidth =
        rememberDropdownTriggerWidth(
            label = value,
            textStyle = MaterialTheme.typography.bodyMedium,
        )
    val menuWidth = baseMenuWidth.coerceAtLeast(triggerWidth)
    SettingValueRow(
        title = title,
        value = value,
        summary = summary,
        leadingIcon = leadingIcon,
        onClick = { onExpandedChange(true) },
        summaryMaxLines = summaryMaxLines,
        trailingContent = {
            Box(
                modifier = Modifier.width(triggerWidth),
                contentAlignment = Alignment.TopEnd,
            ) {
                FoxholeValuePill(
                    value = value,
                    modifier = Modifier.fillMaxWidth(),
                    expanded = expanded,
                    onClick = { onExpandedChange(!expanded) },
                )
                FoxholeDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { onExpandedChange(false) },
                    modifier = Modifier.width(menuWidth),
                    popupGap = 0.dp,
                    horizontalAlignment = FoxholeDropdownHorizontalAlignment.AnchorEnd,
                ) {
                    values.forEachIndexed { index, option ->
                        FoxholeDropdownItem(
                            onClick = {
                                onSelect(option)
                                onExpandedChange(false)
                            },
                            selected = option == selected,
                            extendSelectedToMenuTop = index == 0,
                            extendSelectedToMenuBottom = index == values.lastIndex,
                            leadingContent =
                                optionIcon?.let { icon ->
                                    {
                                        Icon(
                                            imageVector = icon(option),
                                            contentDescription = null,
                                        )
                                    }
                                },
                        ) {
                            Text(
                                text = optionLabels[index],
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        grouped = grouped,
    )
}

@Composable
private fun rememberDropdownTriggerWidth(
    label: String,
    textStyle: TextStyle,
): Dp {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val textWidthPx =
        remember(label, textStyle) {
            textMeasurer.measure(text = label, style = textStyle).size.width
        }
    return with(density) {
        (textWidthPx.toDp() + 44.dp)
            .coerceAtLeast(72.dp)
            .coerceAtMost((screenWidth - ScreenHorizontalPadding * 2).coerceAtLeast(0.dp))
    }
}

@Composable
internal fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    summaryMaxLines: Int = 1,
    grouped: Boolean = false,
) {
    val switchStateDescription =
        stringResource(
            if (checked) {
                R.string.switch_state_on
            } else {
                R.string.switch_state_off
            },
        )
    val rowModifier =
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = title
            stateDescription = switchStateDescription
        }
    val rowClick =
        if (enabled) {
            { onCheckedChange(!checked) }
        } else {
            null
        }
    val rowTrailingContent: @Composable RowScope.() -> Unit = {
        FoxholeSwitch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = if (enabled) onCheckedChange else null,
            modifier =
                Modifier.semantics {
                    contentDescription = title
                    stateDescription = switchStateDescription
                },
        )
    }
    if (grouped) {
        SettingsControlRow(
            modifier = rowModifier,
            title = title,
            summary = summary,
            leadingIcon = leadingIcon,
            summaryMaxLines = summaryMaxLines,
            onClick = rowClick,
            trailingContent = rowTrailingContent,
        )
        return
    }
    FoxholePreferenceCard(
        modifier = rowModifier,
        title = title,
        summary = summary,
        leadingIcon = leadingIcon,
        summaryMaxLines = summaryMaxLines,
        onClick = rowClick,
        trailingContent = rowTrailingContent,
    )
}

@Composable
internal fun SettingsControlGroup(
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Surface(
        modifier = Modifier.fillMaxWidth().clip(shape),
        shape = shape,
        color = LocalFoxholeUiPalette.current.cardContainerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    }
}

@Composable
internal fun SettingsControlGroupDivider() {
    HorizontalDivider(
        modifier = Modifier.fillMaxWidth(),
        thickness = 2.dp,
        color = MaterialTheme.colorScheme.background,
    )
}

@Composable
private fun SettingsControlRow(
    modifier: Modifier = Modifier,
    title: String,
    summary: String? = null,
    leadingIcon: ImageVector? = null,
    leadingIconContainerColor: Color = Color.Unspecified,
    leadingIconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    summaryMaxLines: Int = 1,
    onClick: (() -> Unit)?,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = if (summary.isNullOrBlank()) 48.dp else 60.dp)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon?.let { icon ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color =
                    if (leadingIconContainerColor == Color.Unspecified) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
                    } else {
                        leadingIconContainerColor
                    },
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(7.dp).size(18.dp),
                    tint = leadingIconTint,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            summary?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = summaryMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailingContent?.let {
            Row(
                modifier = Modifier.widthIn(min = 52.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = it,
            )
        }
    }
}

@Composable
internal fun rememberWifiLanAddress(): State<String?> {
    val appContext = LocalContext.current.applicationContext
    val connectivityManager = remember(appContext) { appContext.getSystemService<ConnectivityManager>() }
    val lanAddressProvider = remember(appContext) { AndroidLanProxyAddressProvider(appContext) }
    val wifiLanAddress = remember(connectivityManager, lanAddressProvider) { mutableStateOf(lanAddressProvider.currentWifiIpv4Address()) }
    DisposableEffect(connectivityManager, lanAddressProvider) {
        val manager =
            connectivityManager ?: return@DisposableEffect onDispose {
            }
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    wifiLanAddress.value = lanAddressProvider.currentWifiIpv4Address()
                }

                override fun onLost(network: Network) {
                    wifiLanAddress.value = lanAddressProvider.currentWifiIpv4Address()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    wifiLanAddress.value = lanAddressProvider.currentWifiIpv4Address()
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) {
                    wifiLanAddress.value = lanAddressProvider.currentWifiIpv4Address()
                }
            }
        wifiLanAddress.value = lanAddressProvider.currentWifiIpv4Address()
        manager.registerDefaultNetworkCallback(callback)
        onDispose {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
    return wifiLanAddress
}

@Composable
internal fun proxyLanAccessSummary(
    allowLanAccess: Boolean,
    wifiLanAddress: String?,
): String =
    when {
        wifiLanAddress == null && allowLanAccess ->
            stringResource(R.string.proxy_lan_access_waiting_for_wifi_summary)
        wifiLanAddress == null ->
            stringResource(R.string.proxy_lan_access_wifi_only_summary)
        allowLanAccess ->
            stringResource(R.string.proxy_lan_access_enabled_summary, wifiLanAddress)
        else ->
            stringResource(R.string.proxy_lan_access_disabled_summary, wifiLanAddress)
    }

@Composable
internal fun WarningBlock(
    title: String,
    body: String,
) {
    FoxholeCard {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun InfoBlock(
    title: String,
    body: String,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun LocalProxyAuthEditor(
    auth: LocalAuthSettings,
    onAuthChanged: (LocalAuthSettings) -> Unit,
    grouped: Boolean = false,
) {
    var usernameDialog by rememberSaveable { mutableStateOf(false) }
    var passwordDialog by rememberSaveable { mutableStateOf(false) }

    SettingValueRow(
        title = stringResource(R.string.username),
        value = auth.username,
        summary = stringResource(R.string.proxy_auth_live_apply_summary),
        onClick = { usernameDialog = true },
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FoxholeValuePill(value = auth.username.ifBlank { "-" })
                IconButton(onClick = { usernameDialog = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.edit_label),
                    )
                }
            }
        },
        grouped = grouped,
    )
    if (grouped) {
        SettingsControlGroupDivider()
    }
    SettingValueRow(
        title = stringResource(R.string.password),
        value = maskedProxySecret(auth.password),
        onClick = { passwordDialog = true },
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FoxholeValuePill(value = maskedProxySecret(auth.password))
                IconButton(onClick = { passwordDialog = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.edit_label),
                    )
                }
            }
        },
        grouped = grouped,
    )

    if (usernameDialog) {
        TextValueDialog(
            title = stringResource(R.string.edit_proxy_username_title),
            icon = Icons.Outlined.Edit,
            initialValue = auth.username,
            singleLine = true,
            onDismiss = { usernameDialog = false },
            onConfirm = { value ->
                onAuthChanged(auth.copy(username = value))
            },
        )
    }

    if (passwordDialog) {
        TextValueDialog(
            title = stringResource(R.string.edit_proxy_password_title),
            icon = Icons.Outlined.Edit,
            initialValue = auth.password,
            singleLine = true,
            onDismiss = { passwordDialog = false },
            onConfirm = { value ->
                onAuthChanged(auth.copy(password = value))
            },
        )
    }
}

@Composable
internal fun SettingsFooterVersionText(
    text: String,
    summary: String,
    onRepositoryClick: () -> Unit,
    onSupportBotClick: () -> Unit,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .clickable(onClick = onClick)
                    .testTag("settings_footer_version_card"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                )
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Row(
            modifier =
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsFooterActionButton(
                painterRes = R.drawable.ic_github_mark,
                contentDescription = stringResource(R.string.open_github_repository),
                tint = MaterialTheme.colorScheme.onSurface,
                iconSize = 30.dp,
                testTag = "settings_github_repository_action",
                onClick = onRepositoryClick,
            )
            SettingsFooterActionButton(
                painterRes = R.drawable.ic_telegram_mark,
                contentDescription = stringResource(R.string.open_support_bot),
                tint = Color.Unspecified,
                iconSize = 34.dp,
                testTag = "settings_footer_support_bot_action",
                onClick = onSupportBotClick,
            )
        }
    }
}

@Composable
private fun SettingsFooterActionButton(
    painterRes: Int,
    contentDescription: String,
    tint: Color,
    iconSize: Dp,
    testTag: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier =
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.54f),
                    shape = CircleShape,
                )
                .testTag(testTag),
    ) {
        Icon(
            painter = painterResource(painterRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

private fun maskedProxySecret(value: String): String =
    if (value.isBlank()) {
        "••••••••"
    } else {
        "•".repeat(value.length.coerceIn(8, 12))
    }

@Composable
internal fun HelpSection(
    icon: ImageVector,
    title: String,
    body: String,
) {
    HelpSectionContent(
        icon = icon,
        title = title,
    ) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun HelpSectionContent(
    icon: ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "help_section_chevron",
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = chevronRotation },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 0.dp, end = 4.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun trafficInfoBody(state: SettingsRouteUiState): String =
    buildList {
        add(
            if (state.settings.traffic.mode == TrafficMode.TUNNEL) {
                stringResource(R.string.traffic_info_tunnel)
            } else {
                stringResource(R.string.traffic_info_proxy)
            },
        )
        if (state.settings.traffic.mode == TrafficMode.PROXY && state.settings.expert.localSurfaces.auth.enabled) {
            add(stringResource(R.string.proxy_mode_firefox_hint))
        }
        if (state.reconnectRequired) {
            add(stringResource(R.string.traffic_info_reconnect))
        }
    }.joinToString(separator = "\n\n")

@Composable
internal fun ActionRow(
    primaryLabel: String,
    onPrimary: (() -> Unit)?,
    secondaryLabel: String?,
    onSecondary: (() -> Unit)?,
    tertiaryLabel: String?,
    onTertiary: (() -> Unit)?,
) {
    FoxholeCard {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            onPrimary?.let {
                Button(onClick = it) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(primaryLabel, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (secondaryLabel != null && onSecondary != null) {
                OutlinedButton(onClick = onSecondary) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = null)
                    Text(secondaryLabel, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (tertiaryLabel != null && onTertiary != null) {
                OutlinedButton(onClick = onTertiary) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null)
                    Text(tertiaryLabel, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
internal fun PresetActionRow(
    onActivate: () -> Unit,
    activateLabel: String,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onAddRule: () -> Unit,
) {
    FoxholeCard {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onActivate) { Text(activateLabel) }
            OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.edit_label)) }
            OutlinedButton(onClick = onExport) { Text(stringResource(R.string.export_label)) }
            OutlinedButton(onClick = onAddRule) { Text(stringResource(R.string.add_rule)) }
            OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.delete_label)) }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun <T> EnumChoiceDialog(
    title: String,
    icon: ImageVector? = null,
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.foxholeDialogChrome(),
        shape = FoxholeDialogShape,
        title = { FoxholeDialogTitle(title = title, icon = icon) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                values.forEach { value ->
                    FoxholeChoiceCard(
                        title = label(value),
                        selected = value == selected,
                        onClick = {
                            onSelect(value)
                            onDismiss()
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            FoxholeDialogDismissButton(onClick = onDismiss)
        },
    )
}

@Composable
internal fun IntValueDialog(
    title: String,
    icon: ImageVector? = null,
    initialValue: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initialValue.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.foxholeDialogChrome(),
        shape = FoxholeDialogShape,
        title = { FoxholeDialogTitle(title = title, icon = icon) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.filter(Char::isDigit) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            FoxholeDialogConfirmButton(
                onClick = {
                    onConfirm(value.toIntOrNull() ?: initialValue)
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
internal fun TextValueDialog(
    title: String,
    icon: ImageVector? = null,
    initialValue: String,
    singleLine: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.foxholeDialogChrome(),
        shape = FoxholeDialogShape,
        title = { FoxholeDialogTitle(title = title, icon = icon) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = singleLine,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            FoxholeDialogConfirmButton(
                onClick = {
                    onConfirm(value)
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
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    iconContainerColor: Color? = null,
    bodyIcon: ImageVector? = null,
    bodyIconTint: Color? = null,
    dismissLabel: String? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    prominentActions: Boolean = false,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.foxholeDialogChrome(),
        shape = FoxholeDialogShape,
        properties =
            DialogProperties(
                dismissOnBackPress = dismissOnBackPress,
                dismissOnClickOutside = dismissOnClickOutside,
            ),
        title = {
            FoxholeDialogTitle(
                title = title,
                icon = icon,
                iconTint = iconTint,
                iconContainerColor = iconContainerColor,
            )
        },
        text = {
            FoxholeDialogBody(
                body = body,
                icon = bodyIcon,
                iconTint = bodyIconTint,
            )
        },
        confirmButton = {
            if (prominentActions) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    dismissLabel?.let { label ->
                        FoxholeDialogDismissButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("confirm_dialog_dismiss_button"),
                            label = label,
                        )
                    }
                    if (secondaryLabel != null && onSecondary != null) {
                        FoxholeDialogSecondaryButton(
                            label = secondaryLabel,
                            onClick = onSecondary,
                            modifier = Modifier.testTag("confirm_dialog_secondary_button"),
                        )
                    }
                    FoxholeDialogConfirmButton(
                        onClick = onConfirm,
                        modifier = Modifier.testTag("confirm_dialog_confirm_button"),
                        label = confirmLabel,
                    )
                }
            } else {
                FoxholeDialogConfirmButton(
                    onClick = onConfirm,
                    modifier = Modifier.testTag("confirm_dialog_confirm_button"),
                    label = confirmLabel,
                )
            }
        },
        dismissButton = {
            if (!prominentActions) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (secondaryLabel != null && onSecondary != null) {
                        FoxholeDialogSecondaryButton(
                            label = secondaryLabel,
                            onClick = onSecondary,
                        )
                    }
                    FoxholeDialogDismissButton(
                        onClick = onDismiss,
                        label = dismissLabel,
                    )
                }
            }
        },
    )
}

@Composable
private fun FoxholeDialogBody(
    body: String,
    icon: ImageVector?,
    iconTint: Color?,
) {
    val resolvedIconTint = iconTint ?: FoxholeInfoAccent
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        icon?.let { imageVector ->
            Surface(
                shape = CircleShape,
                color = resolvedIconTint.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, resolvedIconTint.copy(alpha = 0.24f)),
            ) {
                Icon(
                    imageVector = imageVector,
                    contentDescription = null,
                    tint = resolvedIconTint,
                    modifier =
                        Modifier
                            .padding(6.dp)
                            .size(16.dp),
                )
            }
        }
        Text(
            text = body,
            modifier = Modifier.weight(1f),
            style =
                if (icon == null) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodySmall
                },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun tunStackLabel(value: TunStack): String = value.configValue.replaceFirstChar(Char::uppercaseChar)

internal fun domainStrategyLabel(value: DomainStrategy): String =
    value.configValue
        .split('_')
        .joinToString(separator = " ") { part -> part.replaceFirstChar(Char::uppercaseChar) }

internal fun ruleSummary(rule: RoutingRule): String =
    buildList {
        if (rule.matchDomains.isNotEmpty()) add("domain=${rule.matchDomains.joinToString()}")
        if (rule.matchIpCidrs.isNotEmpty()) add("ip=${rule.matchIpCidrs.joinToString()}")
        if (rule.matchPorts.isNotEmpty()) add("port=${rule.matchPorts.joinToString()}")
        if (rule.matchProtocols.isNotEmpty()) add("protocol=${rule.matchProtocols.joinToString()}")
        if (rule.matchNetworks.isNotEmpty()) add("network=${rule.matchNetworks.joinToString()}")
        add("action=${rule.action.name.lowercase()}")
    }.joinToString(" • ")

internal fun tokenize(raw: String): List<String> =
    raw.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()
