package com.foxhole.beta.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
    recommendedOptionIds: Set<String> = recommendedOptionId?.let(::setOf).orEmpty(),
    activeOptionId: String? = profile.selectedProtocolOptionId ?: MultiProtocolProfileSupport.selectedOption(profile)?.id,
    onRefreshMetrics: (() -> Unit)? = null,
    onCancelRefreshMetrics: (() -> Unit)? = null,
    refreshWarningRequired: Boolean = false,
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
    var showRefreshWarning by rememberSaveable(profile.id) { mutableStateOf(false) }
    val requestRefreshMetrics: () -> Unit = {
        if (refreshWarningRequired) {
            showRefreshWarning = true
        } else {
            onRefreshMetrics?.invoke()
        }
    }
    Box {
        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
        val menuWidth =
            rememberSmartProfileMenuWidth(
                options = options,
                menuLayout = menuLayout,
                excludedOptionIds = excludedOptionIds,
                latencyByOptionId = latencyByOptionId,
                unavailableOptionIds = unavailableOptionIds,
                latencyUnavailableOptionIds = latencyUnavailableOptionIds,
                serverPingByOptionId = serverPingByOptionId,
                serverPingUnavailableOptionIds = serverPingUnavailableOptionIds,
                recommendedOptionId = recommendedOptionId,
                recommendedOptionIds = recommendedOptionIds,
                activeOptionId = activeOptionId,
                compact = compact,
                maxWidth = (screenWidth - ScreenHorizontalPadding - ScreenHorizontalPadding).coerceAtLeast(SmartProfileMenuCompactMinWidth),
                showRefreshHeader = menuLayout.showHeader || onRefreshMetrics != null,
            )
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
                    .width(menuWidth),
            offset = DpOffset(x = 0.dp, y = if (compact) (-6).dp else 0.dp),
        ) {
            SmartProfileProtocolMenuContent(
                options = options,
                menuLayout = menuLayout,
                excludedOptionIds = excludedOptionIds,
                onUpdateExcludedOptionIds = onUpdateExcludedOptionIds,
                latencyByOptionId = latencyByOptionId,
                unavailableOptionIds = unavailableOptionIds,
                latencyUnavailableOptionIds = latencyUnavailableOptionIds,
                serverPingByOptionId = serverPingByOptionId,
                serverPingUnavailableOptionIds = serverPingUnavailableOptionIds,
                metricsUpdatedAtByOptionId = metricsUpdatedAtByOptionId,
                metricsRefreshing = metricsRefreshing,
                recommendedOptionId = recommendedOptionId,
                recommendedOptionIds = recommendedOptionIds,
                activeOptionId = activeOptionId,
                onRefreshMetrics = onRefreshMetrics?.let { requestRefreshMetrics },
                onCancelRefreshMetrics = onCancelRefreshMetrics,
                compact = compact,
            )
        }
    }
    if (showRefreshWarning) {
        ConfirmDialog(
            title = stringResource(R.string.smart_profile_metrics_refresh_confirm_title),
            body = stringResource(R.string.smart_profile_metrics_refresh_confirm_body),
            confirmLabel = stringResource(R.string.refresh),
            icon = Icons.Outlined.Refresh,
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { showRefreshWarning = false },
            onConfirm = {
                showRefreshWarning = false
                onRefreshMetrics?.invoke()
            },
        )
    }
}

@Composable
private fun rememberSmartProfileMenuWidth(
    options: List<ProfileProtocolOption>,
    menuLayout: SmartStartProtocolMenuLayout,
    excludedOptionIds: Set<String>,
    latencyByOptionId: Map<String, Long>,
    unavailableOptionIds: Set<String>,
    latencyUnavailableOptionIds: Set<String>,
    serverPingByOptionId: Map<String, Long>,
    serverPingUnavailableOptionIds: Set<String>,
    recommendedOptionId: String?,
    recommendedOptionIds: Set<String>,
    activeOptionId: String?,
    compact: Boolean,
    maxWidth: Dp,
    showRefreshHeader: Boolean,
): Dp {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val title = stringResource(R.string.smart_profile_menu_title)
    val favoriteLegend = stringResource(R.string.smart_profile_legend_favorite)
    val recommendedLegend = stringResource(R.string.smart_profile_legend_reconnect_recommended)
    val serverPingLabel = stringResource(R.string.smart_profile_menu_server_ping_column)
    val vpnLatencyLabel = stringResource(R.string.smart_profile_menu_latency_column)
    val activeLabel = stringResource(R.string.smart_profile_menu_active_badge)
    val recommendedLabel = stringResource(R.string.smart_profile_menu_recommended_badge)
    val unavailableMetric = stringResource(R.string.smart_profile_metric_unavailable)
    val downMetric = stringResource(R.string.latency_pill_down)
    val recommendedStatus = stringResource(R.string.smart_start_protocol_status_recommended)
    val availableStatus = stringResource(R.string.smart_start_protocol_status_available)
    val slowStatus = stringResource(R.string.smart_start_protocol_status_slow)
    val noDataStatus = stringResource(R.string.smart_start_protocol_status_no_data)
    val disabledStatus = stringResource(R.string.smart_start_protocol_status_disabled)
    val fastLabel = stringResource(R.string.latency_quality_fast)
    val normalLabel = stringResource(R.string.latency_quality_normal)
    val slowLabel = stringResource(R.string.latency_quality_slow)
    val verySlowLabel = stringResource(R.string.latency_quality_very_slow)
    val headerStyle =
        MaterialTheme.typography.labelSmall.copy(
            fontSize = if (compact) 10.5.sp else 12.sp,
            lineHeight = if (compact) 11.5.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    val protocolStyle =
        MaterialTheme.typography.labelSmall.copy(
            fontSize = 13.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    val metricLabelStyle =
        MaterialTheme.typography.labelSmall.copy(
            fontSize = 8.6.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.Medium,
        )
    val metricValueStyle =
        MaterialTheme.typography.labelSmall.copy(
            fontSize = 9.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    val legendStyle =
        MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    fun textWidth(text: String, style: androidx.compose.ui.text.TextStyle): Float =
        textMeasurer.measure(
            text = AnnotatedString(text),
            style = style,
        ).size.width.toFloat()
    fun metricText(
        latencyMs: Long?,
        down: Boolean = false,
        unavailable: Boolean = false,
    ): String =
        when {
            down -> downMetric
            latencyMs != null -> "$latencyMs ms"
            unavailable -> unavailableMetric
            else -> unavailableMetric
        }
    fun statusText(
        included: Boolean,
        recommended: Boolean,
        latencyMs: Long?,
        latencyDown: Boolean,
        latencyUnavailable: Boolean,
    ): String {
        val presentation =
            resolveSmartStartProtocolPresentation(
                included = included,
                recommended = recommended,
                latencyMs = latencyMs,
                latencyDown = latencyDown,
                latencyUnavailable = latencyUnavailable,
            )
        return when {
            latencyDown -> downMetric
            latencyMs != null && !latencyUnavailable ->
                when (classifyVpnLatency(latencyMs = latencyMs, failed = false, unavailable = false)) {
                    LatencyQuality.FAST -> fastLabel
                    LatencyQuality.NORMAL -> normalLabel
                    LatencyQuality.SLOW -> slowLabel
                    LatencyQuality.VERY_SLOW -> verySlowLabel
                    LatencyQuality.UNAVAILABLE,
                    LatencyQuality.FAILED,
                    -> presentation.status.label(
                        recommended = recommendedStatus,
                        available = availableStatus,
                        slow = slowStatus,
                        failed = downMetric,
                        noData = noDataStatus,
                        disabled = disabledStatus,
                    )
                }
            else ->
                presentation.status.label(
                    recommended = recommendedStatus,
                    available = availableStatus,
                    slow = slowStatus,
                    failed = downMetric,
                    noData = noDataStatus,
                    disabled = disabledStatus,
                )
        }
    }
    val compactStatusRows = menuLayout.showCompactStatusRows
    val headerTitleWidthPx =
        if (compactStatusRows) {
            title.split(' ').maxOfOrNull { word -> textWidth(word, headerStyle) } ?: textWidth(title, headerStyle)
        } else {
            textWidth(title, headerStyle)
        }
    val headerWidthPx =
        if (showRefreshHeader) {
            headerTitleWidthPx +
                with(density) { SmartProfileMenuRefreshColumnWidth.toPx() + 18.dp.toPx() }
        } else {
            0f
        }
    val legendWidthPx =
        if (menuLayout.showDetailedMetrics) {
            textWidth(favoriteLegend, legendStyle) +
                textWidth(recommendedLegend, legendStyle) +
                with(density) { 68.dp.toPx() }
        } else {
            0f
        }
    val rowWidthPx =
        options.maxOfOrNull { option ->
            val included = option.id !in excludedOptionIds
            val latencyMs = latencyByOptionId[option.id]
            val latencyDown = option.id in unavailableOptionIds
            val latencyUnavailable = option.id in latencyUnavailableOptionIds
            val recommended = option.id in recommendedOptionIds && !latencyDown
            val activeBadgeWidth =
                if (option.id == activeOptionId) {
                    textWidth(activeLabel, metricValueStyle) + with(density) { 18.dp.toPx() }
                } else {
                    0f
                }
            val recommendedBadgeWidth =
                if (recommended) {
                    textWidth(recommendedLabel, metricValueStyle) +
                        with(density) { if (option.id == recommendedOptionId) 24.dp.toPx() else 14.dp.toPx() }
                } else {
                    0f
                }
            val topLineWidth =
                textWidth(protocolDisplayLabel(option.protocolHint), protocolStyle) +
                    activeBadgeWidth +
                    recommendedBadgeWidth +
                    with(density) { 58.dp.toPx() }
            if (menuLayout.showDetailedMetrics) {
                val serverValue = metricText(
                    latencyMs = serverPingByOptionId[option.id],
                    unavailable =
                        option.id in serverPingUnavailableOptionIds &&
                            option.id !in serverPingByOptionId,
                )
                val vpnValue = metricText(
                    latencyMs = latencyMs,
                    down = latencyDown,
                    unavailable = latencyUnavailable,
                )
                val metricGroupWidth =
                    maxOf(
                        textWidth(serverPingLabel, metricLabelStyle) + textWidth(serverValue, metricValueStyle),
                        textWidth(vpnLatencyLabel, metricLabelStyle) + textWidth(vpnValue, metricValueStyle),
                    ) + with(density) { 28.dp.toPx() }
                maxOf(topLineWidth, metricGroupWidth * 2 + with(density) { 8.dp.toPx() })
            } else {
                topLineWidth +
                    textWidth(statusText(included, recommended, latencyMs, latencyDown, latencyUnavailable), metricValueStyle) +
                    with(density) { 40.dp.toPx() }
            }
        } ?: 0f
    val contentWidthPx =
        maxOf(
            headerWidthPx,
            legendWidthPx,
            rowWidthPx,
        ) + with(density) { (SmartProfileMenuHorizontalPadding * 2 + 16.dp).toPx() }
    val measuredWidth = with(density) { contentWidthPx.toDp() }
    val minAllowedWidth =
        if (menuLayout.showDetailedMetrics) {
            SmartProfileMenuDetailedMinWidth
        } else {
            SmartProfileMenuCompactMinWidth
        }
    val maxAllowedWidth = maxWidth.coerceAtLeast(minAllowedWidth).coerceAtMost(SmartProfileMenuMaxWidth)
    return measuredWidth.coerceIn(minAllowedWidth, maxAllowedWidth)
}

private fun SmartStartProtocolStatus.label(
    recommended: String,
    available: String,
    slow: String,
    failed: String,
    noData: String,
    disabled: String,
): String =
    when (this) {
        SmartStartProtocolStatus.RECOMMENDED -> recommended
        SmartStartProtocolStatus.AVAILABLE -> available
        SmartStartProtocolStatus.SLOW -> slow
        SmartStartProtocolStatus.RECENTLY_FAILED -> failed
        SmartStartProtocolStatus.NO_DATA -> noData
        SmartStartProtocolStatus.DISABLED -> disabled
    }

@Composable
private fun SmartProfileProtocolMenuContent(
    options: List<ProfileProtocolOption>,
    menuLayout: SmartStartProtocolMenuLayout,
    excludedOptionIds: Set<String>,
    onUpdateExcludedOptionIds: (Set<String>) -> Unit,
    latencyByOptionId: Map<String, Long>,
    unavailableOptionIds: Set<String>,
    latencyUnavailableOptionIds: Set<String>,
    serverPingByOptionId: Map<String, Long>,
    serverPingUnavailableOptionIds: Set<String>,
    metricsUpdatedAtByOptionId: Map<String, Long>,
    metricsRefreshing: Boolean,
    recommendedOptionId: String?,
    recommendedOptionIds: Set<String>,
    activeOptionId: String?,
    onRefreshMetrics: (() -> Unit)?,
    onCancelRefreshMetrics: (() -> Unit)?,
    compact: Boolean,
) {
    Column(
        modifier = Modifier.padding(if (menuLayout.showDetailedMetrics) 2.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        SmartProfileProtocolMenuHeaderContent(
            menuLayout = menuLayout,
            metricsUpdatedAtByOptionId = metricsUpdatedAtByOptionId,
            metricsRefreshing = metricsRefreshing,
            onRefreshMetrics = onRefreshMetrics,
            onCancelRefreshMetrics = onCancelRefreshMetrics,
            compact = compact,
        )
        val hasMenuHeader = menuLayout.showHeader || onRefreshMetrics != null
        options.forEachIndexed { index, option ->
            val included = option.id !in excludedOptionIds
            val includedCount = options.count { candidate -> candidate.id !in excludedOptionIds }
            val active = option.id == activeOptionId
            val latencyMs = latencyByOptionId[option.id]
            val latencyDown = option.id in unavailableOptionIds
            val latencyUnavailable = option.id in latencyUnavailableOptionIds
            val recommended = option.id in recommendedOptionIds && !latencyDown
            val topRecommended = recommended && option.id == recommendedOptionId
            val includedSelection = included && active
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
                selected = includedSelection,
                highlightSelected = menuLayout.showDetailedMetrics && includedSelection,
                accentColor =
                    when {
                        included && recommended -> FoxholePositiveAccent
                        included && active -> activeAccent
                        else -> FoxholePositiveAccent
                    },
                selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                shape =
                    foxholeDropdownItemShape(
                        index = index,
                        lastIndex = options.lastIndex,
                        hasHeader = hasMenuHeader,
                    ),
                showBorder = index != options.lastIndex,
                minHeight = if (menuLayout.showDetailedMetrics) 56.dp else 42.dp,
                contentPadding =
                    PaddingValues(
                        horizontal = SmartProfileMenuHorizontalPadding,
                        vertical = 0.dp,
                    ),
            ) {
                if (menuLayout.showDetailedMetrics) {
                    SmartProfileProtocolAdaptiveMetricsRow(
                        option = option,
                        compact = true,
                        included = included,
                        active = active,
                        recommended = recommended,
                        topRecommended = topRecommended,
                        serverPingMs = serverPingByOptionId[option.id],
                        serverPingUnavailable =
                            option.id in serverPingUnavailableOptionIds &&
                                option.id !in serverPingByOptionId,
                        metricsEnabled = included,
                        latencyMs = latencyMs,
                        latencyDown = latencyDown,
                        latencyUnavailable = latencyUnavailable,
                        latencyEnabled = included,
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
                        topRecommended = topRecommended,
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

@Composable
private fun SmartProfileProtocolMenuHeaderContent(
    menuLayout: SmartStartProtocolMenuLayout,
    metricsUpdatedAtByOptionId: Map<String, Long>,
    metricsRefreshing: Boolean,
    onRefreshMetrics: (() -> Unit)?,
    onCancelRefreshMetrics: (() -> Unit)?,
    compact: Boolean,
) {
    when {
        menuLayout.showHeader -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SmartProfileProtocolMenuTitleRow(
                    metricsUpdatedAtByOptionId = metricsUpdatedAtByOptionId,
                    metricsRefreshing = metricsRefreshing,
                    onRefreshMetrics = onRefreshMetrics,
                    onCancelRefreshMetrics = onCancelRefreshMetrics,
                    compact = compact,
                )
                SmartProfileMetricsHint(
                    compact = false,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f),
                )
            }
        }
        onRefreshMetrics != null -> {
            SmartProfileProtocolMenuTitleRow(
                metricsUpdatedAtByOptionId = metricsUpdatedAtByOptionId,
                metricsRefreshing = metricsRefreshing,
                onRefreshMetrics = onRefreshMetrics,
                onCancelRefreshMetrics = onCancelRefreshMetrics,
                compact = true,
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
            )
        }
    }
}

@Composable
private fun SmartProfileProtocolMenuTitleRow(
    metricsUpdatedAtByOptionId: Map<String, Long>,
    metricsRefreshing: Boolean,
    onRefreshMetrics: (() -> Unit)?,
    onCancelRefreshMetrics: (() -> Unit)?,
    compact: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = if (compact) 4.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.smart_profile_menu_title),
            modifier = Modifier.weight(1f),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (compact) 10.5.sp else 12.sp,
                    lineHeight = if (compact) 11.5.sp else 13.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        SmartProfileMetricsRefreshStatus(
            updatedAt = metricsUpdatedAtByOptionId.values.maxOrNull(),
            refreshing = metricsRefreshing,
            onRefreshMetrics = onRefreshMetrics,
            onCancelRefreshMetrics = onCancelRefreshMetrics,
            compact = compact,
        )
    }
}

@Composable
private fun SmartProfileMetricsHint(
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val refreshHint =
        stringResource(
            if (compact) {
                R.string.smart_profile_metrics_refresh_compact_hint
            } else {
                R.string.smart_profile_metrics_refresh_hint
            },
        )
    val hintLines = refreshHint.split('\n', limit = 2)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 4.dp),
    ) {
        Text(
            text = hintLines.firstOrNull().orEmpty(),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (compact) 8.4.sp else 10.sp,
                    lineHeight = if (compact) 9.sp else 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (compact) 1 else 2,
            softWrap = !compact,
            overflow = TextOverflow.Clip,
        )
        hintLines.getOrNull(1)?.let { refreshLine ->
            Text(
                text = refreshLine,
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = if (compact) 8.4.sp else 10.sp,
                        lineHeight = if (compact) 9.sp else 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (compact) 1 else 2,
                softWrap = !compact,
                overflow = TextOverflow.Clip,
            )
        }
        if (compact) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmartProfileLegendRow(
                    starCount = 1,
                    label = stringResource(R.string.smart_profile_legend_favorite),
                    compact = true,
                )
                SmartProfileLegendRow(
                    starCount = 2,
                    label = stringResource(R.string.smart_profile_legend_reconnect_recommended),
                    compact = true,
                )
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SmartProfileLegendRow(
                    starCount = 1,
                    label = stringResource(R.string.smart_profile_legend_favorite),
                    compact = false,
                )
                SmartProfileLegendRow(
                    starCount = 2,
                    label = stringResource(R.string.smart_profile_legend_reconnect_recommended),
                    compact = false,
                )
            }
        }
    }
}

@Composable
private fun SmartProfileLegendRow(
    starCount: Int,
    label: String,
    compact: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(starCount) { index ->
                Icon(
                    imageVector = Icons.Outlined.Star,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 7.dp else 9.dp),
                    tint = smartProfileRecommendationStarTint(index),
                )
            }
        }
        Text(
            text = label,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (compact) 8.4.sp else 10.sp,
                    lineHeight = if (compact) 9.sp else 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (compact) 1 else 2,
            softWrap = !compact,
            overflow = TextOverflow.Ellipsis,
        )
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
                    maxLines = if (compact) 2 else 1,
                    textAlign = TextAlign.End,
                    overflow = TextOverflow.Ellipsis,
                )
            else -> Spacer(modifier = Modifier.height(if (compact) 10.dp else 11.dp))
        }
    }
}

private fun smartProfileCurrentProtocolAccent(
    latencyMs: Long?,
    down: Boolean,
    unavailable: Boolean,
): Color =
    when (classifyVpnLatency(latencyMs = latencyMs, failed = down, unavailable = unavailable || latencyMs == null)) {
        LatencyQuality.FAST,
        LatencyQuality.NORMAL,
        LatencyQuality.SLOW,
        -> SmartProfileCurrentWarningAccent
        LatencyQuality.VERY_SLOW,
        LatencyQuality.FAILED,
        LatencyQuality.UNAVAILABLE,
        -> SmartProfileCurrentDangerAccent
    }

@Composable
private fun SmartProfileProtocolAdaptiveMetricsRow(
    option: ProfileProtocolOption,
    compact: Boolean,
    included: Boolean,
    active: Boolean,
    recommended: Boolean,
    topRecommended: Boolean,
    serverPingMs: Long?,
    serverPingUnavailable: Boolean,
    metricsEnabled: Boolean,
    latencyMs: Long?,
    latencyDown: Boolean,
    latencyUnavailable: Boolean,
    latencyEnabled: Boolean,
    transportKnown: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (compact) SmartProfileProtocolAdaptiveCompactRowHeight else SmartProfileProtocolAdaptiveRowHeight)
                .padding(vertical = if (compact) 5.dp else 6.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmartProfileProtocolCell(
                option = option,
                compact = compact,
                enabled = included,
                active = active,
                recommended = recommended,
                topRecommended = topRecommended,
                showSelectionBadge = true,
                transportKnown = transportKnown,
                modifier = Modifier.weight(1f),
            )
            SmartProfileOnToggle(included = included, compact = compact)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmartProfileLabeledMetric(
                label = stringResource(R.string.smart_profile_menu_server_ping_column),
                latencyMs = serverPingMs,
                unavailable = serverPingUnavailable || serverPingMs == null,
                enabled = metricsEnabled,
                compact = compact,
                modifier = Modifier.weight(1f),
            )
            SmartProfileLabeledMetric(
                label = stringResource(R.string.smart_profile_menu_latency_column),
                latencyMs = latencyMs,
                down = latencyDown,
                unavailable = latencyUnavailable || latencyMs == null,
                enabled = latencyEnabled,
                compact = compact,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SmartProfileLabeledMetric(
    label: String,
    latencyMs: Long?,
    down: Boolean = false,
    unavailable: Boolean = false,
    enabled: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (compact) 8.6.sp else 9.sp,
                    lineHeight = if (compact) 9.sp else 10.sp,
                    fontWeight = FontWeight.Medium,
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.78f else 0.48f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        SmartProfileMetricCell(
            latencyMs = latencyMs,
            down = down,
            unavailable = unavailable,
            enabled = enabled,
            modifier = Modifier.widthIn(min = 62.dp, max = 88.dp),
        )
    }
}

@Composable
private fun SmartProfileProtocolSimpleMenuRow(
    option: ProfileProtocolOption,
    compact: Boolean,
    included: Boolean,
    active: Boolean,
    recommended: Boolean,
    topRecommended: Boolean,
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
            topRecommended = topRecommended,
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
            latencyMs = latencyMs,
            latencyDown = latencyDown,
            latencyUnavailable = latencyUnavailable,
            showLatencyDetails = true,
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
    topRecommended: Boolean,
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
            modifier = Modifier.graphicsLayer(alpha = if (enabled) 1f else 0.54f),
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
            if (showSelectionBadge && enabled && (active || recommended)) {
                SmartProfileSelectionBadge(
                    active = active,
                    recommended = recommended,
                    topRecommended = topRecommended,
                    compact = compact,
                    modifier = Modifier.offset(y = if (compact) (-3).dp else (-2).dp),
                )
            }
        }
    }
}

@Composable
private fun SmartProfileSelectionBadge(
    active: Boolean,
    recommended: Boolean,
    topRecommended: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (active) FoxholeInfoAccent else FoxholePositiveAccent
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.30f)),
    ) {
        Row(
            modifier =
                Modifier
                    .padding(horizontal = if (active) 4.dp else 3.dp, vertical = if (active) 1.dp else 2.dp),
            horizontalArrangement = Arrangement.spacedBy(if (active && recommended) 3.dp else 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (active) {
                Text(
                    text = stringResource(R.string.smart_profile_menu_active_badge),
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
            if (recommended) {
                SmartProfileRecommendationStars(topRecommended = topRecommended, compact = compact)
            }
        }
    }
}

@Composable
private fun SmartProfileRecommendationStars(
    topRecommended: Boolean,
    compact: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val starCount = if (topRecommended) 2 else 1
        repeat(starCount) { index ->
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription =
                    if (index == 0) {
                        stringResource(R.string.smart_profile_menu_recommended_badge)
                    } else {
                        null
                    },
                modifier = Modifier.size(if (compact) 9.dp else 10.dp),
                tint = smartProfileRecommendationStarTint(index),
            )
        }
    }
}

private fun smartProfileRecommendationStarTint(index: Int): Color =
    if (index == 1) {
        Color(0xFFE0B84A)
    } else {
        FoxholePositiveAccent
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
        color = Color.Transparent,
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
    enabled: Boolean = true,
) {
    val text =
        when {
            down -> stringResource(R.string.latency_pill_down)
            latencyMs != null -> stringResource(R.string.latency_pill_value, latencyMs)
            unavailable -> stringResource(R.string.smart_profile_metric_unavailable)
            else -> stringResource(R.string.smart_profile_metric_unavailable)
        }
    val tone =
        if (!enabled) {
            SmartProfileMetricTone.NEUTRAL
        } else {
            when (classifyVpnLatency(latencyMs = latencyMs, failed = down, unavailable = unavailable || latencyMs == null)) {
                LatencyQuality.FAST -> SmartProfileMetricTone.POSITIVE
                LatencyQuality.NORMAL -> SmartProfileMetricTone.NEUTRAL
                LatencyQuality.SLOW -> SmartProfileMetricTone.WARNING
                LatencyQuality.VERY_SLOW -> SmartProfileMetricTone.VERY_SLOW
                LatencyQuality.FAILED -> SmartProfileMetricTone.DANGER
                LatencyQuality.UNAVAILABLE -> SmartProfileMetricTone.NEUTRAL
            }
        }
    SmartProfileMetricPill(
        text = text,
        tone = tone,
        roundUnavailable = latencyMs == null && !down,
        enabled = enabled,
        modifier = modifier,
    )
}

@Composable
private fun SmartProfileStatusPill(
    modifier: Modifier = Modifier,
    presentation: SmartStartProtocolPresentation,
    latencyMs: Long? = null,
    latencyDown: Boolean = false,
    latencyUnavailable: Boolean = false,
    showLatencyDetails: Boolean = false,
    compact: Boolean,
) {
    SmartProfileMetricPill(
        text =
            if (showLatencyDetails) {
                smartStartProtocolCompactText(
                    presentation = presentation,
                    latencyMs = latencyMs,
                    latencyDown = latencyDown,
                    latencyUnavailable = latencyUnavailable,
                )
            } else {
                smartStartProtocolPresentationText(presentation)
            },
        tone =
            if (showLatencyDetails) {
                smartStartProtocolCompactTone(
                    presentation = presentation,
                    latencyMs = latencyMs,
                    latencyDown = latencyDown,
                    latencyUnavailable = latencyUnavailable,
                )
            } else {
                smartStartProtocolPresentationTone(presentation)
            },
        compact = compact,
        modifier = modifier,
    )
}

@Composable
private fun smartStartProtocolCompactText(
    presentation: SmartStartProtocolPresentation,
    latencyMs: Long?,
    latencyDown: Boolean,
    latencyUnavailable: Boolean,
): String {
    val base = smartStartProtocolPresentationText(presentation)
    if (latencyMs == null || latencyDown || latencyUnavailable) {
        return base
    }
    val quality =
        latencyQualityLabel(
            classifyVpnLatency(
                latencyMs = latencyMs,
                failed = false,
                unavailable = false,
            ),
        )
    return when (presentation.status) {
        SmartStartProtocolStatus.AVAILABLE,
        SmartStartProtocolStatus.SLOW,
        SmartStartProtocolStatus.RECOMMENDED,
        -> quality
        else -> base
    }
}

private fun smartStartProtocolCompactTone(
    presentation: SmartStartProtocolPresentation,
    latencyMs: Long?,
    latencyDown: Boolean,
    latencyUnavailable: Boolean,
): SmartProfileMetricTone {
    if (presentation.status == SmartStartProtocolStatus.DISABLED) {
        return SmartProfileMetricTone.NEUTRAL
    }
    if (latencyDown) {
        return SmartProfileMetricTone.DANGER
    }
    if (latencyMs == null || latencyUnavailable) {
        return smartStartProtocolPresentationTone(presentation)
    }
    return when (classifyVpnLatency(latencyMs = latencyMs, failed = false, unavailable = false)) {
        LatencyQuality.FAST -> SmartProfileMetricTone.POSITIVE
        LatencyQuality.NORMAL -> SmartProfileMetricTone.NEUTRAL
        LatencyQuality.SLOW -> SmartProfileMetricTone.WARNING
        LatencyQuality.VERY_SLOW -> SmartProfileMetricTone.VERY_SLOW
        LatencyQuality.UNAVAILABLE,
        LatencyQuality.FAILED,
        -> smartStartProtocolPresentationTone(presentation)
    }
}

@Composable
internal fun latencyQualityLabel(quality: LatencyQuality): String =
    stringResource(
        when (quality) {
            LatencyQuality.FAST -> R.string.latency_quality_fast
            LatencyQuality.NORMAL -> R.string.latency_quality_normal
            LatencyQuality.SLOW -> R.string.latency_quality_slow
            LatencyQuality.VERY_SLOW -> R.string.latency_quality_very_slow
            LatencyQuality.UNAVAILABLE -> R.string.latency_quality_unavailable
            LatencyQuality.FAILED -> R.string.latency_quality_failed
        },
    )

@Composable
private fun smartStartProtocolPresentationText(presentation: SmartStartProtocolPresentation): String =
    when (presentation.status) {
        SmartStartProtocolStatus.RECOMMENDED -> stringResource(R.string.smart_start_protocol_status_recommended)
        SmartStartProtocolStatus.AVAILABLE -> stringResource(R.string.smart_start_protocol_status_available)
        SmartStartProtocolStatus.SLOW -> stringResource(R.string.smart_start_protocol_status_slow)
        SmartStartProtocolStatus.RECENTLY_FAILED -> stringResource(R.string.latency_pill_down)
        SmartStartProtocolStatus.NO_DATA -> stringResource(R.string.smart_start_protocol_status_no_data)
        SmartStartProtocolStatus.DISABLED -> stringResource(R.string.smart_start_protocol_status_disabled)
    }

private fun smartStartProtocolPresentationTone(presentation: SmartStartProtocolPresentation): SmartProfileMetricTone =
    when (presentation.status) {
        SmartStartProtocolStatus.RECOMMENDED -> SmartProfileMetricTone.POSITIVE
        SmartStartProtocolStatus.AVAILABLE -> SmartProfileMetricTone.NEUTRAL
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
    enabled: Boolean = true,
) {
    val color =
        if (!enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            when (tone) {
                SmartProfileMetricTone.POSITIVE -> FoxholePositiveAccent
                SmartProfileMetricTone.WARNING -> Color(0xFFE0B84A)
                SmartProfileMetricTone.VERY_SLOW -> Color(0xFFE28131)
                SmartProfileMetricTone.DANGER -> Color(0xFFC95353)
                SmartProfileMetricTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        }
    val contentAlpha = if (enabled) 1f else 0.58f
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
            color = color.copy(alpha = if (!enabled) 0.06f else if (tone == SmartProfileMetricTone.NEUTRAL) 0.10f else 0.16f),
            border = BorderStroke(1.dp, color.copy(alpha = if (enabled) 0.32f else 0.18f)),
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
                        color = color.copy(alpha = contentAlpha),
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
                    color = color.copy(alpha = contentAlpha),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
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
    VERY_SLOW,
    DANGER,
    NEUTRAL,
}

private val SmartProfileMenuHorizontalPadding = 8.dp
private val SmartProfileMenuCompactMinWidth = 216.dp
private val SmartProfileMenuDetailedMinWidth = 304.dp
private val SmartProfileMenuMaxWidth = 392.dp
private val SmartProfileMenuRefreshColumnWidth = 96.dp
private val SmartProfileProtocolAdaptiveCompactRowHeight = 54.dp
private val SmartProfileProtocolAdaptiveRowHeight = 60.dp
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
