package com.foxhole.beta.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RemoveCircleOutline
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import com.foxhole.beta.core.profile.MultiProtocolProfileSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat

private val CompactProtocolSelectorMinWidth = 170.dp
private val CompactProtocolSelectorMaxWidth = 286.dp
private val RegularProtocolSelectorMinWidth = 206.dp
private val RegularProtocolSelectorMaxWidth = 332.dp

@Composable
internal fun ProtocolMetadataRow(
    protocol: ProtocolHint,
    subscriptionExpiresAt: Long?,
    modifier: Modifier = Modifier,
    protocolOptions: List<ProfileProtocolOption> = emptyList(),
    selectedProtocolOptionId: String? = null,
    onProtocolOptionSelected: ((String) -> Unit)? = null,
    expiryPlacement: SubscriptionExpiryPlacement = SubscriptionExpiryPlacement.DASHBOARD,
    compact: Boolean = false,
    animateSelection: Boolean = false,
    latencyByOptionId: Map<String, Long> = emptyMap(),
    selectorMenuInfoText: String? = null,
    selectorBorderColor: Color? = null,
    requiresInsecureTls: Boolean = false,
    showInsecureTlsBadge: Boolean = true,
    reserveTrailingSpace: Boolean = true,
    expand: Boolean = true,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val supportedProtocolOptions = MultiProtocolProfileSupport.supportedOptions(protocolOptions)
    val selectedOption =
        supportedProtocolOptions.firstOrNull { it.id == selectedProtocolOptionId }
            ?: supportedProtocolOptions.firstOrNull(ProfileProtocolOption::isSelected)
    val selectedRequiresInsecureTls =
        shouldShowInsecureTlsProfileBadge(
            showInsecureTlsBadge = showInsecureTlsBadge,
            profileRequiresInsecureTls = requiresInsecureTls,
            selectedOptionRequiresInsecureTls = selectedOption?.requiresInsecureTls == true,
        )
    Row(
        modifier = if (expand) modifier.fillMaxWidth() else modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingContent?.invoke(this)
        ProtocolMarkOrSelector(
            protocol = protocol,
            protocolOptions = supportedProtocolOptions,
            selectedProtocolOptionId = selectedProtocolOptionId,
            onProtocolOptionSelected = onProtocolOptionSelected,
            compact = compact,
            animateSelection = animateSelection,
            dropdownInfoText = selectorMenuInfoText,
            selectorBorderColor = selectorBorderColor,
        )
        trailingContent?.invoke(this)
        if (selectedRequiresInsecureTls) {
            if (reserveTrailingSpace) {
                Spacer(modifier = Modifier.weight(1f))
            }
            InsecureTlsProfileBadge(compact = compact)
        }
        if (expiryPlacement == SubscriptionExpiryPlacement.DASHBOARD) {
            if (reserveTrailingSpace && !selectedRequiresInsecureTls) {
                Spacer(modifier = Modifier.weight(1f))
            }
            if (subscriptionExpiresAt != null) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    subscriptionExpiresAt?.let { expiresAt ->
                        SubscriptionExpiryText(
                            expiresAt = expiresAt,
                            placement = expiryPlacement,
                        )
                    }
                }
            }
        } else {
            subscriptionExpiresAt?.let { expiresAt ->
                SubscriptionExpiryText(
                    expiresAt = expiresAt,
                    placement = expiryPlacement,
                )
                Spacer(modifier = Modifier.weight(1f))
            } ?: Spacer(modifier = Modifier.weight(1f))
        }
    }
}

internal fun shouldShowInsecureTlsProfileBadge(
    showInsecureTlsBadge: Boolean,
    profileRequiresInsecureTls: Boolean,
    selectedOptionRequiresInsecureTls: Boolean,
): Boolean =
    showInsecureTlsBadge && (profileRequiresInsecureTls || selectedOptionRequiresInsecureTls)

@Composable
internal fun InsecureTlsProfileBadge(compact: Boolean = false) {
    val badgeColor = Color(0xFFE55353)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.72f)),
    ) {
        Text(
            text = stringResource(R.string.insecure_tls_profile_badge),
            modifier = Modifier.padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 2.dp else 3.dp),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (compact) 9.sp else 10.sp,
                    lineHeight = if (compact) 10.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            color = badgeColor,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
internal fun rememberProfileSourceSummary(profile: Profile): String {
    val sourceLabel = profileSourceLabel(profile)
    val expiresAt = profile.subscriptionExpiresAt
    if (profile.sourceType != ProfileSourceType.SUBSCRIPTION_URL || expiresAt == null) {
        return sourceLabel
    }
    val formattedDate = rememberSubscriptionExpiryLabel(expiresAt)
    val activeUntilLabel = stringResource(R.string.subscription_profile_active_until_label)
    return remember(activeUntilLabel, formattedDate) {
        "$activeUntilLabel $formattedDate"
    }
}

@Composable
internal fun rememberProfileDetailSourceValue(profile: Profile): String {
    val expiresAt = profile.subscriptionExpiresAt
    return if (profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL && expiresAt != null) {
        rememberSubscriptionExpiryLabel(expiresAt)
    } else {
        profileSourceLabel(profile)
    }
}

@Composable
private fun ProtocolMarkOrSelector(
    protocol: ProtocolHint,
    protocolOptions: List<ProfileProtocolOption>,
    selectedProtocolOptionId: String?,
    onProtocolOptionSelected: ((String) -> Unit)?,
    compact: Boolean,
    animateSelection: Boolean,
    dropdownInfoText: String?,
    selectorBorderColor: Color?,
) {
    if (protocolOptions.size < 2 || onProtocolOptionSelected == null) {
        ProtocolMark(protocol = protocol, compact = compact)
        return
    }

    var expanded by rememberSaveable { mutableStateOf(false) }
    val selected =
        protocolOptions.firstOrNull { it.id == selectedProtocolOptionId }
            ?: protocolOptions.firstOrNull(ProfileProtocolOption::isSelected)
            ?: protocolOptions.first()
    val selectorWidth =
        rememberProtocolSelectorFixedWidth(
            protocolOptions = protocolOptions,
            compact = compact,
        )
    Box {
        Surface(
            modifier =
                Modifier
                    .testTag("protocol_selector_${selected.id}")
                    .width(selectorWidth)
                    .clip(MaterialTheme.shapes.large)
                    .clickable { expanded = true },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface.copy(alpha = if (compact) 0.20f else 0.24f),
            border = BorderStroke(1.dp, selectorBorderColor ?: MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)),
        ) {
            Row(
                modifier =
                    Modifier.padding(
                        start = if (compact) 10.dp else 14.dp,
                        top = if (compact) 7.dp else 9.dp,
                        end = if (compact) 8.dp else 10.dp,
                        bottom = if (compact) 7.dp else 9.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f, fill = true)) {
                    if (animateSelection) {
                        AnimatedContent(
                            targetState = selected.id,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(170))
                                    .togetherWith(fadeOut(animationSpec = tween(120)))
                            },
                            label = "protocol_selector_active_option",
                        ) { optionId ->
                            val animatedOption = protocolOptions.firstOrNull { option -> option.id == optionId } ?: selected
                            ProtocolSelectorLabel(
                                option = animatedOption,
                                compact = compact,
                            )
                        }
                    } else {
                        ProtocolSelectorLabel(
                            option = selected,
                            compact = compact,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (compact) 17.dp else 18.dp),
                )
            }
        }
        FoxholeDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(selectorWidth),
            offset = DpOffset(x = 0.dp, y = if (compact) (-4).dp else 0.dp),
        ) {
            Column(
                modifier = Modifier,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                dropdownInfoText?.let { infoText ->
                    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (compact) 4.dp else 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                modifier = Modifier.size(if (compact) 14.dp else 16.dp),
                                tint = FoxholeInfoAccent,
                            )
                            Text(
                                text = infoText,
                                modifier = Modifier.weight(1f),
                                style =
                                    if (compact) {
                                        MaterialTheme.typography.bodySmall
                                    } else {
                                        MaterialTheme.typography.labelMedium
                                    },
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = if (compact) 8.dp else 10.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f),
                        )
                    }
                }
                protocolOptions.forEachIndexed { index, option ->
                    val optionSelected = option.id == selected.id
                    FoxholeDropdownItem(
                        onClick = {
                            expanded = false
                            onProtocolOptionSelected(option.id)
                        },
                        selected = optionSelected,
                        highlightSelected = false,
                        showBorder = false,
                        accentColor = FoxholePositiveAccent,
                        shape =
                            foxholeDropdownItemShape(
                                index = index,
                                lastIndex = protocolOptions.lastIndex,
                                hasHeader = dropdownInfoText != null,
                            ),
                        contentPadding =
                            PaddingValues(
                                horizontal = 12.dp,
                                vertical = 0.dp,
                            ),
                        trailingContent = {
                            Box(
                                modifier = Modifier.size(20.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                if (optionSelected) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = FoxholePositiveAccent,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                    ) {
                        ProtocolSelectorLabel(
                            option = option,
                            modifier = Modifier.weight(1f),
                            compact = compact,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProtocolSelectorLabel(
    option: ProfileProtocolOption,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProtocolMark(protocol = option.protocolHint, compact = compact)
        protocolSelectorSecondaryLabel(option)?.let { secondaryLabel ->
            Text(
                text = secondaryLabel,
                style =
                    if (compact) {
                        MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp,
                            lineHeight = 13.sp,
                        )
                    } else {
                        MaterialTheme.typography.labelMedium
                    },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ProtocolLatencyPill(
    modifier: Modifier = Modifier,
    latencyMs: Long? = null,
    compact: Boolean = false,
    isDown: Boolean = false,
    isUnavailable: Boolean = false,
    showLabel: Boolean = false,
) {
    val (contentColor, containerColor) =
        when (classifyVpnLatency(latencyMs = latencyMs, failed = isDown, unavailable = isUnavailable || latencyMs == null)) {
            LatencyQuality.FAST -> Color(0xFF2F9E6A) to Color(0xFF2F9E6A).copy(alpha = 0.16f)
            LatencyQuality.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
            LatencyQuality.SLOW -> Color(0xFFE0B84A) to Color(0xFFE0B84A).copy(alpha = 0.16f)
            LatencyQuality.VERY_SLOW -> Color(0xFFE28131) to Color(0xFFE28131).copy(alpha = 0.16f)
            LatencyQuality.FAILED -> Color(0xFFC95353) to Color(0xFFC95353).copy(alpha = 0.16f)
            LatencyQuality.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        }
    val text =
        when {
            showLabel -> {
                val label = stringResource(R.string.latency_pill_label)
                val value =
                    when {
                        isDown -> stringResource(R.string.latency_pill_down)
                        isUnavailable -> stringResource(R.string.latency_pill_unavailable)
                        latencyMs != null -> stringResource(R.string.latency_pill_value, latencyMs)
                        else -> stringResource(R.string.latency_pill_unavailable)
                    }
                buildAnnotatedString {
                    pushStyle(
                        SpanStyle(
                            fontSize = if (compact) 8.sp else 11.sp,
                        ),
                    )
                    append(label)
                    pop()
                    append(' ')
                    append(value)
                }
            }
            isDown -> AnnotatedString(stringResource(R.string.latency_pill_down))
            isUnavailable -> AnnotatedString(stringResource(R.string.latency_pill_unavailable))
            latencyMs != null -> AnnotatedString(stringResource(R.string.latency_pill_value, latencyMs))
            else -> AnnotatedString(stringResource(R.string.latency_pill_unavailable))
        }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.32f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = if (compact) 6.dp else 10.dp, vertical = if (compact) 2.dp else 5.dp),
            style =
                if (compact) {
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        lineHeight = 10.sp,
                    )
                } else {
                    MaterialTheme.typography.labelMedium
                },
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun SubscriptionExpiryText(
    expiresAt: Long,
    placement: SubscriptionExpiryPlacement,
) {
    val formattedDate = rememberSubscriptionExpiryLabel(expiresAt)
    when (placement) {
        SubscriptionExpiryPlacement.DASHBOARD ->
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.subscription_dashboard_active_until_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

        SubscriptionExpiryPlacement.PROFILE_INLINE ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.subscription_profile_active_until_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
    }
}

@Composable
internal fun rememberSubscriptionExpiryLabel(expiresAt: Long): String {
    val formatter = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    return remember(expiresAt, formatter) { formatter.format(expiresAt) }
}

@Composable
private fun rememberProtocolSelectorFixedWidth(
    protocolOptions: List<ProfileProtocolOption>,
    compact: Boolean,
): androidx.compose.ui.unit.Dp {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val primaryStyle =
        if (compact) {
            MaterialTheme.typography.labelSmall.copy(
                fontSize = 12.5.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
        }
    val secondaryStyle =
        if (compact) {
            MaterialTheme.typography.labelSmall.copy(
                fontSize = 12.sp,
                lineHeight = 13.sp,
            )
        } else {
            MaterialTheme.typography.labelMedium
        }
    val iconSizePx = with(density) { if (compact) 13.dp.roundToPx() else 18.dp.roundToPx() }
    val markSpacingPx = with(density) { if (compact) 4.dp.roundToPx() else 8.dp.roundToPx() }
    val secondarySpacingPx = with(density) { if (compact) 4.dp.roundToPx() else 8.dp.roundToPx() }
    val chevronSizePx = with(density) { if (compact) 17.dp.roundToPx() else 18.dp.roundToPx() }
    val chevronGapPx = with(density) { if (compact) 4.dp.roundToPx() else 6.dp.roundToPx() }
    val leadingPaddingPx = with(density) { if (compact) 10.dp.roundToPx() else 14.dp.roundToPx() }
    val trailingPaddingPx = with(density) { if (compact) 8.dp.roundToPx() else 10.dp.roundToPx() }
    val compactWidthSlackPx = with(density) { if (compact) 8.dp.roundToPx() else 0 }
    val widestContentPx =
        protocolOptions.maxOfOrNull { option ->
            val primaryWidth =
                textMeasurer.measure(
                    text = AnnotatedString(protocolDisplayLabel(option.protocolHint)),
                    style = primaryStyle,
                ).size.width
            val secondaryWidth =
                protocolSelectorSecondaryLabel(option)?.let { secondary ->
                    secondarySpacingPx +
                        textMeasurer.measure(
                            text = AnnotatedString(secondary),
                            style = secondaryStyle,
                        ).size.width
                } ?: 0
            iconSizePx + markSpacingPx + primaryWidth + secondaryWidth
        } ?: 0
    val estimatedWidth =
        with(density) {
            (leadingPaddingPx + widestContentPx + chevronGapPx + chevronSizePx + trailingPaddingPx + compactWidthSlackPx).toDp()
        }
    return if (compact) {
        estimatedWidth.coerceIn(CompactProtocolSelectorMinWidth, CompactProtocolSelectorMaxWidth)
    } else {
        estimatedWidth.coerceIn(RegularProtocolSelectorMinWidth, RegularProtocolSelectorMaxWidth)
    }
}

private fun protocolSelectorSecondaryLabel(option: ProfileProtocolOption): String? {
    val displayName = option.displayName.ifBlank { return null }
    val primaryLabel = protocolDisplayLabel(option.protocolHint)
    val trimmed =
        displayName
            .removePrefix("$primaryLabel · ")
            .removePrefix("$primaryLabel·")
            .removePrefix("$primaryLabel ")
            .trim()
    return trimmed.takeIf { it.isNotBlank() && !it.equals(primaryLabel, ignoreCase = true) }
}

internal fun protocolDisplayLabel(protocol: ProtocolHint): String =
    when (protocol) {
        ProtocolHint.VLESS -> "VLESS"
        ProtocolHint.TROJAN -> "TROJAN"
        ProtocolHint.SHADOWSOCKS -> "SHADOWSOCKS"
        ProtocolHint.WIREGUARD -> "WIREGUARD"
        ProtocolHint.HYSTERIA2 -> "HYSTERIA2"
        ProtocolHint.VMESS -> "VMESS"
        ProtocolHint.OUTLINE -> "OUTLINE"
        ProtocolHint.SING_BOX -> "SING-BOX"
        ProtocolHint.UNKNOWN -> "UNKNOWN"
    }

@Composable
internal fun SelectedAppRow(
    app: InstalledAppOption,
    onRemove: () -> Unit,
) {
    FoxholeCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(packageName = app.packageName)
            AppTextBlock(app = app, modifier = Modifier.weight(1f))
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = stringResource(R.string.delete_label))
            }
        }
    }
}

@Composable
internal fun SelectableInstalledAppRow(
    app: InstalledAppOption,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    FoxholeCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .clickable { onToggle(!checked) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(packageName = app.packageName)
            AppTextBlock(app = app, modifier = Modifier.weight(1f))
            FoxholeSwitch(
                checked = checked,
                onCheckedChange = onToggle,
            )
        }
    }
}

@Composable
internal fun AppTextBlock(
    app: InstalledAppOption,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = app.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = app.packageName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppTypePill(isSystemApp = app.isSystemApp)
    }
}

@Composable
internal fun AppTypePill(isSystemApp: Boolean) {
    FoxholeValuePill(
        value =
            if (isSystemApp) {
                stringResource(R.string.system_app_label)
            } else {
                stringResource(R.string.user_app_label)
            },
    )
}

@Composable
internal fun AppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { 40.dp.roundToPx() }
    val cached = remember(packageName) { appIconCache.get(packageName) }
    val bitmap by produceState<ImageBitmap?>(initialValue = cached, packageName, sizePx) {
        if (value != null) {
            return@produceState
        }
        val resolved =
            withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager
                        .getApplicationIcon(packageName)
                        .toBitmap(
                            width = sizePx,
                            height = sizePx,
                            config = Bitmap.Config.ARGB_8888,
                        ).asImageBitmap()
                }.getOrNull()
            }
        if (resolved != null) {
            appIconCache.put(packageName, resolved)
        }
        value = resolved
    }

    Surface(
        modifier = modifier.size(56.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.padding(8.dp).fillMaxSize(),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Apps,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
internal fun perAppRoutingModeLabel(mode: PerAppRoutingMode): String =
    when (mode) {
        PerAppRoutingMode.FULL_TUNNEL -> stringResource(R.string.per_app_mode_full_tunnel)
        PerAppRoutingMode.INCLUDE_SELECTED_APPS -> stringResource(R.string.per_app_mode_include_selected)
        PerAppRoutingMode.EXCLUDE_SELECTED_APPS -> stringResource(R.string.per_app_mode_exclude_selected)
    }

@Composable
internal fun routingAppsModeGuidance(mode: PerAppRoutingMode): String =
    when (mode) {
        PerAppRoutingMode.FULL_TUNNEL -> stringResource(R.string.routing_apps_mode_full_summary)
        PerAppRoutingMode.INCLUDE_SELECTED_APPS -> stringResource(R.string.routing_apps_mode_include_summary)
        PerAppRoutingMode.EXCLUDE_SELECTED_APPS -> stringResource(R.string.routing_apps_mode_exclude_summary)
    }

@Composable
internal fun profileSourceLabel(profile: Profile): String =
    when (profile.sourceType) {
        ProfileSourceType.SUBSCRIPTION_URL -> stringResource(R.string.profile_source_subscription)
        ProfileSourceType.SHARE_URI -> stringResource(R.string.profile_source_share_uri)
        ProfileSourceType.RAW_SINGBOX_JSON -> stringResource(R.string.profile_source_raw_json)
        ProfileSourceType.RAW_WIREGUARD_TEXT -> stringResource(R.string.profile_source_wireguard)
    }

internal fun formatProfileUpdatedAt(value: Long?): String =
    value?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(it) } ?: "—"

@Composable
internal fun siteRuleSummary(rule: RoutingRule): String = siteActionLabel(rule.action)

@Composable
internal fun SiteRuleDialog(
    rule: RoutingRule?,
    onDismiss: () -> Unit,
    onConfirm: (List<String>, RoutingRuleAction) -> Unit,
) {
    var domains by rememberSaveable(rule?.id) { mutableStateOf(rule?.matchDomains?.joinToString("\n").orEmpty()) }
    var action by rememberSaveable(rule?.id) { mutableStateOf(rule?.action ?: RoutingRuleAction.DIRECT) }
    var actionDialog by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            FoxholeDialogTitle(
                title =
                    if (rule == null) {
                        stringResource(R.string.add_site_exception)
                    } else {
                        stringResource(R.string.edit_site_exception)
                    },
                icon = Icons.Outlined.Public,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = domains,
                    onValueChange = { domains = it },
                    label = { Text(stringResource(R.string.match_domains)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                FoxholePreferenceCard(
                    title = stringResource(R.string.action_label),
                    summary = stringResource(R.string.site_exception_action_summary),
                    leadingIcon = siteActionIcon(action),
                    onClick = { actionDialog = true },
                    trailingContent = {
                        FoxholeValuePill(siteActionLabel(action))
                    },
                )
            }
        },
        confirmButton = {
            FoxholeDialogConfirmButton(
                onClick = {
                    onConfirm(
                        domains
                            .lineSequence()
                            .map(String::trim)
                            .filter(String::isNotBlank)
                            .toList(),
                        action,
                    )
                },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )

    if (actionDialog) {
        ChoiceDialog(
            title = stringResource(R.string.action_label),
            values = RoutingRuleAction.entries,
            selected = action,
            label = { siteActionLabel(it) },
            icon = { siteActionIcon(it) },
            onDismiss = { actionDialog = false },
            onSelect = {
                action = it
                actionDialog = false
            },
        )
    }
}

@Composable
internal fun <T> ChoiceDialog(
    title: String,
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    icon: @Composable ((T) -> ImageVector)? = null,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                values.forEach { value ->
                    if (icon == null) {
                        FoxholeChoiceCard(
                            title = label(value),
                            selected = value == selected,
                            onClick = { onSelect(value) },
                        )
                    } else {
                        FoxholePreferenceCard(
                            title = label(value),
                            leadingIcon = icon(value),
                            onClick = { onSelect(value) },
                            trailingContent = {
                                if (value == selected) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
internal fun siteActionLabel(action: RoutingRuleAction): String =
    when (action) {
        RoutingRuleAction.PROXY -> stringResource(R.string.site_action_proxy)
        RoutingRuleAction.DIRECT -> stringResource(R.string.site_action_direct)
        RoutingRuleAction.BLOCK -> stringResource(R.string.site_action_block)
    }

internal fun siteActionIcon(action: RoutingRuleAction): ImageVector =
    when (action) {
        RoutingRuleAction.PROXY -> Icons.Outlined.Tune
        RoutingRuleAction.DIRECT -> Icons.Outlined.ArrowOutward
        RoutingRuleAction.BLOCK -> Icons.Outlined.Block
    }
