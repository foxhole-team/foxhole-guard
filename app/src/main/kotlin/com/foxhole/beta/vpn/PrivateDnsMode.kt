package com.foxhole.beta.vpn

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.Locale

internal enum class PrivateDnsMode {
    OFF,
    OPPORTUNISTIC,
    STRICT,
    UNKNOWN,
}

internal object PrivateDnsSettings {
    fun current(context: Context): PrivateDnsMode {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return PrivateDnsMode.OFF
        }
        return fromValues(
            modeValue = Settings.Global.getString(context.contentResolver, PRIVATE_DNS_MODE_KEY),
            specifierValue = Settings.Global.getString(context.contentResolver, PRIVATE_DNS_SPECIFIER_KEY),
        )
    }

    internal fun fromValues(
        modeValue: String?,
        specifierValue: String?,
    ): PrivateDnsMode {
        val mode = modeValue?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val specifier = specifierValue?.trim().orEmpty()
        return when {
            mode.isEmpty() && specifier.isEmpty() -> PrivateDnsMode.OFF
            mode == PRIVATE_DNS_MODE_OFF -> PrivateDnsMode.OFF
            mode == PRIVATE_DNS_MODE_OPPORTUNISTIC || mode == PRIVATE_DNS_MODE_AUTOMATIC -> PrivateDnsMode.OPPORTUNISTIC
            mode == PRIVATE_DNS_MODE_HOSTNAME || specifier.isNotEmpty() -> PrivateDnsMode.STRICT
            else -> PrivateDnsMode.UNKNOWN
        }
    }

    private const val PRIVATE_DNS_MODE_KEY = "private_dns_mode"
    private const val PRIVATE_DNS_SPECIFIER_KEY = "private_dns_specifier"
    private const val PRIVATE_DNS_MODE_OFF = "off"
    private const val PRIVATE_DNS_MODE_OPPORTUNISTIC = "opportunistic"
    private const val PRIVATE_DNS_MODE_AUTOMATIC = "automatic"
    private const val PRIVATE_DNS_MODE_HOSTNAME = "hostname"
}
