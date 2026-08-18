package com.foxhole.guard.ui.cli.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliRowDivider

@Immutable
internal data class CliQuickStartItem(
    val text: String,
    @DrawableRes val icon: Int,
)

internal fun quickStartItems(body: String): List<CliQuickStartItem> =
    body.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapIndexed { index, text ->
            CliQuickStartItem(
                text = text,
                icon = QUICK_START_ICONS.getOrElse(index) { R.drawable.pix_info },
            )
        }
        .toList()

@Composable
internal fun CliQuickStartItems(
    body: String,
    framed: Boolean,
    modifier: Modifier = Modifier,
) {
    val items = remember(body) { quickStartItems(body) }
    CliIconTextItems(items = items, framed = framed, modifier = modifier)
}

@Composable
internal fun CliIconTextItems(
    items: List<CliQuickStartItem>,
    framed: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CliSpacing.sm),
    ) {
        items.forEachIndexed { index, item ->
            if (framed) {
                CliPanel(modifier = Modifier.fillMaxWidth()) {
                    CliQuickStartItemRow(item)
                }
            } else {
                if (index > 0) CliRowDivider()
                CliQuickStartItemRow(item)
            }
        }
    }
}

@Composable
private fun CliQuickStartItemRow(item: CliQuickStartItem) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        CliPixIcon(
            id = item.icon,
            contentDescription = null,
            size = 16.dp,
            tint = colors.accent,
        )
        Spacer(modifier = Modifier.width(CliSpacing.sm))
        Text(
            text = item.text,
            style = CliType.body.copy(lineHeight = QUICK_START_LINE_HEIGHT),
            color = colors.fg,
            modifier = Modifier.weight(1f),
        )
    }
}

private val QUICK_START_ICONS = listOf(
    R.drawable.pix_shield,
    R.drawable.pix_profiles,
    R.drawable.pix_power,
    R.drawable.pix_trash,
    R.drawable.pix_status,
    R.drawable.pix_link,
    R.drawable.pix_settings,
    R.drawable.pix_check,
    R.drawable.pix_globe,
    R.drawable.pix_update,
    R.drawable.pix_lock,
    R.drawable.pix_incognito,
)

private val QUICK_START_LINE_HEIGHT = 22.sp
