package com.foxhole.guard.ui.cli.settings

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliBottomChromeClearance
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliExternalLinkSheet
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.emitError
import com.foxhole.guard.ui.exportDiagnostics
import com.foxhole.guard.widget.FOX_STATUS_ANIMATION_FRAMES
import com.foxhole.guard.widget.FOX_STATUS_FRAME_DURATION_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CliAboutSubScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logShareChooser = stringResource(R.string.cli_about_log_share_chooser)
    val logShareFailed = stringResource(R.string.cli_about_log_share_failed)
    var pendingExternalUrl by remember { mutableStateOf<String?>(null) }
    var preparingAppJournal by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_cfg_more_about), icon = R.drawable.pix_star)

        Column(

            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalCliBottomChromeClearance.current),

        ) {
            CliAboutDashedBlock(
                title = stringResource(R.string.cli_about_title),
                icon = R.drawable.pix_info,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CliAboutAnimatedFox()
                    Spacer(modifier = Modifier.size(CliSpacing.sm))
                    CliAboutVersionTable(
                        appVersion = state.appVersion,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliAboutDashedBlock(
                title = stringResource(R.string.about_licenses_title),
                icon = R.drawable.pix_info,
            ) {
                ABOUT_LICENSES.forEachIndexed { index, (component, license) ->
                    if (index > 0) CliRowDivider()
                    CliKeyValue(key = component, value = license)
                }
            }
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliAboutDashedBlock(
                title = stringResource(R.string.cli_about_links_title),
                icon = R.drawable.pix_link,
                infoText = stringResource(R.string.cli_about_log_share_note),
            ) {
                ABOUT_LINKS.forEachIndexed { index, (label, url) ->
                    if (index > 0) CliRowDivider()
                    CliActionRow(
                        label = label,
                        icon = R.drawable.pix_link,
                        value = stringResource(R.string.cli_about_link_open),
                        onTap = { pendingExternalUrl = url },
                    )
                }
                Spacer(modifier = Modifier.height(CliSpacing.sm))
                CliRowDivider()
                CliActionRow(
                    label = stringResource(R.string.cli_about_log_share),
                    icon = R.drawable.pix_export,
                    enabled = !preparingAppJournal,
                    onTap = {
                        if (!preparingAppJournal) {
                            preparingAppJournal = true
                            scope.launch {
                                val result = runCatching {
                                    withContext(Dispatchers.IO) { viewModel.exportDiagnostics() }
                                }
                                preparingAppJournal = false
                                result.onSuccess { shareIntent ->
                                    runCatching {
                                        context.startActivity(
                                            Intent.createChooser(
                                                shareIntent,
                                                logShareChooser,
                                            ),
                                        )
                                    }.onFailure {
                                        viewModel.emitError(logShareFailed)
                                    }
                                }.onFailure {
                                    viewModel.emitError(logShareFailed)
                                }
                            }
                        }
                    },
                )
            }
            Spacer(modifier = Modifier.height(CliSpacing.sm))
        }
    }
    pendingExternalUrl?.let { url ->
        CliExternalLinkSheet(url = url, onDismiss = { pendingExternalUrl = null })
    }
}

@Composable
private fun CliAboutAnimatedFox() {
    var frameIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(FOX_STATUS_FRAME_DURATION_MS)
            frameIndex = (frameIndex + 1) % FOX_STATUS_ANIMATION_FRAMES.size
        }
    }
    Image(
        painter = painterResource(FOX_STATUS_ANIMATION_FRAMES[frameIndex]),
        contentDescription = null,
        modifier = Modifier.size(ABOUT_FOX_SIZE),
    )
}

@Composable
private fun CliAboutVersionTable(
    appVersion: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        CliKeyValue(key = "FoxHole Guard", value = appVersion)
        CliRowDivider()
        CliKeyValue(key = "FoxHole Core", value = BuildConfig.FOXCORE_SOURCE_VERSION)
        CliRowDivider()
        CliKeyValue(key = "Arti", value = BuildConfig.ARTI_VERSION)
    }
}

@Composable
private fun CliAboutDashedBlock(
    title: String,
    @DrawableRes icon: Int,
    infoText: String? = null,
    content: @Composable () -> Unit,
) {
    CliPanel(
        title = title,
        icon = icon,
        modifier = Modifier.fillMaxWidth(),
        infoText = infoText,
    ) {
        content()
    }
}

private val ABOUT_FOX_SIZE = 72.dp

// Component names and SPDX identifiers are intentionally not localized.
private val ABOUT_LICENSES =
    listOf(
        "FoxHole Guard" to "GPL-3.0-or-later",
        "FoxHole Core" to "GPL-3.0-or-later",
        "Arti (Tor)" to "MIT OR Apache-2.0",
        "lyrebird" to "BSD-3-Clause",
        "conjure-client" to "BSD-3-Clause",
        "i2pd (PurpleI2P)" to "BSD-3-Clause",
        "SQLCipher" to "BSD-3-Clause",
        "OkHttp" to "Apache-2.0",
        "AndroidX / Jetpack Compose" to "Apache-2.0",
        "Kotlin / kotlinx" to "Apache-2.0",
        "Protocol Buffers" to "BSD-3-Clause",
        "ZXing Android Embedded" to "Apache-2.0",
        "lazysodium-android" to "MPL-2.0",
        "JNA" to "LGPL-2.1 / Apache-2.0",
        "AdGuard DNS filter" to "GPL-3.0",
        "DB-IP / ip-location-db" to "CC BY 4.0",
        "Stalkerware indicators (Echap)" to "CC BY 4.0",
        "Silkscreen / Press Start 2P / LanaPixel" to "OFL-1.1",
        "Inter / JetBrains Mono" to "OFL-1.1",
        "1-bit Pixel Icons (Nikoichu)" to "CC0-1.0",
        "Tabler Icons" to "MIT",
    )

private val ABOUT_LINKS = listOf(
    "FoxHole Guard" to "https://github.com/foxhole-team/foxhole-guard",
    "FoxHole Core" to "https://github.com/foxhole-team/foxhole-core",
    "FoxHole DB" to "https://github.com/foxhole-team/foxhole-db",
    "Telegram" to "https://t.me/foxhole_team",
)
