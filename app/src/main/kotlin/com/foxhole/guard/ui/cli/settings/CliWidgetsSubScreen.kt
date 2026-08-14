package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.HomeWidgetKind
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.onAddHomeWidget
import com.foxhole.guard.ui.onWidgetAlphaPercentChanged
import com.foxhole.guard.ui.onWidgetBlackBackgroundChanged

/**
 * Home-screen widgets, as their own sub-screen in the shape of the backup screen: three explicit
 * launcher/configuration entries followed by the defaults copied by newly added content widgets.
 *
 * They were two rows lost between the language selector and the terminal upkeep, which read as
 * app-wide preferences rather than as what they are — the starting point every newly added widget
 * copies. An individual widget still overrides both in its own configure form.
 */
@Composable
internal fun CliWidgetsSubScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    val widgets = state.settings.widgets
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(
            label = stringResource(R.string.cli_cfg_widgets),
            icon = R.drawable.pix_home,
        )
        CliPanel(
            title = stringResource(R.string.cli_cfg_widgets),
            icon = R.drawable.pix_home,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CliActionRow(
                label = stringResource(R.string.cli_status_widget_label),
                icon = R.drawable.pix_status,
                onTap = { viewModel.onAddHomeWidget(HomeWidgetKind.CONNECTION) },
            )
            CliElbowLine(text = stringResource(R.string.cli_status_widget_description))
            CliRowDivider()
            CliActionRow(
                label = stringResource(R.string.cli_webapps_widget_label),
                icon = R.drawable.pix_webapps,
                onTap = { viewModel.onAddHomeWidget(HomeWidgetKind.WEB_APPS) },
            )
            CliElbowLine(text = stringResource(R.string.cli_webapps_widget_description))
            CliRowDivider()
            CliActionRow(
                label = stringResource(R.string.fox_status_widget_label),
                icon = R.drawable.pix_star,
                onTap = { viewModel.onAddHomeWidget(HomeWidgetKind.STATUS) },
            )
            CliElbowLine(text = stringResource(R.string.fox_status_widget_description))
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliPanel(
            title = stringResource(R.string.cli_widget_config_title),
            icon = R.drawable.pix_home,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CliDropdownRow(
                label = stringResource(R.string.cli_cfg_widget_bg),
                icon = R.drawable.pix_settings,
                value = stringResource(
                    if (widgets.blackBackground) R.string.cli_widget_bg_black else R.string.cli_widget_bg_white,
                ),
                options = listOf(
                    CliDropdownOption(id = WIDGET_BG_OPT_BLACK, label = stringResource(R.string.cli_widget_bg_black)),
                    CliDropdownOption(id = WIDGET_BG_OPT_WHITE, label = stringResource(R.string.cli_widget_bg_white)),
                ),
                selectedId = if (widgets.blackBackground) WIDGET_BG_OPT_BLACK else WIDGET_BG_OPT_WHITE,
                onSelect = { id -> viewModel.onWidgetBlackBackgroundChanged(id == WIDGET_BG_OPT_BLACK) },
            )
            CliWidgetAlphaRow(viewModel = viewModel, alphaPercent = widgets.alphaPercent)
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

/** `opacity ....... [-] 60% [+]` — the stepper sits at the trailing edge like every other control. */
@Composable
private fun CliWidgetAlphaRow(
    viewModel: HomeViewModel,
    alphaPercent: Int,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
    ) {
        Text(
            text = stringResource(R.string.cli_cfg_widget_alpha),
            style = CliType.body,
            color = colors.fg,
            modifier = Modifier.weight(1f),
        )
        CliChip(
            label = "-",
            onClick = { viewModel.onWidgetAlphaPercentChanged((alphaPercent - ALPHA_STEP).coerceAtLeast(0)) },
        )
        Text(text = "$alphaPercent%", style = CliType.body, color = colors.accent)
        CliChip(
            label = "+",
            onClick = { viewModel.onWidgetAlphaPercentChanged((alphaPercent + ALPHA_STEP).coerceAtMost(100)) },
        )
    }
}

private const val ALPHA_STEP = 10
private const val WIDGET_BG_OPT_BLACK = "black"
private const val WIDGET_BG_OPT_WHITE = "white"
