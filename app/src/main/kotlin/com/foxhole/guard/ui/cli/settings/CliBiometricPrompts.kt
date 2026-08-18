package com.foxhole.guard.ui.cli.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.foxhole.guard.R
import javax.crypto.Cipher

internal fun showCliBiometricConfirmPrompt(
    context: Context,
    onConfirmed: () -> Unit,
) {
    val activity = context.findFragmentActivity() ?: return
    if (!strongBiometricsAvailable(context)) {
        return
    }
    val prompt =
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onConfirmed()
                }
            },
        )
    prompt.authenticate(cliBiometricPromptInfo(context))
}

internal fun showCliBiometricEnrolPrompt(
    context: Context,
    cipher: Cipher,
    onAuthorized: (Cipher) -> Unit,
) {
    val activity = context.findFragmentActivity() ?: return
    if (!strongBiometricsAvailable(context)) {
        return
    }
    val prompt =
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    result.cryptoObject?.cipher?.let(onAuthorized)
                }
            },
        )
    prompt.authenticate(cliBiometricPromptInfo(context), BiometricPrompt.CryptoObject(cipher))
}

private fun strongBiometricsAvailable(context: Context): Boolean =
    BiometricManager
        .from(context)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

private fun cliBiometricPromptInfo(context: Context): BiometricPrompt.PromptInfo =
    BiometricPrompt.PromptInfo
        .Builder()
        .setTitle(context.getString(R.string.app_lock_biometric_title))
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText(context.getString(R.string.cancel))
        .build()

private tailrec fun Context.findFragmentActivity(): FragmentActivity? =
    when (this) {
        is FragmentActivity -> this
        is ContextWrapper -> baseContext.findFragmentActivity()
        else -> null
    }
