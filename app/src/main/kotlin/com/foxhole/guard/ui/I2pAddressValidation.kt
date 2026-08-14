package com.foxhole.guard.ui

import com.foxhole.guard.R
import java.util.Locale

// Validation for the local .i2p addressbook (host → full base64 destination). Mirrors the
// SiteMaskValidation.kt shape: pure normalizers + nullable string-res errors for live dialogs.

internal fun normalizedI2pHostInput(value: String): String? {
    val normalized = value.trim().lowercase(Locale.ROOT).removeSuffix(".").takeIf(String::isNotBlank) ?: return null
    return if (normalized.endsWith(I2P_HOST_SUFFIX)) normalized else "$normalized$I2P_HOST_SUFFIX"
}

internal fun i2pHostValidationErrorRes(value: String): Int? {
    val host = normalizedI2pHostInput(value) ?: return R.string.i2p_address_host_invalid
    return when {
        // b32 names hash-resolve inside I2P without any addressbook entry; a dedicated error
        // explains that instead of a generic "invalid host".
        host.endsWith(I2P_B32_SUFFIX) -> R.string.i2p_address_host_b32_not_needed
        !isValidI2pHost(host) -> R.string.i2p_address_host_invalid
        else -> null
    }
}

internal fun i2pDestinationValidationErrorRes(value: String): Int? {
    val destination = value.trim()
    return when {
        destination.length !in I2P_DESTINATION_LENGTH_RANGE -> R.string.i2p_address_destination_invalid
        !destination.all(::isI2pBase64Char) -> R.string.i2p_address_destination_invalid
        else -> null
    }
}

private fun isValidI2pHost(host: String): Boolean {
    val name = host.removeSuffix(I2P_HOST_SUFFIX)
    if (name.isEmpty() || host.length > MAX_HOST_LENGTH || host.contains("..")) {
        return false
    }
    return name.split(".").all(::isValidI2pHostLabel)
}

private fun isValidI2pHostLabel(value: String): Boolean =
    value.length in 1..MAX_LABEL_LENGTH &&
        value.first() != '-' &&
        value.last() != '-' &&
        value.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

private const val I2P_HOST_SUFFIX = ".i2p"
private const val I2P_B32_SUFFIX = ".b32.i2p"
private const val MAX_HOST_LENGTH = 253
private const val MAX_LABEL_LENGTH = 63

// A full I2P destination is 516..616 chars of I2P-flavored base64 ('-' and '~' replace '+' and
// '/'), optionally '='-padded; *.b32.i2p hashes are intentionally not accepted here.
private val I2P_DESTINATION_LENGTH_RANGE = 516..616

private fun isI2pBase64Char(value: Char): Boolean =
    value in 'A'..'Z' || value in 'a'..'z' || value in '0'..'9' ||
        value == '-' || value == '~' || value == '='
