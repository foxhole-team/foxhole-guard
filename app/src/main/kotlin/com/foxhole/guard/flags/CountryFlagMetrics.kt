package com.foxhole.guard.flags

import java.util.Locale

internal fun countryFlagCode(countryCode: String?): String? = countryCode?.trim()?.lowercase(Locale.ROOT)
    ?.takeIf { it.length == 2 && it.all { letter -> letter in 'a'..'z' } }

internal data class CountryFlagSizePx(val width: Float, val height: Float)

internal fun countryFlagSizePx(capitalHeightPx: Float, imageWidth: Int, imageHeight: Int): CountryFlagSizePx? {
    if (!capitalHeightPx.isFinite() || capitalHeightPx <= 0f || imageWidth <= 0 || imageHeight <= 0) return null
    return CountryFlagSizePx(capitalHeightPx * imageWidth / imageHeight, capitalHeightPx)
}
