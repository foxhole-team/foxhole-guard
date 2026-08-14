package com.foxhole.guard.ui.cli.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.foxhole.core.model.TrafficMapUiState
import com.foxhole.guard.ui.HomeRouteUiState
import com.foxhole.guard.ui.cli.CliTheme
import com.foxhole.guard.ui.cli.LocalCliColors

/**
 * Previews live in the debug source set on purpose: `@Preview` comes from `ui-tooling-preview`,
 * which this project pulls in as `debugImplementation` only, so previews in `src/main` would drag
 * a tooling dependency into the shipped APK for nothing.
 *
 * They exist because [CliMapContent] takes plain state and one callback instead of the whole
 * HomeViewModel.
 */
@Preview(name = "map · disabled", widthDp = 360, heightDp = 640)
@Composable
private fun CliMapContentDisabledPreview() {
    CliMapPreviewFrame {
        CliMapContent(
            map = TrafficMapUiState(),
            home = HomeRouteUiState(),
            mapEnabled = false,
            onEnableMap = {},
        )
    }
}

/** Enabled but with nothing measured yet — the empty country table and the bare route scheme. */
@Preview(name = "map · no traffic yet", widthDp = 360, heightDp = 640)
@Composable
private fun CliMapContentEmptyPreview() {
    CliMapPreviewFrame {
        CliMapContent(
            map = TrafficMapUiState(),
            home = HomeRouteUiState(),
            mapEnabled = true,
            onEnableMap = {},
        )
    }
}

@Composable
private fun CliMapPreviewFrame(content: @Composable () -> Unit) {
    CliTheme {
        Box(modifier = Modifier.fillMaxSize().background(LocalCliColors.current.bg)) {
            content()
        }
    }
}
