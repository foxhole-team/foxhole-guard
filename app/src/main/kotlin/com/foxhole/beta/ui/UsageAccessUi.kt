package com.foxhole.beta.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.foxhole.beta.R
import com.foxhole.beta.core.anomaly.UsageStatsAccess

@Composable
internal fun rememberUsageAccessGranted(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember(context) { mutableStateOf(UsageStatsAccess.isGranted(context)) }
    DisposableEffect(context, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    granted = UsageStatsAccess.isGranted(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        granted = UsageStatsAccess.isGranted(context)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return granted
}

internal fun openUsageAccessSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

@Composable
internal fun UsageAccessConsentDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.usage_access_consent_title)) },
        text = { Text(stringResource(R.string.usage_access_consent_body)) },
        confirmButton = {
            FoxholeDialogConfirmButton(
                onClick = onConfirm,
                label = stringResource(R.string.usage_access_consent_confirm),
            )
        },
        dismissButton = {
            FoxholeDialogDismissButton(
                onClick = onDismiss,
                label = stringResource(R.string.usage_access_consent_dismiss),
            )
        },
    )
}
