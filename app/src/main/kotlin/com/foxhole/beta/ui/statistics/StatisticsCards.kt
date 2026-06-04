package com.foxhole.beta.ui.statistics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class StatisticsCardTone {
    Neutral,
    Elevated,
    Warning,
    Danger,
}

@Immutable
internal data class StatisticsMetricTileModel(
    val label: String,
    val value: String,
    val caption: String? = null,
    val accent: Color? = null,
)

@Composable
internal fun StatisticsDashboardCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    tone: StatisticsCardTone = StatisticsCardTone.Neutral,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = statisticsVisualTokens()
    val shape = RoundedCornerShape(tokens.dimens.cardRadius)
    val borderColor =
        when (tone) {
            StatisticsCardTone.Neutral,
            StatisticsCardTone.Elevated,
            -> tokens.colors.cardBorder
            StatisticsCardTone.Warning -> tokens.colors.warning.copy(alpha = 0.42f)
            StatisticsCardTone.Danger -> tokens.colors.danger.copy(alpha = 0.48f)
        }
    val container =
        when (tone) {
            StatisticsCardTone.Elevated -> tokens.colors.cardContainerElevated
            else -> tokens.colors.cardContainer
        }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = shape,
        border = BorderStroke(tokens.dimens.cardBorderWidth, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(tokens.dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(tokens.dimens.cardVerticalGap),
        ) {
            StatisticsHeader(
                icon = icon,
                title = title,
                subtitle = subtitle,
                tone = tone,
                trailing = trailing,
            )
            content()
        }
    }
}

@Composable
internal fun StatisticsHeader(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tone: StatisticsCardTone = StatisticsCardTone.Neutral,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val tokens = statisticsVisualTokens()
    val iconTint =
        when (tone) {
            StatisticsCardTone.Warning -> tokens.colors.warning
            StatisticsCardTone.Danger -> tokens.colors.danger
            else -> tokens.colors.headerIconTint
        }
    val iconContainer =
        when (tone) {
            StatisticsCardTone.Warning -> tokens.colors.warning.copy(alpha = 0.16f)
            StatisticsCardTone.Danger -> tokens.colors.danger.copy(alpha = 0.16f)
            else -> tokens.colors.headerIconContainer
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = iconContainer,
            contentColor = iconTint,
            tonalElevation = 0.dp,
        ) {
            Box(
                modifier = Modifier.size(tokens.dimens.iconBoxSize),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(tokens.dimens.iconSize),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.mutedText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

@Composable
internal fun StatisticsMetricTileGrid(
    metrics: List<StatisticsMetricTileModel>,
    modifier: Modifier = Modifier,
    columns: Int = 2,
) {
    if (metrics.isEmpty()) {
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        metrics.chunked(columns.coerceAtLeast(1)).forEach { rowMetrics ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowMetrics.forEach { metric ->
                    StatisticsMetricTile(
                        metric = metric,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - rowMetrics.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun StatisticsMetricTile(
    metric: StatisticsMetricTileModel,
    modifier: Modifier = Modifier,
) {
    val tokens = statisticsVisualTokens()
    Surface(
        modifier = modifier.heightIn(min = 64.dp),
        shape = RoundedCornerShape(tokens.dimens.tileRadius),
        color = tokens.colors.metricTileContainer,
        border =
        BorderStroke(
            tokens.dimens.cardBorderWidth,
            metric.accent?.copy(alpha = 0.42f) ?: tokens.colors.metricTileBorder,
        ),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = metric.label,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = metric.value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = metric.accent ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (metric.caption != null) {
                Text(
                    text = metric.caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun StatisticsEmptyState(
    icon: ImageVector,
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = statisticsVisualTokens()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(tokens.dimens.innerRadius),
        color = tokens.colors.rowContainer,
        border = BorderStroke(tokens.dimens.cardBorderWidth, tokens.colors.cardBorder),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(tokens.dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = tokens.colors.headerIconContainer,
                    contentColor = tokens.colors.headerIconTint,
                    tonalElevation = 0.dp,
                ) {
                    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (body != null) {
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.mutedText,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (actionLabel != null && onAction != null) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = onAction) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

@Composable
internal fun StatisticsDivider(modifier: Modifier = Modifier) {
    val tokens = statisticsVisualTokens()
    HorizontalDivider(
        modifier = modifier,
        thickness = tokens.dimens.hairlineWidth,
        color = tokens.colors.divider,
    )
}

@Composable
internal fun StatisticsRowSurface(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    borderColor: Color? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = statisticsVisualTokens()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(tokens.dimens.innerRadius),
        color = if (selected) tokens.colors.rowContainerSelected else tokens.colors.rowContainer,
        border = BorderStroke(tokens.dimens.cardBorderWidth, borderColor ?: tokens.colors.metricTileBorder),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = tokens.dimens.rowHorizontalPadding,
                    vertical = tokens.dimens.rowVerticalPadding,
                ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
