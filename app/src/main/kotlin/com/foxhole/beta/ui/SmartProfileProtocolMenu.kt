package com.foxhole.beta.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.profile.MultiProtocolProfileSupport
import kotlin.math.max

@Composable
internal fun SmartProfileAutoConnectMenu(
    profile: Profile,
    excludedOptionIds: Set<String>,
    onUpdateExcludedOptionIds: (Set<String>) -> Unit,
    latencyByOptionId: Map<String, Long> = emptyMap(),
    unavailableOptionIds: Set<String> = emptySet(),
    latencyUnavailableOptionIds: Set<String> = emptySet(),
    serverPingByOptionId: Map<String, Long> = emptyMap(),
    serverPingUnavailableOptionIds: Set<String> = emptySet(),
    metricsUpdatedAtByOptionId: Map<String, Long> = emptyMap(),
    metricsRefreshing: Boolean = false,
    recommendedOptionId: String? = null,
    activeOptionId: String? = profile.selectedProtocolOptionId ?: MultiProtocolProfileSupport.selectedOption(profile)?.id,
    onRefreshMetrics: (() -> Unit)? = null,
    onCancelRefreshMetrics: (() -> Unit)? = null,
    showLatency: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true,
    actionIconSize: Dp? = null,
    showMetricsTable: Boolean = true,
) {
    val options = MultiProtocolProfileSupport.supportedOptions(profile)
    if (options.size < 2) {
        return
    }
    val menuLayout = resolveSmartStartProtocolMenuLayout(showMetricsTable = showMetricsTable)
    var expanded by rememberSaveable(profile.id) { mutableStateOf(false) }
    val minMenuWidth =
        when {
            menuLayout.showDetailedMetrics && compact -> 334.dp
            menuLayout.showDetailedMetrics -> 356.dp
            compact -> 304.dp
            else -> 304.dp
        }
    val maxMenuWidth =
        when {
            menuLayout.showDetailedMetrics && compact -> 348.dp
            menuLayout.showDetailedMetrics -> 376.dp
            compact -> 328.dp
            else -> 336.dp
        }
    Box {
        Surface(
            modifier =
                Modifier
                    .size(if (compact) 30.dp else 36.dp)
                    .clip(CircleShape)
                    .clickable(enabled = enabled) { expanded = true }
                    .testTag("smart_profile_auto_connect_menu_action"),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
            border = BorderStroke(1.dp, FoxholeInfoAccent.copy(alpha = 0.18f)),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.smart_profile_menu_title),
                    modifier = Modifier.size(actionIconSize ?: if (compact) 16.dp else 20.dp),
                    tint = FoxholeInfoAccent,
                )
            }
        }
        FoxholeDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier =
                Modifier
                    .testTag("smart_profile_auto_connect_menu")
                    .widthIn(min = minMenuWidth, max = maxMenuWidth),
            offset = DpOffset(x = 0.dp, y = if (compact) (-6).dp else 0.dp),
        ) {
            Column(
                modifier = Modifier.padding(if (compact) 0.dp else 2.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 2.dp),
            ) {
                if (menuLayout.showHeader) {
                    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 5.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (compact) 3.dp else 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                modifier = Modifier.size(if (compact) 14.dp else 16.dp),
                                tint = FoxholeInfoAccent,
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.smart_profile_menu_title),
                                    style =
                                        if (compact) {
                                            MaterialTheme.typography.bodySmall
                                        } else {
                                            MaterialTheme.typography.labelMedium
                                        },
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (compact) 3 else 2,
                                    overflow = TextOverflow.Clip,
                                )
                                Text(
                                    text = stringResource(R.string.smart_profile_metrics_refresh_hint),
                                    style =
                                        MaterialTheme.typography.labelSmall.copy(
                                            fontSize = if (compact) 10.sp else 11.sp,
                                            lineHeight = if (compact) 11.sp else 12.sp,
                                        ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            SmartProfileMetricsRefreshStatus(
                                updatedAt = metricsUpdatedAtByOptionId.values.maxOrNull(),
                                refreshing = metricsRefreshing,
                                onRefreshMetrics = onRefreshMetrics,
                                onCancelRefreshMetrics = onCancelRefreshMetrics,
                                compact = compact,
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = if (compact) 6.dp else 10.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f),
                        )
                        if (menuLayout.showDetailedMetrics) {
                            SmartProfileSelectedProtocolSummary(
                                options = options,
                                activeOptionId = activeOptionId,
                                recommendedOptionId = recommendedOptionId,
                                latencyByOptionId = latencyByOptionId,
                                unavailableOptionIds = unavailableOptionIds,
                                latencyUnavailableOptionIds = latencyUnavailableOptionIds,
                                compact = compact,
                            )
                            SmartProfileProtocolMenuHeader(compact = compact)
                        }
                    }
                }
                options.forEach { option ->
                    val included = option.id !in excludedOptionIds
                    val includedCount = options.count { candidate -> candidate.id !in excludedOptionIds }
                    val recommended = option.id == recommendedOptionId
                    val active = option.id == activeOptionId
                    val latencyMs = latencyByOptionId[option.id]
                    val latencyDown = option.id in unavailableOptionIds
                    val latencyUnavailable = option.id in latencyUnavailableOptionIds
                    val activeAccent =
                        smartProfileCurrentProtocolAccent(
                            latencyMs = latencyMs,
                            down = latencyDown,
                            unavailable = latencyUnavailable,
                        )
                    FoxholeDropdownItem(
                        onClick = {
                            val nextExcluded =
                                if (included) {
                                    if (includedCount <= 1) {
                                        null
                                    } else {
                                        excludedOptionIds + option.id
                                    }
                                } else {
                                    excludedOptionIds - option.id
                                }
                            nextExcluded?.let(onUpdateExcludedOptionIds)
                        },
                        selected = active || recommended,
                        highlightSelected = active || recommended,
                        accentColor =
                            when {
                                recommended -> FoxholePositiveAccent
                                active -> activeAccent
                                else -> FoxholePositiveAccent
                            },
                        selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                        minHeight = if (menuLayout.showDetailedMetrics) 32.dp else 42.dp,
                        contentPadding =
                            PaddingValues(
                                horizontal = SmartProfileMenuHorizontalPadding,
                                vertical = 0.dp,
                            ),
                    ) {
                        if (menuLayout.showDetailedMetrics) {
                            SmartProfileProtocolMenuRow(
                                option = option,
                                compact = true,
                                included = included,
                                active = active,
                                recommended = recommended,
                                showSelectionBadge = false,
                                serverPingMs = serverPingByOptionId[option.id],
                                serverPingUnavailable =
                                    option.id in serverPingUnavailableOptionIds &&
                                        option.id !in serverPingByOptionId,
                                latencyMs = latencyMs,
                                latencyDown = latencyDown,
                                latencyUnavailable = latencyUnavailable,
                                transportKnown =
                                    option.id in metricsUpdatedAtByOptionId ||
                                        option.id in latencyByOptionId ||
                                        option.id in serverPingByOptionId ||
                                        option.id in serverPingUnavailableOptionIds ||
                                        option.id in unavailableOptionIds ||
                                        option.id in latencyUnavailableOptionIds,
                            )
                        } else {
                            SmartProfileProtocolSimpleMenuRow(
                                option = option,
                                compact = true,
                                included = included,
                                active = active,
                                recommended = recommended,
                                showSelectionBadge = true,
                                latencyMs = latencyMs,
                                latencyDown = latencyDown,
                                latencyUnavailable = latencyUnavailable,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartProfileMetricsRefreshStatus(
    updatedAt: Long?,
    refreshing: Boolean,
    onRefreshMetrics: (() -> Unit)?,
    onCancelRefreshMetrics: (() -> Unit)?,
    compact: Boolean,
) {
    Column(
        modifier = Modifier.width(if (compact) 96.dp else 110.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        onRefreshMetrics?.let { refreshMetrics ->
            IconButton(
                onClick = {
                    if (refreshing) {
                        onCancelRefreshMetrics?.invoke()
                    } else {
                        refreshMetrics()
                    }
                },
                enabled = !refreshing || onCancelRefreshMetrics != null,
                modifier = Modifier.size(if (compact) 26.dp else 30.dp),
            ) {
                Icon(
                    imageVector = if (refreshing) Icons.Outlined.Close else Icons.Outlined.Refresh,
                    contentDescription =
                        stringResource(
                            if (refreshing) {
                                R.string.smart_profile_metrics_cancel_action
                            } else {
                                R.string.smart_profile_metrics_refresh_action
                            },
                        ),
                    modifier = Modifier.size(if (compact) 17.dp else 19.dp),
                    tint =
                        if (refreshing) {
                            MaterialTheme.colorScheme.error
                        } else {
                            FoxholeInfoAccent
                        },
                )
            }
        }
        when {
            refreshing ->
                FoxholeSkeletonBlock(
                    modifier =
                        Modifier
                            .width(if (compact) 74.dp else 88.dp)
                            .height(if (compact) 10.dp else 11.dp),
                )
            updatedAt != null ->
                Text(
                    text = stringResource(R.string.smart_profile_metrics_last_updated, formatSmartMetricsUpdatedAgo(updatedAt)),
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontSize = if (compact) 9.sp else 10.sp,
                            lineHeight = if (compact) 10.sp else 11.sp,
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.74f),
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    overflow = TextOverflow.Ellipsis,
                )
            else -> Spacer(modifier = Modifier.height(if (compact) 10.dp else 11.dp))
        }
    }
}

@Composable
private fun SmartProfileProtocolMenuHeader(compact: Boolean) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(if (compact) SmartProfileProtocolCompactHeaderHeight else SmartProfileProtocolHeaderHeight)
                .padding(horizontal = SmartProfileMenuHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmartProfileProtocolHeaderText(
            text = stringResource(R.string.smart_profile_menu_protocol_column),
            modifier = Modifier.width(SmartProfileProtocolColumnWidth),
            textAlign = TextAlign.Start,
        )
        SmartProfileProtocolMenuDivider(
            height = if (compact) SmartProfileProtocolCompactHeaderHeight else SmartProfileProtocolHeaderHeight,
        )
        SmartProfileProtocolHeaderText(
            text = stringResource(R.string.smart_profile_menu_server_ping_column),
            modifier = Modifier.width(SmartProfileMetricColumnWidth),
        )
        SmartProfileProtocolMenuDivider(
            height = if (compact) SmartProfileProtocolCompactHeaderHeight else SmartProfileProtocolHeaderHeight,
        )
        SmartProfileProtocolHeaderText(
            text = stringResource(R.string.smart_profile_menu_latency_column),
            modifier = Modifier.width(SmartProfileMetricColumnWidth),
        )
        SmartProfileProtocolMenuDivider(
            height = if (compact) SmartProfileProtocolCompactHeaderHeight else SmartProfileProtocolHeaderHeight,
        )
        SmartProfileProtocolHeaderText(
            text = stringResource(R.string.smart_profile_menu_on_column),
            modifier = Modifier.width(SmartProfileOnColumnWidth),
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = SmartProfileMenuHorizontalPadding),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
    )
}

private fun smartProfileCurrentProtocolAccent(
    latencyMs: Long?,
    down: Boolean,
    unavailable: Boolean,
): Color =
    when {
        down || unavailable || (latencyMs != null && latencyMs > 520L) -> SmartProfileCurrentDangerAccent
        else -> SmartProfileCurrentWarningAccent
    }

@Composable
private fun SmartProfileSelectedProtocolSummary(
    options: List<ProfileProtocolOption>,
    activeOptionId: String?,
    recommendedOptionId: String?,
    latencyByOptionId: Map<String, Long>,
    unavailableOptionIds: Set<String>,
    latencyUnavailableOptionIds: Set<String>,
    compact: Boolean,
) {
    val activeOption =
        options.firstOrNull { option -> option.id == activeOptionId }
            ?: options.firstOrNull { option -> option.id == recommendedOptionId }
            ?: options.firstOrNull()
    if (activeOption == null) {
        return
    }
    val latencyMs = latencyByOptionId[activeOption.id]
    val down = activeOption.id in unavailableOptionIds
    val unavailable = activeOption.id in latencyUnavailableOptionIds || (latencyMs == null && !down)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = if (compact) 4.dp else 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.smart_profile_menu_selected_label),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (compact) 10.sp else 11.sp,
                    lineHeight = if (compact) 11.sp else 12.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        ProtocolSelectorLabel(
            option = activeOption,
            modifier = Modifier.weight(1f),
            compact = compact,
        )
        SmartProfileStatusPill(
            presentation =
                resolveSmartStartProtocolPresentation(
                    included = true,
                    recommended = activeOption.id == recommendedOptionId,
                    latencyMs = latencyMs,
                    latencyDown = down,
                    latencyUnavailable = unavailable,
                ),
            compact = compact,
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = if (compact) 6.dp else 10.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
    )
}

@Composable
private fun SmartProfileProtocolHeaderText(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
) {
    Text(
        text = text,
        modifier = modifier,
        style =
            MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
    )
}

@Composable
private fun SmartProfileProtocolMenuRow(
    option: ProfileProtocolOption,
    compact: Boolean,
    included: Boolean,
    active: Boolean,
    recommended: Boolean,
    showSelectionBadge: Boolean,
    serverPingMs: Long?,
    serverPingUnavailable: Boolean,
    latencyMs: Long?,
    latencyDown: Boolean,
    latencyUnavailable: Boolean,
    transportKnown: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(if (compact) SmartProfileProtocolCompactRowHeight else SmartProfileProtocolRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmartProfileProtocolCell(
            option = option,
            compact = compact,
            enabled = included,
            active = active,
            recommended = recommended,
            showSelectionBadge = showSelectionBadge,
            transportKnown = transportKnown,
            modifier = Modifier.width(SmartProfileProtocolColumnWidth),
        )
        SmartProfileProtocolMenuDivider(
            height = if (compact) SmartProfileProtocolCompactRowHeight else SmartProfileProtocolRowHeight,
        )
        SmartProfileMetricCell(
            latencyMs = serverPingMs,
            unavailable = serverPingUnavailable || serverPingMs == null,
            modifier = Modifier.width(SmartProfileMetricColumnWidth),
        )
        SmartProfileProtocolMenuDivider(
            height = if (compact) SmartProfileProtocolCompactRowHeight else SmartProfileProtocolRowHeight,
        )
        SmartProfileMetricCell(
            latencyMs = latencyMs,
            down = latencyDown,
            unavailable = latencyUnavailable || latencyMs == null,
            modifier = Modifier.width(SmartProfileMetricColumnWidth),
        )
        SmartProfileProtocolMenuDivider(
            height = if (compact) SmartProfileProtocolCompactRowHeight else SmartProfileProtocolRowHeight,
        )
        Box(
            modifier = Modifier.width(SmartProfileOnColumnWidth),
            contentAlignment = Alignment.Center,
        ) {
            SmartProfileOnToggle(included = included, compact = compact)
        }
    }
}

@Composable
private fun SmartProfileProtocolSimpleMenuRow(
    option: ProfileProtocolOption,
    compact: Boolean,
    included: Boolean,
    active: Boolean,
    recommended: Boolean,
    showSelectionBadge: Boolean,
    latencyMs: Long?,
    latencyDown: Boolean,
    latencyUnavailable: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(if (compact) SmartProfileProtocolSimpleCompactRowHeight else SmartProfileProtocolSimpleRowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SmartProfileProtocolCell(
            option = option,
            compact = compact,
            enabled = included,
            active = active,
            recommended = recommended,
            showSelectionBadge = showSelectionBadge,
            transportKnown = false,
            modifier = Modifier.weight(1f),
        )
        SmartProfileStatusPill(
            presentation =
                resolveSmartStartProtocolPresentation(
                    included = included,
                    recommended = recommended,
                    latencyMs = latencyMs,
                    latencyDown = latencyDown,
                    latencyUnavailable = latencyUnavailable,
                ),
            compact = compact,
        )
        SmartProfileOnToggle(included = included, compact = compact)
    }
}

@Composable
private fun SmartProfileProtocolCell(
    option: ProfileProtocolOption,
    compact: Boolean,
    enabled: Boolean,
    active: Boolean,
    recommended: Boolean,
    showSelectionBadge: Boolean,
    transportKnown: Boolean,
    modifier: Modifier = Modifier,
) {
    val contentColor =
        if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Box(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProtocolMarkIcon(protocol = option.protocolHint, compact = compact)
            Text(
                text = protocolDisplayLabel(option.protocolHint),
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = if (compact) 13.sp else 14.sp,
                        lineHeight = if (compact) 14.sp else 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                color = contentColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (transportKnown) {
                SmartProfileTransportBadge(
                    transport = protocolTransportBadge(option.protocolHint),
                    modifier = Modifier.offset(y = if (compact) (-3).dp else (-2).dp),
                )
            }
            if (showSelectionBadge && (active || recommended)) {
                SmartProfileSelectionBadge(
                    active = active,
                    compact = compact,
                    modifier = Modifier.offset(y = if (compact) (-4).dp else (-3).dp),
                )
            }
        }
    }
}

@Composable
private fun SmartProfileSelectionBadge(
    active: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (active) FoxholeInfoAccent else FoxholePositiveAccent
    val text =
        stringResource(
            if (active) {
                R.string.smart_profile_menu_active_badge
            } else {
                R.string.smart_profile_menu_recommended_badge
            },
        )
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.30f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (compact) 7.sp else 8.sp,
                    lineHeight = if (compact) 8.sp else 9.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun SmartProfileOnToggle(
    included: Boolean,
    compact: Boolean,
) {
    if (included) {
        Surface(
            modifier = Modifier.size(if (compact) 17.dp else 18.dp),
            shape = CircleShape,
            color = FoxholePositiveAccent.copy(alpha = 0.16f),
            border = BorderStroke(1.dp, FoxholePositiveAccent.copy(alpha = 0.48f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 12.dp else 13.dp),
                    tint = FoxholePositiveAccent,
                )
            }
        }
    } else {
        Surface(
            modifier = Modifier.size(if (compact) 14.dp else 15.dp),
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)),
            content = {},
        )
    }
}

@Composable
private fun SmartProfileTransportBadge(
    transport: SmartProfileTransport,
    modifier: Modifier = Modifier,
) {
    val color =
        when (transport) {
            SmartProfileTransport.TCP -> SmartProfileTcpAccent
            SmartProfileTransport.UDP -> SmartProfileUdpAccent
        }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.44f)),
    ) {
        Text(
            text = transport.name,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = 7.sp,
                    lineHeight = 8.sp,
                    fontWeight = FontWeight.Bold,
                ),
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun SmartProfileMetricCell(
    modifier: Modifier = Modifier,
    latencyMs: Long?,
    down: Boolean = false,
    unavailable: Boolean = false,
) {
    val text =
        when {
            down -> stringResource(R.string.latency_pill_down)
            latencyMs != null -> stringResource(R.string.latency_pill_value, latencyMs)
            unavailable -> stringResource(R.string.smart_profile_metric_unavailable)
            else -> stringResource(R.string.smart_profile_metric_unavailable)
        }
    val tone =
        when {
            down -> SmartProfileMetricTone.DANGER
            latencyMs == null -> SmartProfileMetricTone.NEUTRAL
            latencyMs <= 180L -> SmartProfileMetricTone.POSITIVE
            latencyMs <= 520L -> SmartProfileMetricTone.WARNING
            else -> SmartProfileMetricTone.DANGER
        }
    SmartProfileMetricPill(
        text = text,
        tone = tone,
        roundUnavailable = latencyMs == null && !down,
        modifier = modifier,
    )
}

@Composable
private fun SmartProfileStatusPill(
    modifier: Modifier = Modifier,
    presentation: SmartStartProtocolPresentation,
    compact: Boolean,
) {
    SmartProfileMetricPill(
        text = smartStartProtocolPresentationText(presentation),
        tone = smartStartProtocolPresentationTone(presentation),
        compact = compact,
        modifier = modifier,
    )
}

@Composable
private fun smartStartProtocolPresentationText(presentation: SmartStartProtocolPresentation): String =
    when (presentation.status) {
        SmartStartProtocolStatus.RECOMMENDED -> stringResource(R.string.smart_start_protocol_status_recommended)
        SmartStartProtocolStatus.SLOW -> stringResource(R.string.smart_start_protocol_status_slow)
        SmartStartProtocolStatus.RECENTLY_FAILED -> stringResource(R.string.smart_start_protocol_status_recently_failed)
        SmartStartProtocolStatus.NO_DATA -> stringResource(R.string.smart_start_protocol_status_no_data)
        SmartStartProtocolStatus.DISABLED ->
            stringResource(
                R.string.smart_start_protocol_status_disabled,
                stringResource(
                    when (presentation.disabledReason) {
                        SmartStartProtocolDisabledReason.MANUAL_OFF,
                        null,
                        -> R.string.smart_start_protocol_disabled_reason_manual_off
                    },
                ),
            )
    }

private fun smartStartProtocolPresentationTone(presentation: SmartStartProtocolPresentation): SmartProfileMetricTone =
    when (presentation.status) {
        SmartStartProtocolStatus.RECOMMENDED -> SmartProfileMetricTone.POSITIVE
        SmartStartProtocolStatus.SLOW -> SmartProfileMetricTone.WARNING
        SmartStartProtocolStatus.RECENTLY_FAILED -> SmartProfileMetricTone.DANGER
        SmartStartProtocolStatus.NO_DATA,
        SmartStartProtocolStatus.DISABLED,
        -> SmartProfileMetricTone.NEUTRAL
    }

@Composable
private fun SmartProfileMetricPill(
    text: String,
    tone: SmartProfileMetricTone,
    modifier: Modifier = Modifier,
    roundUnavailable: Boolean = false,
    compact: Boolean = true,
) {
    val color =
        when (tone) {
            SmartProfileMetricTone.POSITIVE -> FoxholePositiveAccent
            SmartProfileMetricTone.WARNING -> Color(0xFFE28131)
            SmartProfileMetricTone.DANGER -> Color(0xFFC95353)
            SmartProfileMetricTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier =
                if (roundUnavailable) {
                    Modifier.size(SmartProfileMetricUnavailableSize)
                } else {
                    Modifier
                },
            shape = if (roundUnavailable) CircleShape else MaterialTheme.shapes.medium,
            color = color.copy(alpha = if (tone == SmartProfileMetricTone.NEUTRAL) 0.10f else 0.16f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.32f)),
        ) {
            val textStyle =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (compact) 9.sp else 10.sp,
                    lineHeight = if (compact) 10.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            if (roundUnavailable) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = text,
                        style = textStyle,
                        color = color,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 2.dp else 3.dp),
                    style = textStyle,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SmartProfileProtocolMenuDivider(height: Dp) {
    Spacer(
        modifier =
            Modifier
                .height(height)
                .width(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)),
    )
}

private fun protocolTransportBadge(protocol: ProtocolHint): SmartProfileTransport =
    when (protocol) {
        ProtocolHint.HYSTERIA2,
        ProtocolHint.WIREGUARD,
        -> SmartProfileTransport.UDP
        ProtocolHint.VLESS,
        ProtocolHint.TROJAN,
        ProtocolHint.SHADOWSOCKS,
        ProtocolHint.VMESS,
        ProtocolHint.OUTLINE,
        ProtocolHint.SING_BOX,
        ProtocolHint.UNKNOWN,
        -> SmartProfileTransport.TCP
    }

private enum class SmartProfileTransport {
    TCP,
    UDP,
}

private enum class SmartProfileMetricTone {
    POSITIVE,
    WARNING,
    DANGER,
    NEUTRAL,
}

private val SmartProfileMenuHorizontalPadding = 8.dp
private val SmartProfileProtocolColumnWidth = 154.dp
private val SmartProfileOnColumnWidth = 32.dp
private val SmartProfileMetricColumnWidth = 62.dp
private val SmartProfileProtocolCompactHeaderHeight = 32.dp
private val SmartProfileProtocolHeaderHeight = 36.dp
private val SmartProfileProtocolCompactRowHeight = 34.dp
private val SmartProfileProtocolRowHeight = 38.dp
private val SmartProfileProtocolSimpleCompactRowHeight = 38.dp
private val SmartProfileProtocolSimpleRowHeight = 42.dp
private val SmartProfileMetricUnavailableSize = 18.dp
private val SmartProfileTcpAccent = Color(0xFF3F7DD9)
private val SmartProfileUdpAccent = Color(0xFFE28131)
private val SmartProfileCurrentWarningAccent = Color(0xFFE28131)
private val SmartProfileCurrentDangerAccent = Color(0xFFC95353)

@Composable
private fun formatSmartMetricsUpdatedAgo(updatedAt: Long): String {
    val elapsedMs = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)
    val minute = 60_000L
    val hour = 60L * minute
    val day = 24L * hour
    val week = 7L * day
    return when {
        elapsedMs < hour ->
            stringResource(R.string.smart_profile_metrics_ago_minutes, max(1L, elapsedMs / minute))
        elapsedMs < day ->
            stringResource(R.string.smart_profile_metrics_ago_hours, max(1L, elapsedMs / hour))
        elapsedMs < week ->
            stringResource(R.string.smart_profile_metrics_ago_days, max(1L, elapsedMs / day))
        else ->
            stringResource(R.string.smart_profile_metrics_ago_weeks, max(1L, elapsedMs / week))
    }
}
