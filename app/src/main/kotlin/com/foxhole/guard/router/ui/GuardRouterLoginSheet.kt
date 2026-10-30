package com.foxhole.guard.router.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GuardRouterLoginSheet(
    installed: Boolean,
    gateway: String,
    busy: Boolean,
    onSubmit: suspend (address: String, credential: CharArray) -> Unit,
    onFailure: () -> Unit,
    addressEditable: Boolean = true,
    onClose: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val address = remember { TextFieldState(gateway) }
    val credential = remember(installed) { TextFieldState() }
    val scope = rememberCoroutineScope()
    var submitting by remember(installed) { mutableStateOf(false) }
    DisposableEffect(credential) {
        onDispose { credential.edit { replace(0, length, "") } }
    }
    val ready = address.text.isNotBlank() && (!installed || credential.text.length == 4 && credential.text.all { it in '0'..'9' })
    fun submit() {
        submitting = true
        val host = address.text.toString().trim()
        val secret = CharArray(credential.text.length) { credential.text[it] }
        credential.edit { replace(0, length, "") }
        val job = scope.launch {
            try { onSubmit(host, secret) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { onFailure() }
            finally { secret.fill('\u0000'); submitting = false }
        }
        job.invokeOnCompletion { secret.fill('\u0000') }
    }
    GuardRouterSheetContent(
        R.string.guard_router_vpn_router,
        R.drawable.lin_router,
        busy = busy || submitting,
        actions = {
            GuardRouterHelpButton(
                if (installed) R.string.guard_router_vpn_router_pin else R.string.guard_router_vpn_router_password,
                if (installed) R.string.guard_router_router_help_pin else R.string.guard_router_router_help_password,
            )
        },
        footer = {
            OutlinedButton(
                onClick = ::submit,
                enabled = !busy && !submitting && ready,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                border = BorderStroke(1.5.dp, if (installed) routerConnectColor() else MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor =
                    if (installed) routerConnectColor() else MaterialTheme.colorScheme.primary),
            ) { Text(stringResource(if (installed) R.string.guard_router_vpn_router_pair else R.string.guard_router_vpn_router_check)) }
            GuardRouterNavigationFooter(!busy && !submitting, onBack, onClose)
        },
    ) {
        if (!installed) Text(stringResource(R.string.guard_router_vpn_router_root_login))
        OutlinedTextField(
            state = address,
            modifier = Modifier.fillMaxWidth(),
            enabled = addressEditable && !busy && !submitting,
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation.maxLength(253),
            label = { Text(stringResource(R.string.guard_router_vpn_router_address)) },
        )
        OutlinedSecureTextField(
            state = credential,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !submitting,
            inputTransformation = InputTransformation.maxLength(if (installed) 4 else 1024),
            textObfuscationMode = TextObfuscationMode.Hidden,
            keyboardOptions = KeyboardOptions(keyboardType = if (installed) KeyboardType.NumberPassword else KeyboardType.Password),
            label = { Text(stringResource(if (installed) R.string.guard_router_vpn_router_pin else R.string.guard_router_vpn_router_password)) },
        )
    }
}
