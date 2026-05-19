package com.foxhole.beta.vpn

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.Locale

enum class PrivateDnsMode {
    OFF,
    OPPORTUNISTIC,
    STRICT,
    UNKNOWN,
}

data class PrivateDnsState(
    val mode: PrivateDnsMode,
    val hostname: String? = null,
)

internal fun PrivateDnsMode.isSupportedForTunnelMode(): Boolean =
    this == PrivateDnsMode.OFF || this == PrivateDnsMode.OPPORTUNISTIC || this == PrivateDnsMode.STRICT

internal object PrivateDnsSettings {
    fun current(context: Context): PrivateDnsMode = currentState(context).mode

    fun currentState(context: Context): PrivateDnsState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return PrivateDnsState(PrivateDnsMode.OFF)
        }
        return stateFromValues(
            modeValue = Settings.Global.getString(context.contentResolver, PRIVATE_DNS_MODE_KEY),
            specifierValue = Settings.Global.getString(context.contentResolver, PRIVATE_DNS_SPECIFIER_KEY),
        )
    }

    internal fun fromValues(
        modeValue: String?,
        specifierValue: String?,
    ): PrivateDnsMode = stateFromValues(modeValue, specifierValue).mode

    internal fun stateFromValues(
        modeValue: String?,
        specifierValue: String?,
    ): PrivateDnsState {
        val mode = modeValue?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val specifier = specifierValue?.trim().orEmpty()
        val hostname = specifier.takeIf(String::isNotBlank)
        return when {
            mode.isEmpty() && specifier.isEmpty() -> PrivateDnsState(PrivateDnsMode.OFF)
            mode == PRIVATE_DNS_MODE_OFF -> PrivateDnsState(PrivateDnsMode.OFF)
            mode == PRIVATE_DNS_MODE_OPPORTUNISTIC || mode == PRIVATE_DNS_MODE_AUTOMATIC ->
                PrivateDnsState(PrivateDnsMode.OPPORTUNISTIC)
            mode == PRIVATE_DNS_MODE_HOSTNAME || specifier.isNotEmpty() ->
                PrivateDnsState(PrivateDnsMode.STRICT, hostname)
            else -> PrivateDnsState(PrivateDnsMode.UNKNOWN)
        }
    }

    private const val PRIVATE_DNS_MODE_KEY = "private_dns_mode"
    private const val PRIVATE_DNS_SPECIFIER_KEY = "private_dns_specifier"
    private const val PRIVATE_DNS_MODE_OFF = "off"
    private const val PRIVATE_DNS_MODE_OPPORTUNISTIC = "opportunistic"
    private const val PRIVATE_DNS_MODE_AUTOMATIC = "automatic"
    private const val PRIVATE_DNS_MODE_HOSTNAME = "hostname"
}
