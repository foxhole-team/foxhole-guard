package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.core.security.PasswordSetupResult
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.changeAppLockPassword
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliConfirmSheet
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliLoadingRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.disableAppLockPassword
import com.foxhole.guard.ui.enableAppLockPassword
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun CliPinPanel(
    viewModel: HomeViewModel,
    flow: CliPinFlow,
    onClose: () -> Unit,
    eventMonitoring: Boolean = true,
    appJournal: Boolean = true,
) {
    val colors = LocalCliColors.current
    val scope = rememberCoroutineScope()
    val state = remember { CliPinPanelState(flow) }

    fun submit() {
        state.step = CliPinStep.WORKING
        scope.launch {
            val done =
                state.applyResult(
                    performPinOperation(
                        viewModel = viewModel,
                        flow = flow,
                        state = state,
                        eventMonitoring = eventMonitoring,
                        appJournal = appJournal,
                    ),
                )
            if (done) {
                delay(DONE_CLOSE_DELAY_MS)
                onClose()
            }
        }
    }

    CliPanel(
        title = stringResource(panelTitleRes(flow)),
        icon = R.drawable.pix_lock,
        titleColor = colors.accent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        when (state.step) {
            CliPinStep.CURRENT -> PinEntryBody(
                pin = state.currentPin,
                onPin = { state.currentPin = it },
                hintRes = if (flow == CliPinFlow.DISABLE) {
                    R.string.cli_lock_disable_warning
                } else {
                    R.string.cli_lock_hint_digits
                },
                errorRes = state.errorRes,
                onFilled = { state.advanceFrom(CliPinStep.CURRENT, ::submit) },
                onCancel = onClose,
            )
            CliPinStep.NEW -> PinEntryBody(
                pin = state.newPin,
                onPin = { state.newPin = it },
                hintRes = R.string.cli_lock_hint_digits,
                errorRes = state.errorRes,
                onFilled = { state.advanceFrom(CliPinStep.NEW, ::submit) },
                onCancel = onClose,
            )
            CliPinStep.CONFIRM -> PinEntryBody(
                pin = state.confirmPin,
                onPin = { state.confirmPin = it },
                hintRes = R.string.cli_lock_hint_repeat,
                errorRes = state.errorRes,
                onFilled = { state.advanceFrom(CliPinStep.CONFIRM, ::submit) },
                onCancel = onClose,
            )
            CliPinStep.WORKING -> CliLoadingRow(text = stringResource(R.string.cli_lock_working))
            CliPinStep.DONE -> Text(
                text = stringResource(doneTextRes(flow)),
                style = CliType.body,
                color = colors.ok,
            )
        }
    }
}

@Composable
internal fun CliEncryptionConsentSheet(
    flow: CliPinFlow,
    eventMonitoring: Boolean,
    appJournal: Boolean,
    onEventMonitoring: (Boolean) -> Unit,
    onAppJournal: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val enabling = flow == CliPinFlow.ENABLE
    CliConfirmSheet(
        title = stringResource(if (enabling) R.string.cli_lock_title_enable else R.string.cli_lock_title_disable),
        icon = R.drawable.pix_lock,
        question = stringResource(
            if (enabling) R.string.cli_lock_consent_body else R.string.cli_lock_disable_warning,
        ),
        confirmLabel = stringResource(R.string.cli_lock_consent_yes),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        content = if (!enabling) {
            null
        } else {
            {
                CliToggleRow(
                    label = stringResource(R.string.cli_lock_companion_monitoring),
                    icon = R.drawable.pix_status,
                    checked = eventMonitoring,
                    onToggle = onEventMonitoring,
                )
                CliToggleRow(
                    label = stringResource(R.string.cli_lock_companion_journal),
                    icon = R.drawable.pix_journal,
                    checked = appJournal,
                    onToggle = onAppJournal,
                )
            }
        },
    )
}

internal enum class CliPinFlow { ENABLE, CHANGE, DISABLE }

internal enum class CliPinStep { CURRENT, NEW, CONFIRM, WORKING, DONE }

private const val PIN_LENGTH = 6
private const val DONE_CLOSE_DELAY_MS = 900L

internal class CliPinPanelState(private val flow: CliPinFlow) {
    var step by mutableStateOf(
        if (flow == CliPinFlow.ENABLE) CliPinStep.NEW else CliPinStep.CURRENT,
    )
    var currentPin by mutableStateOf("")
    var newPin by mutableStateOf("")
    var confirmPin by mutableStateOf("")
    var errorRes by mutableStateOf<Int?>(null)

    fun advanceFrom(step0: CliPinStep, submit: () -> Unit) {
        errorRes = null
        when (step0) {
            CliPinStep.CURRENT ->
                if (flow == CliPinFlow.DISABLE) submit() else step = CliPinStep.NEW
            CliPinStep.NEW -> step = CliPinStep.CONFIRM
            CliPinStep.CONFIRM ->
                if (confirmPin == newPin) {
                    submit()
                } else {
                    errorRes = R.string.cli_lock_err_mismatch
                    newPin = ""
                    confirmPin = ""
                    step = CliPinStep.NEW
                }
            else -> Unit
        }
    }

    fun applyResult(result: PasswordSetupResult): Boolean = when (result) {
        PasswordSetupResult.Success -> {
            step = CliPinStep.DONE
            true
        }
        PasswordSetupResult.WrongPassword -> {
            errorRes = R.string.cli_lock_err_wrong_pin
            currentPin = ""
            step = CliPinStep.CURRENT
            false
        }
        PasswordSetupResult.Failed -> {
            errorRes = R.string.cli_lock_err_failed
            newPin = ""
            confirmPin = ""
            step = if (flow == CliPinFlow.DISABLE) CliPinStep.CURRENT else CliPinStep.NEW
            false
        }
    }
}

private suspend fun performPinOperation(
    viewModel: HomeViewModel,
    flow: CliPinFlow,
    state: CliPinPanelState,
    eventMonitoring: Boolean,
    appJournal: Boolean,
): PasswordSetupResult = when (flow) {
    CliPinFlow.ENABLE -> viewModel.enableAppLockPassword(
        password = state.newPin.toCharArray(),
        eventMonitoring = eventMonitoring,
        appJournal = appJournal,
    )
    CliPinFlow.CHANGE -> viewModel.changeAppLockPassword(
        current = state.currentPin.toCharArray(),
        next = state.newPin.toCharArray(),
    )
    CliPinFlow.DISABLE -> viewModel.disableAppLockPassword(state.currentPin.toCharArray())
}

private fun panelTitleRes(flow: CliPinFlow): Int = when (flow) {
    CliPinFlow.ENABLE -> R.string.cli_lock_title_enable
    CliPinFlow.CHANGE -> R.string.cli_lock_title_change
    CliPinFlow.DISABLE -> R.string.cli_lock_title_disable
}

private fun doneTextRes(flow: CliPinFlow): Int = when (flow) {
    CliPinFlow.ENABLE -> R.string.cli_lock_done_enabled
    CliPinFlow.CHANGE -> R.string.cli_lock_done_changed
    CliPinFlow.DISABLE -> R.string.cli_lock_done_disabled
}

@Composable
private fun PinEntryBody(
    pin: String,
    onPin: (String) -> Unit,
    hintRes: Int,
    errorRes: Int?,
    onFilled: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalCliColors.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(pin) {
        if (pin.length == PIN_LENGTH) {
            onFilled()
        }
    }
    Box {
        BasicTextField(
            value = pin,
            onValueChange = { raw -> onPin(raw.filter(Char::isDigit).take(PIN_LENGTH)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .alpha(0f)
                .focusRequester(focusRequester),
        )
        Row {
            Text(text = "pin > ", style = CliType.body, color = colors.accent)
            Text(
                text = List(PIN_LENGTH) { index -> if (index < pin.length) "●" else "▁" }
                    .joinToString(" "),
                style = CliType.body,
                color = colors.fg,
            )
        }
    }
    CliElbowLine(
        text = stringResource(errorRes ?: hintRes),
        color = if (errorRes != null) colors.err else colors.dim,
    )
    Spacer(modifier = Modifier.height(CliSpacing.xs))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        CliChip(label = stringResource(R.string.cli_common_no_cancel), onClick = onCancel)
    }
}
