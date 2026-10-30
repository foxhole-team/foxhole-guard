package com.foxhole.guard.flags

import com.foxhole.guard.R

internal fun countryFlagVectorResource(countryCode: String): Int? = when (countryFlagCode(countryCode)) {
    "gb" -> R.drawable.flag_svg_gb
    "ru" -> R.drawable.flag_svg_ru
    "de" -> R.drawable.flag_svg_de
    "fr" -> R.drawable.flag_svg_fr
    "es" -> R.drawable.flag_svg_es
    "jp" -> R.drawable.flag_svg_jp
    "cn" -> R.drawable.flag_svg_cn
    else -> null
}
