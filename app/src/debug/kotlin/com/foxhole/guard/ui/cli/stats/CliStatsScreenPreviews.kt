package com.foxhole.guard.ui.cli.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsSettings
import com.foxhole.guard.ui.HomeRouteUiState
import com.foxhole.guard.ui.StatisticsRouteUiState
import com.foxhole.guard.ui.cli.CliTheme
import com.foxhole.guard.ui.cli.LocalCliColors

@Preview(name = "stats · settings not hydrated", widthDp = 360, heightDp = 640)
@Composable
private fun CliStatsContentLoadingPreview() {
    CliStatsPreviewFrame {
        CliStatsContent(
            state = StatisticsRouteUiState(settingsHydrated = false),
            home = HomeRouteUiState(),
            actions = previewStatsActions(),
        )
    }
}

@Preview(name = "stats · collection off", widthDp = 360, heightDp = 640)
@Composable
private fun CliStatsContentConsentPreview() {
    CliStatsPreviewFrame {
        CliStatsContent(
            state = StatisticsRouteUiState(settingsHydrated = true),
            home = HomeRouteUiState(),
            actions = previewStatsActions(),
        )
    }
}

@Preview(name = "stats · collecting", widthDp = 360, heightDp = 640)
@Composable
private fun CliStatsContentCollectingPreview() {
    CliStatsPreviewFrame {
        CliStatsContent(
            state = StatisticsRouteUiState(
                settings = Settings(statistics = StatisticsSettings(enabled = true)),
                settingsHydrated = true,
            ),
            home = HomeRouteUiState(),
            actions = previewStatsActions(),
        )
    }
}

private fun previewStatsActions() = CliStatsActions(
    openSettings = {},
    enableStatistics = {},
    selectWindow = {},
)

@Composable
private fun CliStatsPreviewFrame(content: @Composable () -> Unit) {
    CliTheme {
        Box(modifier = Modifier.fillMaxSize().background(LocalCliColors.current.bg)) {
            content()
        }
    }
}
