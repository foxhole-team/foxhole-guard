package com.foxhole.beta.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.foxhole.beta.ui.theme.LocalFoxholeUiPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat

private val CompactProtocolSelectorMinWidth = 154.dp
private val CompactProtocolSelectorMaxWidth = 300.dp
private val RegularProtocolSelectorMinWidth = 166.dp
private val RegularProtocolSelectorMaxWidth = 320.dp

@Suppress("LongParameterList", "CyclomaticComplexMethod")
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
    downProtocolOptionIds: Set<String> = emptySet(),
    latencyUnavailableOptionIds: Set<String> = emptySet(),
    recommendedProtocolOptionId: String? = null,
    recommendedProtocolOptionIds: Set<String> = recommendedProtocolOptionId?.let(::setOf).orEmpty(),
    favoriteProtocolOptionId: String? = null,
    selectorMenuInfoText: String? = null,
    selectorBorderColor: Color? = null,
    highlightSelectedOption: Boolean = true,
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
            hasMultipleProtocolOptions = supportedProtocolOptions.size > 1,
        )
    val selectedLatencyOptionId =
        selectedOption?.id
            ?: selectedProtocolOptionId
            ?: protocolLatencyOptionId(protocol)
    val selectedLatencyMs = selectedLatencyOptionId?.let(latencyByOptionId::get)
    val selectedLatencyDown = selectedLatencyOptionId in downProtocolOptionIds
    val selectedLatencyUnavailable =
        selectedLatencyOptionId != null &&
            selectedLatencyMs == null &&
            selectedLatencyOptionId in latencyUnavailableOptionIds
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
            recommendedProtocolOptionId = recommendedProtocolOptionId,
            recommendedProtocolOptionIds = recommendedProtocolOptionIds,
            favoriteProtocolOptionId = favoriteProtocolOptionId,
            latencyByOptionId = latencyByOptionId,
            downProtocolOptionIds = downProtocolOptionIds,
            latencyUnavailableOptionIds = latencyUnavailableOptionIds,
            selectedLatencyMs = selectedLatencyMs,
            selectedLatencyDown = selectedLatencyDown,
            selectedLatencyUnavailable = selectedLatencyUnavailable,
            dropdownInfoText = selectorMenuInfoText,
            selectorBorderColor = selectorBorderColor,
            highlightSelectedOption = highlightSelectedOption,
        )
        trailingContent?.invoke(this)
        if (selectedRequiresInsecureTls) {
            Box(
                modifier =
                    if (expand || reserveTrailingSpace) {
                        Modifier
                            .weight(1f, fill = true)
                            .fillMaxWidth()
                    } else {
                        Modifier
                    },
                contentAlignment = Alignment.CenterEnd,
            ) {
                InsecureTlsProfileBadge(
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .offset(y = 0.dp),
                    compact = compact,
                )
            }
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
    hasMultipleProtocolOptions: Boolean = false,
): Boolean =
    showInsecureTlsBadge &&
        if (hasMultipleProtocolOptions) {
            selectedOptionRequiresInsecureTls
        } else {
            selectedOptionRequiresInsecureTls || profileRequiresInsecureTls
        }

internal fun selectedProtocolRequiresInsecureTls(profile: Profile): Boolean {
    val supportedProtocolOptions = MultiProtocolProfileSupport.supportedOptions(profile.protocolOptions)
    if (supportedProtocolOptions.size > 1) {
        val selectedOption =
            supportedProtocolOptions.firstOrNull { option -> option.id == profile.selectedProtocolOptionId }
                ?: supportedProtocolOptions.firstOrNull(ProfileProtocolOption::isSelected)
                ?: supportedProtocolOptions.firstOrNull()
        return selectedOption?.requiresInsecureTls == true
    }
    return profile.requiresInsecureTls || supportedProtocolOptions.firstOrNull()?.requiresInsecureTls == true
}

@Composable
internal fun InsecureTlsProfileBadge(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val badgeColor = Color(0xFFE55353)
    Surface(
        modifier = modifier.widthIn(min = if (compact) 78.dp else 92.dp),
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.72f)),
    ) {
        Text(
            text = stringResource(R.string.insecure_tls_profile_badge),
            modifier = Modifier.padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 2.dp else 3.dp),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (compact) 8.5.sp else 10.sp,
                    lineHeight = if (compact) 9.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            color = badgeColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
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

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
private fun ProtocolMarkOrSelector(
    protocol: ProtocolHint,
    protocolOptions: List<ProfileProtocolOption>,
    selectedProtocolOptionId: String?,
    onProtocolOptionSelected: ((String) -> Unit)?,
    compact: Boolean,
    animateSelection: Boolean,
    recommendedProtocolOptionId: String?,
    recommendedProtocolOptionIds: Set<String>,
    favoriteProtocolOptionId: String?,
    latencyByOptionId: Map<String, Long>,
    downProtocolOptionIds: Set<String>,
    latencyUnavailableOptionIds: Set<String>,
    selectedLatencyMs: Long?,
    selectedLatencyDown: Boolean,
    selectedLatencyUnavailable: Boolean,
    dropdownInfoText: String?,
    selectorBorderColor: Color?,
    highlightSelectedOption: Boolean,
) {
    if (protocolOptions.size < 2 || onProtocolOptionSelected == null) {
        ProtocolMark(
            protocol = protocol,
            compact = compact,
            tintOverride =
                protocolLatencyIconTint(
                    latencyMs = selectedLatencyMs,
                    down = selectedLatencyDown,
                    unavailable = selectedLatencyUnavailable,
                ),
        )
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
            recommendedProtocolOptionId = recommendedProtocolOptionId,
            recommendedProtocolOptionIds = recommendedProtocolOptionIds,
            favoriteProtocolOptionId = favoriteProtocolOptionId,
        )
    val selectorShape = MaterialTheme.shapes.large
    val selectorPalette = LocalFoxholeUiPalette.current
    val selectorContainerColor = selectorPalette.valuePillContainerColor
    val selectorResolvedBorderColor =
        selectorBorderColor
            ?: selectorPalette.valuePillBorderColor.takeUnless { it == Color.Transparent }
            ?: MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
    Box {
        Surface(
            modifier =
                Modifier
                    .testTag("protocol_selector_${selected.id}")
                    .width(selectorWidth)
                    .foxholeMenuShadow(shape = selectorShape, elevation = 2.dp)
                    .clip(selectorShape)
                    .clickable { expanded = true },
            shape = selectorShape,
            color = selectorContainerColor,
            tonalElevation = 1.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, selectorResolvedBorderColor),
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
                                recommended = animatedOption.id in recommendedProtocolOptionIds,
                                topRecommended = animatedOption.id == recommendedProtocolOptionId,
                                favorite = animatedOption.id == favoriteProtocolOptionId,
                                latencyMs = latencyByOptionId[animatedOption.id],
                                latencyDown = animatedOption.id in downProtocolOptionIds,
                                latencyUnavailable =
                                    animatedOption.id !in latencyByOptionId &&
                                        animatedOption.id in latencyUnavailableOptionIds,
                            )
                        }
                    } else {
                        ProtocolSelectorLabel(
                            option = selected,
                            compact = compact,
                            recommended = selected.id in recommendedProtocolOptionIds,
                            topRecommended = selected.id == recommendedProtocolOptionId,
                            favorite = selected.id == favoriteProtocolOptionId,
                            latencyMs = latencyByOptionId[selected.id],
                            latencyDown = selected.id in downProtocolOptionIds,
                            latencyUnavailable =
                                selected.id !in latencyByOptionId &&
                                    selected.id in latencyUnavailableOptionIds,
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
            popupGap = 0.dp,
            horizontalAlignment = FoxholeDropdownHorizontalAlignment.AnchorStart,
        ) {
            Column(
                modifier = Modifier,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                dropdownInfoText?.let { infoText ->
                    val infoTone = foxholeSystemAwareAccentColor(fallback = FoxholeInfoAccent)
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
                                tint = infoTone,
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
                                color = infoTone,
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
                        highlightSelected = highlightSelectedOption,
                        selectedContainerColor = selectorResolvedBorderColor.copy(alpha = 0.16f),
                        extendSelectedToMenuTop = index == 0 && dropdownInfoText == null,
                        extendSelectedToMenuBottom = index == protocolOptions.lastIndex,
                        shape =
                            foxholeDropdownItemShape(
                                index = index,
                                lastIndex = protocolOptions.lastIndex,
                                hasHeader = dropdownInfoText != null,
                            ),
                        minHeight = if (compact) 34.dp else 42.dp,
                        contentPadding =
                            PaddingValues(
                                start = 12.dp,
                                top = 0.dp,
                                end = 0.dp,
                                bottom = 0.dp,
                            ),
                    ) {
                        ProtocolSelectorLabel(
                            option = option,
                            modifier = Modifier.weight(1f),
                            compact = compact,
                            recommended = option.id in recommendedProtocolOptionIds,
                            topRecommended = option.id == recommendedProtocolOptionId,
                            favorite = option.id == favoriteProtocolOptionId,
                            latencyMs = latencyByOptionId[option.id],
                            latencyDown = option.id in downProtocolOptionIds,
                            latencyUnavailable =
                                option.id !in latencyByOptionId &&
                                    option.id in latencyUnavailableOptionIds,
                        )
                    }
                    if (index != protocolOptions.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
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
    recommended: Boolean = false,
    topRecommended: Boolean = false,
    favorite: Boolean = false,
    latencyMs: Long? = null,
    latencyDown: Boolean = false,
    latencyUnavailable: Boolean = false,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProtocolMark(
            protocol = option.protocolHint,
            compact = compact,
            tintOverride =
                protocolLatencyIconTint(
                    latencyMs = latencyMs,
                    down = latencyDown,
                    unavailable = latencyUnavailable,
                ),
        )
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
        if (favorite || recommended) {
            SmartProfileConditionStars(
                favorite = favorite,
                recommended = recommended,
                topRecommended = topRecommended,
                compact = compact,
                modifier = Modifier.offset(y = if (compact) (-4).dp else (-3).dp),
            )
        }
    }
}

@Composable
internal fun SmartProfileConditionStars(
    topRecommended: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    favorite: Boolean = false,
    recommended: Boolean = false,
) {
    val starCount =
        smartProfileConditionStarCount(
            favorite = favorite,
            recommended = recommended,
            topRecommended = topRecommended,
        )
    if (starCount <= 0) {
        return
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(starCount) { index ->
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription =
                    if (index == 0) {
                        stringResource(
                            if (starCount == 2) {
                                R.string.smart_profile_legend_reconnect_recommended
                            } else if (favorite) {
                                R.string.smart_profile_legend_favorite
                            } else {
                                R.string.smart_profile_menu_recommended_badge
                            },
                        )
                    } else {
                        null
                    },
                modifier = Modifier.size(if (compact) 9.dp else 10.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

internal fun smartProfileConditionStarCount(
    favorite: Boolean,
    recommended: Boolean,
    topRecommended: Boolean,
): Int =
    when {
        favorite && recommended -> 2
        recommended && topRecommended -> 2
        favorite || recommended -> 1
        else -> 0
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
            showLabel && latencyMs != null && !isDown && !isUnavailable -> {
                val label = stringResource(R.string.latency_pill_label)
                val value = stringResource(R.string.latency_pill_value, boundedDisplayLatencyMs(latencyMs))
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
            latencyMs != null -> AnnotatedString(stringResource(R.string.latency_pill_value, boundedDisplayLatencyMs(latencyMs)))
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
internal fun ProtocolLatencyLoadingPill(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showLabel: Boolean = false,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    FoxholeSkeletonBlock(
        modifier =
            modifier
                .width(
                    when {
                        showLabel && compact -> 78.dp
                        showLabel -> 96.dp
                        compact -> 44.dp
                        else -> 58.dp
                    },
                )
                .height(if (compact) 16.dp else 24.dp),
        color = color,
    )
}

@Composable
internal fun protocolLatencyIconTint(
    latencyMs: Long?,
    down: Boolean = false,
    unavailable: Boolean = false,
): Color =
    when (classifyVpnLatency(latencyMs = latencyMs, failed = down, unavailable = unavailable || latencyMs == null)) {
        LatencyQuality.FAST -> FoxholePositiveAccent
        LatencyQuality.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
        LatencyQuality.SLOW -> Color(0xFFE0B84A)
        LatencyQuality.VERY_SLOW -> Color(0xFFE28131)
        LatencyQuality.FAILED -> Color(0xFFC95353)
        LatencyQuality.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
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
@Suppress("CyclomaticComplexMethod")
private fun rememberProtocolSelectorFixedWidth(
    protocolOptions: List<ProfileProtocolOption>,
    compact: Boolean,
    recommendedProtocolOptionId: String?,
    recommendedProtocolOptionIds: Set<String>,
    favoriteProtocolOptionId: String?,
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
    val legendStyle =
        MaterialTheme.typography.labelSmall.copy(
            fontSize = if (compact) 8.4.sp else 10.sp,
            lineHeight = if (compact) 9.sp else 11.sp,
            fontWeight = FontWeight.Medium,
        )
    val favoriteLegend = stringResource(R.string.smart_profile_legend_favorite)
    val recommendedLegend = stringResource(R.string.smart_profile_legend_reconnect_recommended)
    val iconSizePx = with(density) { if (compact) 13.dp.roundToPx() else 18.dp.roundToPx() }
    val markSpacingPx = with(density) { if (compact) 4.dp.roundToPx() else 8.dp.roundToPx() }
    val secondarySpacingPx = with(density) { if (compact) 4.dp.roundToPx() else 8.dp.roundToPx() }
    val starIconPx = with(density) { if (compact) 9.dp.roundToPx() else 10.dp.roundToPx() }
    val starGapPx = with(density) { 1.dp.roundToPx() }
    val chevronSizePx = with(density) { if (compact) 17.dp.roundToPx() else 18.dp.roundToPx() }
    val chevronGapPx = with(density) { if (compact) 4.dp.roundToPx() else 6.dp.roundToPx() }
    val leadingPaddingPx = with(density) { if (compact) 10.dp.roundToPx() else 14.dp.roundToPx() }
    val trailingPaddingPx = with(density) { if (compact) 8.dp.roundToPx() else 10.dp.roundToPx() }
    val compactWidthSlackPx = with(density) { if (compact) 8.dp.roundToPx() else 0 }
    val dropdownHorizontalPaddingPx = with(density) { 24.dp.roundToPx() }
    val dropdownTrailingGapPx = 0
    val dropdownCheckWidthPx = 0
    fun textWidth(text: String, style: TextStyle): Int =
        textMeasurer.measure(
            text = AnnotatedString(text),
            style = style,
        ).size.width
    fun conditionStarsWidthPx(option: ProfileProtocolOption): Int {
        val starCount =
            smartProfileConditionStarCount(
                favorite = option.id == favoriteProtocolOptionId,
                recommended = option.id in recommendedProtocolOptionIds,
                topRecommended = option.id == recommendedProtocolOptionId,
            )
        return if (starCount <= 0) {
            0
        } else {
            starCount * starIconPx + (starCount - 1) * starGapPx
        }
    }
    val widestContentPx =
        protocolOptions.maxOfOrNull { option ->
            val primaryWidth =
                textWidth(
                    text = protocolDisplayLabel(option.protocolHint),
                    style = primaryStyle,
                )
            val secondaryWidth =
                protocolSelectorSecondaryLabel(option)?.let { secondary ->
                    secondarySpacingPx +
                        textWidth(
                            text = secondary,
                            style = secondaryStyle,
                        )
                } ?: 0
            iconSizePx +
                markSpacingPx +
                primaryWidth +
                secondaryWidth +
                conditionStarsWidthPx(option)
        } ?: 0
    val openerWidthPx = leadingPaddingPx + widestContentPx + chevronGapPx + chevronSizePx + trailingPaddingPx + compactWidthSlackPx
    val dropdownRowWidthPx = dropdownHorizontalPaddingPx + widestContentPx + dropdownTrailingGapPx + dropdownCheckWidthPx
    val hasLegendBasis = protocolOptions.any { option -> option.id == favoriteProtocolOptionId || option.id in recommendedProtocolOptionIds }
    val legendWidthPx =
        if (hasLegendBasis) {
            textWidth(favoriteLegend, legendStyle) +
                textWidth(recommendedLegend, legendStyle) +
                with(density) { if (compact) 48.dp.toPx() else 56.dp.toPx() }
        } else {
            0f
        }
    val estimatedWidth =
        with(density) {
            protocolSelectorWidthBasisPx(
                protocolLabelWidthPx = maxOf(openerWidthPx, dropdownRowWidthPx).toFloat(),
                legendWidthPx = legendWidthPx,
            ).toDp()
        }
    return if (compact) {
        estimatedWidth.coerceIn(CompactProtocolSelectorMinWidth, CompactProtocolSelectorMaxWidth)
    } else {
        estimatedWidth.coerceIn(RegularProtocolSelectorMinWidth, RegularProtocolSelectorMaxWidth)
    }
}

internal fun protocolSelectorWidthBasisPx(
    protocolLabelWidthPx: Float,
    legendWidthPx: Float,
): Float = maxOf(protocolLabelWidthPx, legendWidthPx)

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

internal fun protocolLatencyOptionId(protocol: ProtocolHint): String? =
    protocol
        .takeIf { hint -> hint !in setOf(ProtocolHint.UNKNOWN, ProtocolHint.SING_BOX) }
        ?.name
        ?.lowercase()

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
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit,
) {
    FoxholeCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (enabled) ({ onToggle(!checked) }) else null,
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
                enabled = enabled,
                onCheckedChange = if (enabled) onToggle else null,
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
    val bitmap = rememberAppIconBitmap(packageName = packageName, bitmapSize = 48.dp)

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
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
internal fun rememberAppIconBitmap(
    packageName: String,
    bitmapSize: Dp,
): ImageBitmap? {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { bitmapSize.roundToPx() }
    val cacheKey = remember(packageName, sizePx) { "$packageName@$sizePx" }
    val cached = remember(cacheKey) { appIconCache.get(cacheKey) }
    val bitmap by produceState<ImageBitmap?>(initialValue = cached, cacheKey, sizePx) {
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
            appIconCache.put(cacheKey, resolved)
        }
        value = resolved
    }
    return bitmap
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
internal fun simpleAppRoutingModeLabel(mode: PerAppRoutingMode): String =
    when (mode) {
        PerAppRoutingMode.EXCLUDE_SELECTED_APPS -> stringResource(R.string.site_action_direct)
        PerAppRoutingMode.FULL_TUNNEL,
        PerAppRoutingMode.INCLUDE_SELECTED_APPS,
        -> stringResource(R.string.site_action_proxy)
    }

@Composable
internal fun simpleAppRoutingModeGuidance(mode: PerAppRoutingMode): String =
    when (mode) {
        PerAppRoutingMode.EXCLUDE_SELECTED_APPS -> stringResource(R.string.routing_apps_mode_direct_summary)
        PerAppRoutingMode.FULL_TUNNEL,
        PerAppRoutingMode.INCLUDE_SELECTED_APPS,
        -> stringResource(R.string.routing_apps_mode_proxy_summary)
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
    defaultAction: RoutingRuleAction = RoutingRuleAction.DIRECT,
    onDismiss: () -> Unit,
    onConfirm: (List<String>, RoutingRuleAction) -> Unit,
) {
    val initialToken = remember(rule?.id) { rule?.matchDomains?.firstOrNull() ?: rule?.matchIpCidrs?.firstOrNull()?.let { "cidr:$it" }.orEmpty() }
    var value by rememberSaveable(rule?.id) { mutableStateOf(initialToken) }
    var action by rememberSaveable(rule?.id, defaultAction) { mutableStateOf(rule?.action ?: defaultAction) }
    var actionMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val validationErrorRes = siteMaskValidationErrorRes(value)
    val validationError = validationErrorRes?.let { stringResource(it) }

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
                Text(
                    text = stringResource(R.string.supported_site_masks_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.supported_site_masks_examples),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.site_mask_input_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = validationError != null,
                    supportingText =
                        validationError?.let { message ->
                            { Text(text = message) }
                        },
                )
                DropdownSettingRow(
                    title = stringResource(R.string.action_label),
                    value = siteActionLabel(action),
                    expanded = actionMenuExpanded,
                    onExpandedChange = { actionMenuExpanded = it },
                    values = RoutingRuleAction.entries,
                    selected = action,
                    label = { siteActionLabel(it) },
                    onSelect = { action = it },
                    leadingIcon = siteActionIcon(action),
                    optionIcon = { siteActionIcon(it) },
                )
            }
        },
        confirmButton = {
            FoxholeDialogConfirmButton(
                onClick = {
                    onConfirm(
                        normalizedSiteMaskToken(value)?.let(::listOf).orEmpty(),
                        action,
                    )
                },
                enabled = validationError == null,
            )
        },
        dismissButton = {
            FoxholeDialogDismissButton(onClick = onDismiss)
        },
    )
}

internal fun normalizedSiteMaskToken(value: String): String? {
    val normalized = value.trim().takeIf(String::isNotBlank) ?: return null
    return when {
        normalized.startsWith("cidr:") ||
            normalized.startsWith("kw:") ||
            normalized.startsWith("re:") ||
            normalized.startsWith("*.") -> normalized
        normalized.startsWith(".") -> "*.${normalized.removePrefix(".")}"
        else -> normalized
    }
}

internal fun siteMaskValidationErrorRes(value: String): Int? {
    val token = normalizedSiteMaskToken(value) ?: return R.string.site_exception_validation_error
    return if (isValidSiteMaskToken(token)) null else R.string.site_exception_invalid_error
}

private fun isValidSiteMaskToken(token: String): Boolean =
    when {
        token.startsWith("cidr:") -> isValidCidr(token.removePrefix("cidr:"))
        token.startsWith("kw:") -> isValidKeywordMask(token.removePrefix("kw:"))
        token.startsWith("re:") -> isValidRegexMask(token.removePrefix("re:"))
        token.startsWith("*.") -> isValidDomainName(token.removePrefix("*."))
        else -> isValidDomainName(token)
    }

private fun isValidKeywordMask(value: String): Boolean =
    value.isNotBlank() && value.none { it.isWhitespace() } && "," !in value

private fun isValidRegexMask(value: String): Boolean =
    value.isNotBlank() &&
        runCatching { Regex(value) }.isSuccess

private fun isValidCidr(value: String): Boolean {
    val parts = value.split("/", limit = 2)
    if (parts.size != 2) {
        return false
    }
    val address = parts[0]
    val prefix = parts[1].toIntOrNull() ?: return false
    return if (":" in address) {
        prefix in 0..128 &&
            runCatching {
                java.net.InetAddress.getByName(address) is java.net.Inet6Address
            }.getOrDefault(false)
    } else {
        prefix in 0..32 && isValidIpv4Address(address)
    }
}

private fun isValidIpv4Address(value: String): Boolean {
    val segments = value.split(".")
    return segments.size == 4 &&
        segments.all { segment ->
            segment.isNotEmpty() &&
                segment.all(Char::isDigit) &&
                segment.toIntOrNull()?.let { it in 0..255 } == true
        }
}

private fun isValidDomainName(value: String): Boolean {
    val domain = value.trim().removeSuffix(".")
    if (domain.length !in 3..253 || domain.contains("..")) {
        return false
    }
    if (domain.any { it.isWhitespace() || it in "/:@," }) {
        return false
    }
    val labels = domain.split(".")
    return labels.size >= 2 && labels.all(::isValidDomainLabel)
}

private fun isValidDomainLabel(value: String): Boolean =
    value.length in 1..63 &&
        value.first().isLetterOrDigit() &&
        value.last().isLetterOrDigit() &&
        value.all { it.isLetterOrDigit() || it == '-' }

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
                        )
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
internal fun siteActionLabel(action: RoutingRuleAction): String =
    when (action) {
        RoutingRuleAction.PROXY -> stringResource(R.string.site_action_proxy)
        RoutingRuleAction.DIRECT -> stringResource(R.string.site_action_direct)
        RoutingRuleAction.BLOCK -> stringResource(R.string.site_action_block)
    }

internal fun siteActionIcon(action: RoutingRuleAction): ImageVector =
    when (action) {
        RoutingRuleAction.PROXY -> Icons.Outlined.Public
        RoutingRuleAction.DIRECT -> Icons.Outlined.ArrowOutward
        RoutingRuleAction.BLOCK -> Icons.Outlined.Block
    }
