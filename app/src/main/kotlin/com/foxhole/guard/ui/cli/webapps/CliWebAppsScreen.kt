package com.foxhole.guard.ui.cli.webapps

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.guard.R
import com.foxhole.guard.core.data.WebAppEntity
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.WebAppAddUiState
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.cliCombinedPressable
import com.foxhole.guard.ui.cli.components.cliDashedBorder
import com.foxhole.guard.ui.confirmAddWebApp
import com.foxhole.guard.ui.deleteWebAppCredentials
import com.foxhole.guard.ui.dismissWebAppAdd
import com.foxhole.guard.ui.loadWebAppCredentials
import com.foxhole.guard.ui.openWebApp
import com.foxhole.guard.ui.previewWebApp
import com.foxhole.guard.ui.removeWebApp
import com.foxhole.guard.ui.renameWebApp
import com.foxhole.guard.ui.saveWebAppCredentials
import com.foxhole.guard.ui.webAppIconFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The webapps screen: a grid of added apps with unread badges, long-press to rename or delete,
 * and below it the add-by-URL form with a name and icon preview. Tapping an icon opens the
 * full-screen frame.
 */
@Composable
internal fun CliWebAppsScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val apps by viewModel.webAppsState.collectAsStateWithLifecycle()
    val addState by viewModel.webAppAddState.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selected = apps.firstOrNull { app -> app.id == selectedId }

    val colors = LocalCliColors.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_dock_webapps), icon = R.drawable.pix_webapps)
        CliWebAppAddPanel(viewModel = viewModel, addState = addState)
        Spacer(modifier = Modifier.height(CliSpacing.md))
        // Everything added lives in its own zone below the form: a static dashed border with a
        // caption, matching the profile import zone.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .cliDashedBorder(colors.border)
                .padding(CliSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.cli_webapps_yours),
                style = CliType.small,
                color = colors.dim,
                modifier = Modifier.padding(bottom = CliSpacing.xs),
            )
            if (apps.isEmpty()) {
                CliElbowLine(text = stringResource(R.string.cli_webapps_empty))
            } else {
                CliWebAppsGrid(
                    viewModel = viewModel,
                    apps = apps,
                    onOpen = { app -> viewModel.openWebApp(app.id) },
                    onSelect = { app -> selectedId = if (selectedId == app.id) null else app.id },
                )
            }
            selected?.let { app ->
                Spacer(modifier = Modifier.height(CliSpacing.sm))
                CliWebAppActionsRows(
                    viewModel = viewModel,
                    app = app,
                    onDone = { selectedId = null },
                )
            }
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

@Composable
private fun CliWebAppsGrid(
    viewModel: HomeViewModel,
    apps: List<WebAppEntity>,
    onOpen: (WebAppEntity) -> Unit,
    onSelect: (WebAppEntity) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Three to six per row: however many 72dp cells fit, clamped to that range.
        val columns = (maxWidth / 72.dp).toInt().coerceIn(3, 6)
        Column(verticalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
            apps.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CliSpacing.xs),
                ) {
                    row.forEach { app ->
                        CliWebAppCell(
                            viewModel = viewModel,
                            app = app,
                            onOpen = onOpen,
                            onSelect = onSelect,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CliWebAppCell(
    viewModel: HomeViewModel,
    app: WebAppEntity,
    onOpen: (WebAppEntity) -> Unit,
    onSelect: (WebAppEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    Column(
        modifier = modifier
            .cliCombinedPressable(onLongClick = { onSelect(app) }) { onOpen(app) }
            .padding(vertical = CliSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            CliWebAppIcon(viewModel = viewModel, app = app, size = 40.dp)
            if (app.badgeCount > 0) {
                Text(
                    text = if (app.badgeCount > 99) "99+" else app.badgeCount.toString(),
                    style = CliType.small,
                    color = colors.bg,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-4).dp)
                        .background(colors.accent)
                        .padding(horizontal = 2.dp),
                )
            }
        }
        Text(
            text = app.name.lowercase(),
            style = CliType.small,
            color = colors.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CliWebAppIcon(
    viewModel: HomeViewModel,
    app: WebAppEntity,
    size: Dp,
) {
    val colors = LocalCliColors.current
    // Both steps touch disk: webAppIconFile does File.exists() and decodeFile reads the whole
    // file. In the composable body they ran synchronously on every grid recomposition, so the path
    // is cached and decoding moved to IO.
    val iconPath = remember(app.iconPath) { viewModel.webAppIconFile(app)?.absolutePath }
    val bitmap by produceState<ImageBitmap?>(null, iconPath) {
        value = iconPath?.let { path ->
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
        }
    }
    val icon = bitmap
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = app.name,
            modifier = Modifier.size(size),
            filterQuality = FilterQuality.Medium,
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .border(1.dp, colors.border),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.name.take(1).uppercase(),
                style = CliType.title,
                color = colors.accent,
            )
        }
    }
}

/** Terminal action row for the selected app: rename, delete, cancel. */
@Composable
private fun CliWebAppActionsRows(
    viewModel: HomeViewModel,
    app: WebAppEntity,
    onDone: () -> Unit,
) {
    val colors = LocalCliColors.current
    val scope = rememberCoroutineScope()
    var renameOpen by rememberSaveable(app.id) { mutableStateOf(false) }
    var renameValue by rememberSaveable(app.id) { mutableStateOf(app.name) }
    var credsOpen by rememberSaveable(app.id) { mutableStateOf(false) }
    // Credentials must never enter Compose saved state (Bundle/snapshot). Keep them only for the
    // lifetime of this composition and clear references as soon as the editor closes.
    var credsLogin by remember(app.id) { mutableStateOf("") }
    var credsPassword by remember(app.id) { mutableStateOf("") }
    var credsLoaded by remember(app.id) { mutableStateOf(false) }
    fun clearCredentialEditor() {
        credsLogin = ""
        credsPassword = ""
        credsLoaded = false
        credsOpen = false
    }
    DisposableEffect(app.id) {
        onDispose {
            credsLogin = ""
            credsPassword = ""
        }
    }
    LaunchedEffect(credsOpen) {
        if (credsOpen && !credsLoaded) {
            viewModel.loadWebAppCredentials(app.id)?.let { creds ->
                credsLogin = creds.login
                credsPassword = creds.password
            }
            credsLoaded = true
        }
    }
    CliElbowLine(text = app.name.lowercase(), color = colors.accent)
    if (credsOpen) {
        CliInputRow(
            prompt = stringResource(R.string.cli_webapps_login),
            value = credsLogin,
            onValueChange = { credsLogin = it },
            autoFocus = true,
        )
        CliInputRow(
            prompt = stringResource(R.string.cli_webapps_password),
            value = credsPassword,
            onValueChange = { credsPassword = it },
            password = true,
        )
        CliElbowLine(text = stringResource(R.string.cli_webapps_creds_note))
        Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
            CliChip(
                label = stringResource(R.string.cli_webapps_save),
                color = colors.ok,
                // Close the editor only on success: the vault can refuse (Keystore, IO), and a
                // blindly closed panel read as "password saved" when nothing was stored.
                onClick = {
                    scope.launch {
                        if (viewModel.saveWebAppCredentials(app.id, credsLogin, credsPassword)) {
                            clearCredentialEditor()
                            onDone()
                        }
                    }
                },
            )
            CliChip(
                label = stringResource(R.string.cli_webapps_delete),
                color = colors.err,
                onClick = {
                    viewModel.deleteWebAppCredentials(app.id)
                    clearCredentialEditor()
                    onDone()
                },
            )
            CliChip(
                label = stringResource(R.string.cli_webapps_cancel),
                onClick = ::clearCredentialEditor,
            )
        }
    } else if (renameOpen) {
        CliInputRow(
            prompt = stringResource(R.string.cli_webapps_name),
            value = renameValue,
            onValueChange = { renameValue = it },
            onSubmit = {
                viewModel.renameWebApp(app.id, renameValue)
                onDone()
            },
            trailingChipLabel = stringResource(R.string.cli_webapps_save),
            onTrailingChip = {
                viewModel.renameWebApp(app.id, renameValue)
                onDone()
            },
            autoFocus = true,
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
            CliChip(
                label = stringResource(R.string.cli_webapps_rename),
                onClick = { renameOpen = true },
            )
            CliChip(
                label = stringResource(R.string.cli_webapps_creds),
                onClick = { credsOpen = true },
            )
            CliChip(
                label = stringResource(R.string.cli_webapps_delete),
                color = colors.err,
                onClick = {
                    viewModel.removeWebApp(app.id)
                    onDone()
                },
            )
            CliChip(
                label = stringResource(R.string.cli_webapps_cancel),
                onClick = onDone,
            )
        }
    }
}

@Composable
private fun CliWebAppAddPanel(
    viewModel: HomeViewModel,
    addState: WebAppAddUiState,
) {
    val colors = LocalCliColors.current
    var url by rememberSaveable { mutableStateOf("") }
    CliPanel(
        title = stringResource(R.string.cli_webapps_add),
        icon = R.drawable.pix_webapps,
        modifier = Modifier.fillMaxWidth(),
    ) {
        when (addState) {
            WebAppAddUiState.Idle, is WebAppAddUiState.Error -> {
                CliInputRow(
                    prompt = stringResource(R.string.cli_webapps_add_url),
                    value = url,
                    onValueChange = { url = it },
                    onSubmit = { if (url.isNotBlank()) viewModel.previewWebApp(url) },
                    trailingChipLabel = stringResource(R.string.cli_webapps_fetch),
                    onTrailingChip = { if (url.isNotBlank()) viewModel.previewWebApp(url) },
                )
                if (addState is WebAppAddUiState.Error) {
                    CliElbowLine(text = addState.message, color = colors.err)
                }
            }
            WebAppAddUiState.Loading ->
                CliElbowLine(text = stringResource(R.string.cli_webapps_fetching))
            is WebAppAddUiState.Ready -> {
                var name by rememberSaveable(addState.preview.url) {
                    mutableStateOf(addState.preview.name)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
                ) {
                    CliWebAppPreviewIcon(bytes = addState.preview.iconBytes)
                    Text(
                        text = addState.preview.url,
                        style = CliType.small,
                        color = colors.dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                CliInputRow(
                    prompt = stringResource(R.string.cli_webapps_name),
                    value = name,
                    onValueChange = { name = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
                    CliChip(
                        label = stringResource(R.string.cli_webapps_save),
                        color = colors.ok,
                        onClick = {
                            viewModel.confirmAddWebApp(name)
                            url = ""
                        },
                    )
                    CliChip(
                        label = stringResource(R.string.cli_webapps_cancel),
                        color = colors.err,
                        onClick = viewModel::dismissWebAppAdd,
                    )
                }
            }
        }
    }
}

@Composable
private fun CliWebAppPreviewIcon(bytes: ByteArray?) {
    val colors = LocalCliColors.current
    val bitmap: ImageBitmap? = remember(bytes) {
        bytes?.let { data -> BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            filterQuality = FilterQuality.Medium,
        )
    } else {
        Box(modifier = Modifier.size(24.dp).border(1.dp, colors.border))
    }
}
