package com.foxhole.guard.ui.cli.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.ConnectionState
import com.foxhole.guard.R
import com.foxhole.guard.core.sharing.FileShareUiItem
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliContextHelpButton
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.cli.components.CliYesNoRow
import com.foxhole.guard.ui.fileShareInvitationIntent
import com.foxhole.guard.ui.publishFileShare
import com.foxhole.guard.ui.revokeFileShare
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Encrypted, session-only download publication through the active Tor route. */
@Composable
internal fun CliFileShareSubScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.fileShareState.collectAsStateWithLifecycle()
    val homeState by viewModel.controlUiState.collectAsStateWithLifecycle()
    val form = remember { FileShareFormState() }
    DisposableEffect(form) {
        onDispose(form::clearSecrets)
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val request = form.takePending()
            if (uri != null && request != null) {
                viewModel.publishFileShare(uri, request.lifetimeMs, request.maxDownloads, request.password)
                form.password = ""
            } else {
                request?.password?.fill('\u0000')
            }
        }
    val torReady = homeState.connection.state == ConnectionState.CONNECTED && homeState.connection.torActive

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(
            label = stringResource(R.string.cli_file_share_title),
            icon = R.drawable.pix_export,
            trailing = { CliContextHelpButton(bodyRes = R.string.cli_help_file_share_body) },
        )
        FileShareCreatePanel(
            form = form,
            torReady = torReady,
            busy = state.busy,
            onChoose = {
                form.preparePending()
                picker.launch(arrayOf("*/*"))
            },
            onStartTor = { viewModel.onTorRuntimeToggled(true) },
        )
        ActiveFileShares(
            shares = state.active,
            viewModel = viewModel,
        )
        Spacer(modifier = Modifier.height(CliSpacing.md))
    }
}

@Composable
private fun FileShareCreatePanel(
    form: FileShareFormState,
    torReady: Boolean,
    busy: Boolean,
    onChoose: () -> Unit,
    onStartTor: () -> Unit,
) {
    val colors = LocalCliColors.current
    CliPanel(
        title = stringResource(R.string.cli_file_share_new),
        icon = R.drawable.pix_link,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.cli_file_share_security_note),
            style = CliType.small,
            color = colors.dim,
            modifier = Modifier.padding(bottom = CliSpacing.xs),
        )
        if (!torReady) {
            CliElbowLine(
                text = stringResource(R.string.cli_file_share_tor_idle),
                color = colors.warn,
            )
            // Without this the screen is a dead end: publishing needs TOR, and the only control was
            // a disabled row with nothing to press. Starting it here is the way forward.
            CliActionRow(
                label = stringResource(R.string.cli_file_share_start_tor),
                onTap = onStartTor,
            )
        }
        CliDropdownRow(
            label = stringResource(R.string.cli_file_share_expiry),
            value = lifetimeLabel(form.lifetimeMs),
            options = listOf(
                CliDropdownOption("1h", stringResource(R.string.cli_file_share_expiry_1h)),
                CliDropdownOption("24h", stringResource(R.string.cli_file_share_expiry_24h)),
                CliDropdownOption("7d", stringResource(R.string.cli_file_share_expiry_7d)),
            ),
            selectedId = lifetimeId(form.lifetimeMs),
            onSelect = { id -> form.lifetimeMs = lifetimeForId(id) },
        )
        CliDropdownRow(
            label = stringResource(R.string.cli_file_share_downloads),
            value = form.maxDownloads.toString(),
            options = listOf(1, 3, 10).map { value ->
                CliDropdownOption(value.toString(), value.toString())
            },
            selectedId = form.maxDownloads.toString(),
            onSelect = { id -> id.toIntOrNull()?.let { form.maxDownloads = it } },
        )
        CliToggleRow(
            label = stringResource(R.string.cli_file_share_password),
            checked = form.passwordEnabled,
            onToggle = { enabled ->
                form.passwordEnabled = enabled
                if (!enabled) form.password = ""
            },
        )
        if (form.passwordEnabled) {
            CliInputRow(
                prompt = "pass",
                value = form.password,
                password = true,
                onValueChange = { value -> form.password = value.take(128) },
            )
            Text(
                text = stringResource(R.string.cli_file_share_password_note),
                style = CliType.small,
                color = colors.dim,
            )
        }
        val actionLabel =
            if (busy) {
                stringResource(R.string.cli_file_share_preparing)
            } else {
                stringResource(R.string.cli_file_share_choose)
            }
        val canPublish =
            torReady && !busy && (!form.passwordEnabled || validUiPassword(form.password))
        CliActionRow(
            label = actionLabel,
            enabled = canPublish,
            onTap = onChoose,
        )
    }
}

@Composable
private fun ActiveFileShares(
    shares: List<FileShareUiItem>,
    viewModel: HomeViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revokeConfirmation by remember { mutableStateOf<String?>(null) }
    shares.forEach { share ->
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        ActiveFileSharePanel(
            share = share,
            confirming = revokeConfirmation == share.id,
            onShare = {
                scope.launch {
                    viewModel.fileShareInvitationIntent(share.id)?.let { intent ->
                        context.startActivity(Intent.createChooser(intent, null))
                    }
                }
            },
            onRevoke = { revokeConfirmation = share.id },
            onConfirmRevoke = {
                revokeConfirmation = null
                viewModel.revokeFileShare(share.id)
            },
            onCancelRevoke = { revokeConfirmation = null },
        )
    }
}

@Composable
private fun ActiveFileSharePanel(
    share: FileShareUiItem,
    confirming: Boolean,
    onShare: () -> Unit,
    onRevoke: () -> Unit,
    onConfirmRevoke: () -> Unit,
    onCancelRevoke: () -> Unit,
) {
    val colors = LocalCliColors.current
    val expiry =
        remember(share.expiresAtMs) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(share.expiresAtMs))
        }
    val passwordLabel =
        if (share.passwordProtected) {
            R.string.cli_file_share_password_yes
        } else {
            R.string.cli_file_share_password_no
        }
    CliPanel(
        title = share.displayName,
        icon = R.drawable.pix_lock,
        modifier = Modifier.fillMaxWidth(),
    ) {
        FileShareFact(stringResource(R.string.cli_file_share_size_value, formatBytes(share.sizeBytes)))
        FileShareFact(stringResource(R.string.cli_file_share_expiry_value, expiry))
        FileShareFact(
            stringResource(
                R.string.cli_file_share_downloads_value,
                share.downloadsAuthorized,
                share.maxDownloads,
            ),
        )
        FileShareFact(stringResource(passwordLabel))
        CliActionRow(
            label = stringResource(R.string.cli_file_share_send_invitation),
            onTap = onShare,
        )
        CliActionRow(
            label = stringResource(R.string.cli_file_share_revoke),
            onTap = onRevoke,
        )
        if (confirming) {
            CliYesNoRow(
                question = stringResource(R.string.cli_file_share_revoke_confirm),
                onYes = onConfirmRevoke,
                onNo = onCancelRevoke,
            )
        }
    }
}

@Composable
private fun FileShareFact(text: String) {
    Text(
        text = text,
        style = CliType.small,
        color = LocalCliColors.current.dim,
    )
}

private class FileShareFormState {
    var lifetimeMs by mutableLongStateOf(FILE_SHARE_LIFETIME_DAY_MS)
    var maxDownloads by mutableIntStateOf(1)
    var passwordEnabled by mutableStateOf(false)
    var password by mutableStateOf("")
    private var pending by mutableStateOf<PendingFileShare?>(null)

    fun preparePending() {
        pending?.password?.fill('\u0000')
        pending =
            PendingFileShare(
                lifetimeMs = lifetimeMs,
                maxDownloads = maxDownloads,
                password = password.takeIf { passwordEnabled }?.toCharArray(),
            )
    }

    fun takePending(): PendingFileShare? = pending.also { pending = null }

    fun clearSecrets() {
        pending?.password?.fill('\u0000')
        pending = null
        password = ""
    }
}

private data class PendingFileShare(
    val lifetimeMs: Long,
    val maxDownloads: Int,
    val password: CharArray?,
)

private fun validUiPassword(value: String): Boolean =
    value.length in 8..128 && value.all { character -> character.code in 0x21..0x7e }

private fun lifetimeId(value: Long): String =
    when (value) {
        FILE_SHARE_LIFETIME_HOUR_MS -> "1h"
        FILE_SHARE_LIFETIME_WEEK_MS -> "7d"
        else -> "24h"
    }

private fun lifetimeForId(id: String): Long =
    when (id) {
        "1h" -> FILE_SHARE_LIFETIME_HOUR_MS
        "7d" -> FILE_SHARE_LIFETIME_WEEK_MS
        else -> FILE_SHARE_LIFETIME_DAY_MS
    }

@Composable
private fun lifetimeLabel(value: Long): String =
    stringResource(
        when (value) {
            FILE_SHARE_LIFETIME_HOUR_MS -> R.string.cli_file_share_expiry_1h
            FILE_SHARE_LIFETIME_WEEK_MS -> R.string.cli_file_share_expiry_7d
            else -> R.string.cli_file_share_expiry_24h
        },
    )

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

private const val FILE_SHARE_LIFETIME_HOUR_MS = 60L * 60L * 1_000L
private const val FILE_SHARE_LIFETIME_DAY_MS = 24L * FILE_SHARE_LIFETIME_HOUR_MS
private const val FILE_SHARE_LIFETIME_WEEK_MS = 7L * FILE_SHARE_LIFETIME_DAY_MS
