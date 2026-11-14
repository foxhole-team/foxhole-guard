package com.foxhole.guard.router

import java.util.Locale
import kotlinx.serialization.json.*

private val controlCountries = Locale.getISOCountries().toSet()

internal data class RouterCountryDirectControl(val checked: Boolean, val enableCode: String?, val disableCode: String?) {
    val canChange: Boolean get() = if (checked) disableCode != null else enableCode != null
}

internal fun routerCountryDirectControl(preferences: RouterControlPreferences?, detected: String?,
    snapshot: RouterControlSnapshot?): RouterCountryDirectControl {
    val current = snapshot ?: return RouterCountryDirectControl(false, null, null)
    fun country(value: String?): String? = value?.takeIf { it in controlCountries }
    val selected = country(if (preferences?.countryAuto != false) detected else preferences?.countryCode)
    val local = (current.settings["localRules"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
    val rules = current.countries.mapNotNull { it as? JsonObject }
    if (selected != null) {
        val direct = rules.singleOrNull { (it["code"] as? JsonPrimitive)?.contentOrNull == selected }
            ?.get("route") == JsonPrimitive("direct")
        return RouterCountryDirectControl(local && direct, selected, selected.takeIf { local && direct })
    }
    // Only removing one explicit existing rule is safe when no WAN/manual country is known.
    val direct = if (local) rules.filter { it["route"] == JsonPrimitive("direct") }
        .mapNotNull { country((it["code"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull) }
        .singleOrNull() else null
    return RouterCountryDirectControl(direct != null, null, direct)
}
