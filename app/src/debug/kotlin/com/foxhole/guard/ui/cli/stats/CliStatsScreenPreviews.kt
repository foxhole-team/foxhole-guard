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

/**
 * Previews live in the debug source set on purpose: `@Preview` comes from `ui-tooling-preview`,
 * which this project pulls in as `debugImplementation` only, so previews in `src/main` would drag
 * a tooling dependency into the shipped APK for nothing.
 *
 * They exist because [CliStatsContent] takes plain state and three callbacks instead of the whole
 * HomeViewModel — the first screens in this front end that can be composed without one.
 */
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

/** The state a fresh install actually opens on: collection is opt-in and defaults to off. */
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

/** Collection on, dashboard not built yet — the third and last gate before the tables. */
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
