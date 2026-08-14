package com.foxhole.guard.ui.cli.profiles

import java.util.Locale

/**
 * The profile model carries no country — the endpoint host lives in an encrypted secret — but
 * subscription node names almost always reveal it: an upper-case ISO2 code ("DE-1", "HY2 NL") or an
 * emoji flag. The resolver reads only these reliable forms; lower-case letter pairs are not treated
 * as countries ("de" inside "node"), because no flag beats the wrong flag.
 */
internal fun profileCountryCode(vararg names: String?): String? =
    names.firstNotNullOfOrNull { name ->
        name?.takeIf(String::isNotBlank)?.let(::countryCodeFromName)
    }

private fun countryCodeFromName(name: String): String? {
    emojiFlagCountry(name)?.let { return it }
    return name.split(NonAlphaNumeric)
        .asSequence()
        .filter { token -> token.length == 2 && token.all(Char::isUpperCase) }
        .map { token -> if (token == "UK") "GB" else token }
        .firstOrNull(IsoCountries::contains)
}

// A pair of regional indicators U+1F1E6..U+1F1FF is an emoji flag; the first valid pair wins.
private fun emojiFlagCountry(name: String): String? {
    val codePoints = name.codePoints().toArray()
    for (index in 0 until codePoints.size - 1) {
        val first = codePoints[index]
        val second = codePoints[index + 1]
        if (first in REGIONAL_A..REGIONAL_Z && second in REGIONAL_A..REGIONAL_Z) {
            val code = buildString {
                append('A' + (first - REGIONAL_A))
                append('A' + (second - REGIONAL_A))
            }
            if (code in IsoCountries) return code
        }
    }
    return null
}

private val IsoCountries: Set<String> = Locale.getISOCountries().toSet()
private val NonAlphaNumeric = Regex("[^A-Za-z0-9]+")
private const val REGIONAL_A = 0x1F1E6
private const val REGIONAL_Z = 0x1F1FF
