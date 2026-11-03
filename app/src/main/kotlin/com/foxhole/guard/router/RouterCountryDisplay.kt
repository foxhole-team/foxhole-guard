package com.foxhole.guard.router

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val displayCountries = Locale.getISOCountries().toSet()

internal fun routerDisplayCountryCode(preferences: RouterControlPreferences?, detected: String?, configured: JsonArray?): String? {
    fun country(value: String?): String? = value?.trim()?.uppercase(Locale.ROOT)?.takeIf { it in displayCountries }
    if (preferences?.countryAuto != false) country(detected)?.let { return it }
    country(preferences?.countryCode)?.let { return it }
    // A unique configured direct-country rule is display evidence, never WAN or consent evidence.
    return configured.orEmpty().mapNotNull { item ->
        when (item) {
            is JsonObject -> if ((item["route"] as? JsonPrimitive)?.contentOrNull == "direct")
                country((item["code"] as? JsonPrimitive)?.contentOrNull) else null
            is JsonPrimitive -> item.takeIf { it.isString }?.contentOrNull?.let(::country)
            else -> null
        }
    }.distinct().singleOrNull()
}
