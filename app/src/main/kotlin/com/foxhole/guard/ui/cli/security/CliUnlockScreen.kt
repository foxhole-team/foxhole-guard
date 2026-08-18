package com.foxhole.guard.ui.cli.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.foxhole.guard.R
import com.foxhole.guard.core.security.LockState
import com.foxhole.guard.core.security.UnlockOutcome
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.crypto.Cipher

internal data class CliUnlockOptions(
    val lockState: LockState,
    val legacyPassword: Boolean,
    val biometricAvailable: Boolean,
    val builtInPinPad: Boolean,
    val scrambleDigits: Boolean,
    val initialBackoffSeconds: Int,
)

internal data class CliCredentialActions(
    val unlock: suspend (CharArray) -> UnlockOutcome,
    val biometricCipher: () -> Cipher?,
    val unlockBiometric: suspend (Cipher) -> Boolean,
    val resetLocalData: () -> Unit,
)

internal data class CliSystemUnlockActions(
    val biometricAllowed: () -> Boolean,
    val unlock: () -> Unit,
    val recordFailure: () -> Unit,
)

@Composable
internal fun CliUnlockScreen(
    options: CliUnlockOptions,
    credentials: CliCredentialActions,
    system: CliSystemUnlockActions,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    Box(
        modifier = modifier.fillMaxSize().background(colors.bg).imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .padding(CliSpacing.lg),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "  /\\_/\\\n ( o.o )\n  > ^ <", style = CliType.title, color = colors.accent)
            Spacer(modifier = Modifier.height(CliSpacing.md))
            CliPanel(
                icon = R.drawable.pix_lock,
                title = stringResource(R.string.cli_unlock_title),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.unlock_title).uppercase(),
                    style = CliType.display,
                    color = colors.fg,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (options.lockState == LockState.LOCKED_SYSTEM) {
                    CliSystemCredentialGate(system)
                } else {
                    CliLocalCredentialGate(options, credentials, system.recordFailure)
                }
            }
        }
    }
}

@Composable
private fun CliSystemCredentialGate(actions: CliSystemUnlockActions) {
    val context = LocalContext.current
    var autoPrompted by remember { mutableStateOf(false) }
    val showPrompt = {
        showDeviceCredentialPrompt(
            context = context,
            includeBiometrics = actions.biometricAllowed(),
            onSuccess = actions.unlock,
            onFailure = actions.recordFailure,
        )
    }
    LaunchedEffect(Unit) {
        if (!autoPrompted) {
            autoPrompted = true
            showPrompt()
        }
    }
    CliButton(
        label = stringResource(R.string.unlock_system_action),
        onClick = showPrompt,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CliLocalCredentialGate(
    options: CliUnlockOptions,
    actions: CliCredentialActions,
    recordPromptFailure: () -> Unit,
) {
    val colors = LocalCliColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { CliUnlockState(options.initialBackoffSeconds) }
    LaunchedEffect(state.remainingSeconds) {
        if (state.remainingSeconds > 0) {
            delay(1_000)
            state.remainingSeconds -= 1
        }
    }
    val submit = credentialSubmitAction(state, actions, scope)
    val biometric = biometricSubmitAction(context, actions, recordPromptFailure, scope)
    var biometricPrompted by remember { mutableStateOf(false) }
    LaunchedEffect(options.biometricAvailable) {
        if (options.biometricAvailable && !biometricPrompted) {
            biometricPrompted = true
            biometric()
        }
    }
    LaunchedEffect(state.credential) {
        if (!options.legacyPassword && state.credential.length == PIN_LENGTH) submit()
    }

    CliCredentialEntry(options, state, submit)
    CliUnlockStatus(state)
    CliCredentialActionsRow(options, state, submit, biometric)
    if (state.resetOffered) {
        CliResetControl(onReset = actions.resetLocalData)
    }
}

private fun credentialSubmitAction(
    state: CliUnlockState,
    actions: CliCredentialActions,
    scope: CoroutineScope,
): () -> Unit = {
    if (state.canSubmit) {
        val entered = state.credential.toCharArray()
        state.working = true
        scope.launch { state.apply(actions.unlock(entered)) }
    }
}

private fun biometricSubmitAction(
    context: Context,
    actions: CliCredentialActions,
    recordPromptFailure: () -> Unit,
    scope: CoroutineScope,
): () -> Unit = {
    actions.biometricCipher()?.let { cipher ->
        showBiometricPrompt(
            context = context,
            cipher = cipher,
            onAuthorized = { authorized -> scope.launch { actions.unlockBiometric(authorized) } },
            onFailure = recordPromptFailure,
        )
    }
}

@Composable
private fun CliCredentialEntry(
    options: CliUnlockOptions,
    state: CliUnlockState,
    submit: () -> Unit,
) {
    val colors = LocalCliColors.current
    Text(
        text = if (options.legacyPassword) {
            stringResource(R.string.cli_unlock_prompt_password)
        } else {
            stringResource(R.string.cli_unlock_prompt_pin, PIN_LENGTH)
        },
        style = CliType.body,
        color = colors.dim,
    )
    if (!options.builtInPinPad || options.legacyPassword) {
        CliMaskedInput(state, options.legacyPassword, submit)
    } else {
        CliPinEntry(options, state)
    }
}

@Composable
private fun CliPinEntry(options: CliUnlockOptions, state: CliUnlockState) {
    val colors = LocalCliColors.current
    Text(
        text = state.credential.padEnd(PIN_LENGTH, '_').map { if (it == '_') '_' else '*' }.joinToString(" "),
        style = CliType.title,
        color = if (state.error == null) colors.fg else colors.err,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    CliPinPad(
        scrambled = options.scrambleDigits,
        shuffleKey = state.failures,
        enabled = !state.working && state.remainingSeconds == 0,
        onDigit = { digit -> if (state.credential.length < PIN_LENGTH) state.credential += digit },
        onBackspace = { state.credential = state.credential.dropLast(1) },
    )
}

@Composable
private fun CliCredentialActionsRow(
    options: CliUnlockOptions,
    state: CliUnlockState,
    submit: () -> Unit,
    biometric: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm), modifier = Modifier.fillMaxWidth()) {
        if (options.legacyPassword) {
            CliButton(
                label = stringResource(R.string.unlock_action),
                onClick = submit,
                enabled = state.canSubmit,
                modifier = Modifier.weight(1f),
            )
        }
        if (options.biometricAvailable) {
            CliButton(
                label = stringResource(R.string.cli_unlock_bio),
                onClick = biometric,
                enabled = !state.working,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CliMaskedInput(state: CliUnlockState, legacyPassword: Boolean, submit: () -> Unit) {
    val colors = LocalCliColors.current
    BasicTextField(
        value = state.credential,
        onValueChange = { raw ->
            if (!state.working && state.remainingSeconds == 0) {
                state.error = null
                state.credential = if (legacyPassword) raw else raw.filter(Char::isDigit).take(PIN_LENGTH)
            }
        },
        textStyle = CliType.body.copy(color = colors.fg),
        cursorBrush = SolidColor(colors.accent),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (legacyPassword) KeyboardType.Password else KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        singleLine = true,
        enabled = !state.working && state.remainingSeconds == 0,
        modifier = Modifier.fillMaxWidth().background(colors.panelAlt).padding(CliSpacing.md),
    )
}

@Composable
private fun CliPinPad(
    scrambled: Boolean,
    shuffleKey: Int,
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
) {
    val digits = remember(scrambled, shuffleKey) {
        if (scrambled) ('0'..'9').shuffled() else ('1'..'9').toList() + '0'
    }
    val cells = digits.map(Char::toString).toMutableList().apply {
        if (!scrambled) add(9, "") else add("")
        add("<")
    }
    cells.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm), modifier = Modifier.fillMaxWidth()) {
            row.forEach { label ->
                if (label.isEmpty()) {
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    CliChip(
                        label = label,
                        enabled = enabled,
                        onClick = { if (label == "<") onBackspace() else onDigit(label.single()) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CliUnlockStatus(state: CliUnlockState) {
    val colors = LocalCliColors.current
    val message = when {
        state.remainingSeconds > 0 -> stringResource(R.string.unlock_retry_in, state.remainingSeconds)
        state.error == CliUnlockError.WRONG -> stringResource(R.string.unlock_access_denied)
        state.error == CliUnlockError.CORRUPTED -> stringResource(R.string.unlock_corrupted)
        state.error == CliUnlockError.RESET -> stringResource(R.string.unlock_reset_offer)
        state.working -> stringResource(R.string.cli_unlock_status_authenticating)
        else -> stringResource(R.string.cli_unlock_status_ready)
    }
    Text(text = message, style = CliType.small, color = if (state.error == null) colors.dim else colors.err)
}

@Composable
private fun CliResetControl(onReset: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    val label = if (armed) {
        stringResource(
            R.string.unlock_reset_confirm_action
        )
    } else {
        stringResource(R.string.unlock_reset_action)
    }
    CliButton(label = label, color = LocalCliColors.current.err, onClick = { if (armed) onReset() else armed = true })
}

private class CliUnlockState(initialBackoffSeconds: Int) {
    var credential by mutableStateOf("")
    var error by mutableStateOf<CliUnlockError?>(null)
    var working by mutableStateOf(false)
    var resetOffered by mutableStateOf(false)
    var remainingSeconds by mutableIntStateOf(initialBackoffSeconds)
    var failures by mutableIntStateOf(0)

    val canSubmit: Boolean get() = credential.isNotEmpty() && !working && remainingSeconds == 0

    fun apply(outcome: UnlockOutcome) {
        when (outcome) {
            is UnlockOutcome.Success -> Unit
            is UnlockOutcome.WrongPassword -> {
                credential = ""
                resetOffered = outcome.resetOffered
                error = if (outcome.resetOffered) CliUnlockError.RESET else CliUnlockError.WRONG
                remainingSeconds = ((outcome.nextDelayMs + 999L) / 1_000L).toInt()
                failures += 1
            }
            is UnlockOutcome.Corrupted -> {
                error = CliUnlockError.CORRUPTED
                resetOffered = true
            }
        }
        working = false
    }
}

private enum class CliUnlockError { WRONG, CORRUPTED, RESET }

private fun showDeviceCredentialPrompt(
    context: Context,
    includeBiometrics: Boolean,
    onSuccess: () -> Unit,
    onFailure: () -> Unit,
) {
    val activity = context.findFragmentActivity() ?: return
    val authenticators = if (includeBiometrics || android.os.Build.VERSION.SDK_INT < 30) {
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
    if (BiometricManager.from(context).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) return
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        override fun onAuthenticationFailed() = onFailure()
    }
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(context), callback)
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(context.getString(R.string.unlock_title))
        .setAllowedAuthenticators(authenticators)
        .build()
    prompt.authenticate(info)
}

private fun showBiometricPrompt(
    context: Context,
    cipher: Cipher,
    onAuthorized: (Cipher) -> Unit,
    onFailure: () -> Unit,
) {
    val activity = context.findFragmentActivity() ?: return
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            result.cryptoObject?.cipher?.let(onAuthorized)
        }
        override fun onAuthenticationFailed() = onFailure()
    }
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(context), callback)
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(context.getString(R.string.unlock_title))
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText(context.getString(R.string.cancel))
        .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

private const val PIN_LENGTH = 6
