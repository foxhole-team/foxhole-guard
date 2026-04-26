package com.foxhole.beta.ui

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.profile.MultiProtocolProfileSupport
import java.util.Locale
import kotlinx.coroutines.delay

internal val HomePrimaryActionHeight = 52.dp
internal val HomeTriangleIndicatorSize = 15.dp
internal val HomeDashboardBannerTopPadding = 86.dp
internal val HomeConnectingStatusSignalOffset = 3.dp
internal val HomeNetworkContentHeight = 112.dp

@Composable
internal fun HomeCardHeader(
    icon: ImageVector,
    title: String,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier =
                    Modifier
                        .padding(5.dp)
                        .size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (titleContent != null) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = titleContent,
            )
        } else {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        trailing()
    }
}

@Composable
internal fun HomeHeaderActionButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = FoxholeInfoAccent,
) {
    Surface(
        modifier =
            modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled) { onClick() },
        shape = CircleShape,
        color = tint.copy(alpha = if (enabled) 0.14f else 0.06f),
        border = BorderStroke(1.dp, tint.copy(alpha = if (enabled) 0.34f else 0.14f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
                tint = tint.copy(alpha = if (enabled) 1f else 0.38f),
            )
        }
    }
}

@Composable
internal fun HomeNetworkDetailLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueMonospace: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = if (valueMonospace) FontFamily.Monospace else FontFamily.Default,
                ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun HomeNetworkSubtleDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
}

@Composable
internal fun HomeNetworkColumnTitle(text: String) {
    Text(
        text = text,
        style =
            MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Bold,
            ),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun HomeModeDropdown(
    selected: HomeModeOption,
    onSelect: (HomeModeOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectorTint = FoxholeInfoAccent
    var expanded by rememberSaveable(selected) { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            modifier =
                Modifier
                    .testTag("home_mode_selector")
                    .foxholeAnimateContentSize()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { expanded = true },
            shape = MaterialTheme.shapes.small,
            color = selectorTint.copy(alpha = 0.16f),
            border = BorderStroke(1.dp, selectorTint.copy(alpha = 0.28f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = homeModeOptionIcon(selected),
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = selectorTint,
                )
                Text(
                    text = homeModeChipLabel(selected),
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            lineHeight = 10.sp,
                        ),
                    fontWeight = FontWeight.SemiBold,
                    color = selectorTint,
                )
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = selectorTint.copy(alpha = 0.84f),
                )
            }
        }
        FoxholeDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 232.dp, max = 278.dp),
            offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = (-4).dp),
        ) {
            Column(
                modifier = Modifier.padding(3.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HomeModeOption.entries.forEach { option ->
                    val selectedOption = option == selected
                    FoxholeDropdownItem(
                        onClick = {
                            expanded = false
                            if (option != selected) {
                                onSelect(option)
                            }
                        },
                        selected = selectedOption,
                        accentColor = FoxholeInfoAccent,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp),
                        leadingContent = {
                            Icon(
                                imageVector = homeModeOptionIcon(option),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint =
                                    if (selectedOption) {
                                        FoxholeInfoAccent
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        },
                        trailingContent = {
                            if (selectedOption) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                    tint = FoxholeInfoAccent,
                                )
                            }
                        },
                    ) {
                        Text(
                            text = homeModeMenuLabel(option),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selectedOption) FontWeight.SemiBold else FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeLanProxyChip() {
    val color = Color(0xFFE38B2C)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.14f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Public,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = color,
            )
            Text(
                text = stringResource(R.string.home_lan_proxy_tag),
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        lineHeight = 10.sp,
                    ),
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
    }
}

@Composable
internal fun HomeStatusBadge(
    state: ConnectionState,
    label: String,
    textStyle: TextStyle = MaterialTheme.typography.titleLarge,
    accentColor: Color? = null,
) {
    val color = accentColor ?: homeStatusTone(state)
    Row(
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeStatusSignal(
            tint = color,
            settled = state == ConnectionState.CONNECTED,
            animate = state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING,
        )
        Text(
            text = label,
            style = textStyle,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
internal fun HomeStatusSignal(
    tint: Color,
    settled: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    if (animate) {
        HomeAnalysisSignal(
            tint = tint,
            modifier = modifier.offset(x = HomeConnectingStatusSignalOffset),
        )
        return
    }
    Canvas(
        modifier = modifier.size(HomeTriangleIndicatorSize),
    ) {
        val dotRadius = size.minDimension * 0.16f
        val anchors = homeTriangleAnchors(size.minDimension)
        anchors.forEach { center ->
            drawCircle(
                color = tint.copy(alpha = if (settled) 0.96f else 0.82f),
                radius = dotRadius,
                center = center,
            )
        }
    }
}

@Composable
internal fun HomeTypewriterProtocolText(
    targetText: String,
    prefix: String,
    color: Color,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    var shownText by remember { mutableStateOf(targetText) }

    LaunchedEffect(targetText) {
        if (shownText == targetText) {
            return@LaunchedEffect
        }
        while (shownText.isNotEmpty()) {
            shownText = shownText.dropLast(1)
            delay(18)
        }
        for (index in targetText.indices) {
            shownText = targetText.take(index + 1)
            delay(22)
        }
    }

    Text(
        text = "$prefix$shownText",
        modifier = modifier,
        style = textStyle,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = color,
    )
}

@Composable
internal fun HomeAnalysisStatusText(
    protocolLabel: String,
    tint: Color,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val reducedTextStyle =
        if (textStyle.fontSize != TextUnit.Unspecified) {
            textStyle.copy(fontSize = (textStyle.fontSize.value - 2f).coerceAtLeast(1f).sp)
        } else {
            textStyle
        }
    HomeTypewriterProtocolText(
        targetText = protocolLabel,
        prefix = "${stringResource(R.string.home_status_analysis)}: ",
        color = tint,
        textStyle = reducedTextStyle,
        modifier = modifier.offset(y = 2.dp),
    )
}

@Composable
internal fun TrafficStatBlock(
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    headerSpacing: Dp = 3.dp,
    leadingContentSpacing: Dp = 1.dp,
    label: String,
    value: String,
    secondary: String,
    valueTag: String? = null,
    secondaryTag: String? = null,
) {
    Box(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(headerSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingContent != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(leadingContentSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                        content = leadingContent,
                    )
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = iconTint,
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = value,
                modifier = valueTag?.let(Modifier::testTag) ?: Modifier,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = secondary,
                modifier = secondaryTag?.let(Modifier::testTag) ?: Modifier,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun HomeProfileLoadingBlock() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FoxholeSkeletonBlock(
            modifier =
                Modifier
                    .fillMaxWidth(0.5f)
                    .height(20.dp),
        )
        FoxholeSkeletonBlock(
            modifier =
                Modifier
                    .fillMaxWidth(0.7f)
                    .height(14.dp),
        )
        FoxholeSkeletonBlock(
            modifier =
                Modifier
                    .fillMaxWidth(0.32f)
                    .height(24.dp),
        )
    }
}

@Composable
internal fun HomeNetworkLoadingBlock(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        FoxholeSkeletonBlock(
            modifier =
                Modifier
                    .fillMaxWidth(0.32f)
                    .height(12.dp),
        )
        FoxholeSkeletonBlock(
            modifier =
                Modifier
                    .fillMaxWidth(0.52f)
                    .height(20.dp),
        )
        FoxholeSkeletonBlock(
            modifier =
                Modifier
                    .fillMaxWidth(0.36f)
                    .height(12.dp),
        )
    }
}

@Composable
internal fun HomeConnectionStatusLoadingBlock(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HomeNetworkColumnTitle(stringResource(R.string.home_network_profile_info_title))
        repeat(3) { index ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FoxholeSkeletonBlock(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(11.dp),
                )
                FoxholeSkeletonBlock(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(11.dp),
                )
            }
            if (index < 2) {
                HomeNetworkSubtleDivider()
            }
        }
    }
}

@Composable
internal fun rememberConnectionDurationText(snapshot: ConnectionSnapshot): String? {
    if (!shouldShowConnectionDuration(snapshot)) {
        return null
    }
    val locale = LocalLocale.current.platformLocale
    val now by
        produceState(
            initialValue = System.currentTimeMillis(),
            key1 = snapshot.lastChangeAt,
            key2 = snapshot.state,
        ) {
            value = System.currentTimeMillis()
            while (true) {
                val elapsedMs = (value - snapshot.lastChangeAt).coerceAtLeast(0L)
                delay(connectionDurationTickDelayMillis(elapsedMs))
                value = System.currentTimeMillis()
            }
        }
    return formatConnectionDuration(
        elapsedMs = (now - snapshot.lastChangeAt).coerceAtLeast(0L),
        locale = locale,
    )
}

internal fun shouldShowConnectionDuration(snapshot: ConnectionSnapshot): Boolean =
    snapshot.state in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING)

internal fun formatConnectionDuration(
    elapsedMs: Long,
    locale: Locale,
): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val days = totalSeconds / 86_400L
    val hours = (totalSeconds % 86_400L) / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    val isRussian = locale.language.equals("ru", ignoreCase = true)
    return when {
        days > 0L ->
            if (isRussian) {
                String.format(locale, "%d д %02d ч", days, hours)
            } else {
                String.format(locale, "%d d %02d h", days, hours)
            }
        hours > 0L ->
            if (isRussian) {
                String.format(locale, "%d ч %02d мин", hours, minutes)
            } else {
                String.format(locale, "%d h %02d m", hours, minutes)
            }
        minutes > 0L ->
            if (isRussian) {
                String.format(locale, "%d мин %02d с", minutes, seconds)
            } else {
                String.format(locale, "%d m %02d s", minutes, seconds)
            }
        else ->
            if (isRussian) {
                String.format(locale, "%02d с", seconds)
            } else {
                String.format(locale, "%02d s", seconds)
            }
    }
}

private fun connectionDurationTickDelayMillis(elapsedMs: Long): Long =
    when {
        elapsedMs >= 3_600_000L -> 30_000L
        else -> 1_000L
    }

internal fun dashboardProfileTitle(title: String): String =
    if (title.length <= 25) {
        title
    } else {
        "${title.take(23)}.."
    }

@Composable
internal fun HomeConnectionActions(
    state: HomeRouteUiState,
    onToggleConnection: () -> Unit,
    onAutoConnect: () -> Unit,
) {
    val activeProfile = state.activeProfile
    val hasMultipleProtocols = MultiProtocolProfileSupport.hasMultipleSupportedOptions(activeProfile)
    val autoConnectRunning = state.autoConnect.running
    val autoConnectEnabled =
        activeProfile != null &&
            !autoConnectRunning &&
            state.connection.state !in setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING)
    val autoConnectColor =
        if (autoConnectEnabled) {
            FoxholeInfoAccent
        } else {
            FoxholeInfoAccent.copy(alpha = 0.46f)
        }
    val primaryAction = if (autoConnectRunning) HomePrimaryAction.STOP else homePrimaryAction(state)
    val primaryButtonColor =
        when (primaryAction) {
            HomePrimaryAction.START,
            HomePrimaryAction.RECONNECT,
            -> FoxholePositiveAccent
            HomePrimaryAction.STOP -> Color(0xFFC95353)
        }
    val primaryButtonColors =
        ButtonDefaults.outlinedButtonColors(
            contentColor = primaryButtonColor,
            containerColor = primaryButtonColor.copy(alpha = 0.06f),
            disabledContentColor = primaryButtonColor.copy(alpha = 0.52f),
            disabledContainerColor = primaryButtonColor.copy(alpha = 0.04f),
        )
    val primaryButtonBorder = BorderStroke(1.dp, primaryButtonColor.copy(alpha = 0.36f))
    val primaryIcon =
        when (primaryAction) {
            HomePrimaryAction.RECONNECT -> Icons.Outlined.Refresh
            else -> Icons.Outlined.PowerSettingsNew
        }
    val primaryButtonInteractionSource = remember(primaryAction) { MutableInteractionSource() }
    if (!hasMultipleProtocols) {
        OutlinedButton(
            onClick = onToggleConnection,
            enabled = activeProfile != null,
            modifier = Modifier.fillMaxWidth().testTag("home_connect_button"),
            interactionSource = primaryButtonInteractionSource,
            border = primaryButtonBorder,
            colors = primaryButtonColors,
        ) {
            Icon(primaryIcon, contentDescription = null)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                if (autoConnectRunning) {
                    stringResource(R.string.disconnect)
                } else {
                    homeConnectionLabel(state.connection.state, state.reconnectRequired)
                },
            )
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onToggleConnection,
            enabled = activeProfile != null,
            modifier =
                Modifier
                    .weight(1f)
                    .height(HomePrimaryActionHeight)
                    .testTag("home_connect_button"),
            interactionSource = primaryButtonInteractionSource,
            border = primaryButtonBorder,
            colors = primaryButtonColors,
        ) {
            Icon(primaryIcon, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (autoConnectRunning) {
                    stringResource(R.string.disconnect)
                } else {
                    homeConnectionLabel(state.connection.state, state.reconnectRequired)
                },
            )
        }
        OutlinedButton(
            onClick = onAutoConnect,
            enabled = autoConnectEnabled,
            modifier =
                Modifier
                    .weight(1f)
                    .height(HomePrimaryActionHeight)
                    .testTag("home_auto_connect_button"),
            border = BorderStroke(1.dp, autoConnectColor.copy(alpha = 0.36f)),
            colors =
                ButtonDefaults.outlinedButtonColors(
                    contentColor = autoConnectColor,
                    containerColor = autoConnectColor.copy(alpha = 0.06f),
                    disabledContentColor = autoConnectColor,
                    disabledContainerColor = autoConnectColor.copy(alpha = 0.04f),
                ),
        ) {
            Icon(
                Icons.Outlined.Speed,
                contentDescription = null,
                tint = autoConnectColor,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.auto_connect),
                color = autoConnectColor,
            )
        }
    }
}

@Composable
internal fun SmartStartRefreshReminderCard(
    onRefresh: () -> Unit,
    onLater: () -> Unit,
) {
    FoxholeCard(
        borderColor = FoxholeInfoAccent.copy(alpha = 0.34f),
        containerColor = FoxholeInfoAccent.copy(alpha = 0.06f),
        modifier = Modifier.testTag("smart_start_refresh_reminder"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.smart_start_refresh_reminder_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onLater) {
                    Text(stringResource(R.string.later))
                }
                OutlinedButton(
                    onClick = onRefresh,
                    border = BorderStroke(1.dp, FoxholeInfoAccent.copy(alpha = 0.42f)),
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = FoxholeInfoAccent,
                            containerColor = FoxholeInfoAccent.copy(alpha = 0.06f),
                        ),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.refresh))
                }
            }
        }
    }
}

@Composable
internal fun HomeAutoConnectStatusLine(
    state: AutoConnectUiState,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val option =
        state.options.firstOrNull { it.optionId == state.currentOptionId }
            ?: state.options.firstOrNull()
            ?: return
    val tone = FoxholeInfoAccent
    Row(
        modifier =
            modifier
                .graphicsLayer { translationX = 3f }
                .testTag("home_auto_connect_status_line"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeAnalysisSignal(
            tint = tone,
            modifier = Modifier,
        )
        HomeAnalysisStatusText(
            protocolLabel = protocolHintChipLabel(option.protocolHint),
            tint = tone,
            textStyle = textStyle,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
internal fun HomeAnalysisSignal(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    HomeJellyTriangleLoader(
        modifier = modifier,
        color = tint,
        indicatorSize = HomeTriangleIndicatorSize,
    )
}

@Composable
internal fun HomeJellyTriangleLoader(
    modifier: Modifier = Modifier,
    color: Color,
    indicatorSize: Dp = 15.dp,
) {
    val infinite = rememberInfiniteTransition(label = "home_jelly_triangle")
    val travelerProgress =
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation =
                        keyframes {
                            durationMillis = 1750
                            0f at 0
                            1f / 3f at 583
                            2f / 3f at 1166
                            1f at 1750
                        },
                    repeatMode = RepeatMode.Restart,
                ),
            label = "home_jelly_triangle_progress",
        )
    val topScale =
        animateJellyDotScale(
            infinite = infinite,
            label = "home_jelly_triangle_top",
            fastForwardMillis = 0,
        )
    val rightScale =
        animateJellyDotScale(
            infinite = infinite,
            label = "home_jelly_triangle_right",
            fastForwardMillis = 1167,
        )
    val leftScale =
        animateJellyDotScale(
            infinite = infinite,
            label = "home_jelly_triangle_left",
            fastForwardMillis = 583,
        )
    Canvas(
        modifier = modifier.size(indicatorSize),
    ) {
        val canvasSize = size.minDimension
        val anchors = homeTriangleAnchors(canvasSize)
        val dotRadius = canvasSize * 0.16f
        drawCircle(
            color = color,
            radius = dotRadius * topScale,
            center = anchors[0],
        )
        drawCircle(
            color = color,
            radius = dotRadius * rightScale,
            center = anchors[2],
        )
        drawCircle(
            color = color,
            radius = dotRadius * leftScale,
            center = anchors[1],
        )
        drawCircle(
            color = color,
            radius = dotRadius,
            center = triangleTravelerCenter(progress = travelerProgress.value, anchors = anchors),
        )
    }
}

internal fun homeTriangleAnchors(sizePx: Float): List<Offset> =
    listOf(
        Offset(x = sizePx * 0.465f, y = sizePx * 0.225f),
        Offset(x = sizePx * 0.165f, y = sizePx * 0.775f),
        Offset(x = sizePx * 0.835f, y = sizePx * 0.775f),
    )

internal fun triangleTravelerCenter(
    progress: Float,
    anchors: List<Offset>,
): Offset {
    val normalizedProgress = progress.coerceIn(0f, 1f)
    return when {
        normalizedProgress <= (1f / 3f) ->
            lerpOffset(anchors[0], anchors[2], normalizedProgress * 3f)
        normalizedProgress <= (2f / 3f) ->
            lerpOffset(anchors[2], anchors[1], (normalizedProgress - (1f / 3f)) * 3f)
        else ->
            lerpOffset(anchors[1], anchors[0], (normalizedProgress - (2f / 3f)) * 3f)
    }
}

internal fun lerpOffset(
    start: Offset,
    end: Offset,
    fraction: Float,
): Offset {
    val safeFraction = fraction.coerceIn(0f, 1f)
    return Offset(
        x = start.x + ((end.x - start.x) * safeFraction),
        y = start.y + ((end.y - start.y) * safeFraction),
    )
}

@Composable
internal fun animateJellyDotScale(
    infinite: InfiniteTransition,
    label: String,
    fastForwardMillis: Int,
): Float =
    infinite.animateFloat(
        initialValue = 1.5f,
        targetValue = 1.5f,
        animationSpec =
            infiniteRepeatable(
                animation =
                    keyframes {
                        durationMillis = 1750
                        1.5f at 0
                        1f at 350
                        1f at 1225
                        1.5f at 1750
                    },
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(fastForwardMillis, StartOffsetType.FastForward),
            ),
        label = label,
    ).value

@Composable
internal fun ProxyCredentialRow(
    label: String,
    value: String,
    onEdit: (() -> Unit)? = null,
    editContentDescription: String? = null,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        onEdit?.let { edit ->
            IconButton(
                onClick = edit,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = editContentDescription,
                )
            }
        }
        IconButton(
            onClick = onCopy,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(R.string.copy_to_clipboard),
            )
        }
    }
}
