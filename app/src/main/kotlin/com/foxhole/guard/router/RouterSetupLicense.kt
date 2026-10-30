package com.foxhole.guard.router

import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal const val ROUTER_SETUP_LICENSE_VERSION = 1

internal class RouterLicenseRequired : IOException("router_license_required")

internal data class RouterLicenseRequirement(val required: Boolean, val version: Int) {
    init {
        require(version >= 0)
        require(!required || version == ROUTER_SETUP_LICENSE_VERSION)
    }
}

internal fun requireRouterSetupLicense(accepted: Boolean, version: Int = ROUTER_SETUP_LICENSE_VERSION) {
    if (!accepted) throw RouterLicenseRequired()
    require(version == ROUTER_SETUP_LICENSE_VERSION) { "router_license_version_unsupported" }
}

internal fun routerLicenseRequirement(value: JsonObject): RouterLicenseRequirement {
    val required = (value["licenseRequired"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    val version = (value["licenseVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    require("licenseRequired" !in value || required != null) { "router_license_requirement_invalid" }
    require("licenseVersion" !in value || version != null) { "router_license_requirement_invalid" }
    return RouterLicenseRequirement(required == true, version ?: 0)
}

internal fun routerPinLicenseFields(requirement: RouterLicenseRequirement,
    accepted: Boolean = false, version: Int = 0): JsonObject {
    if (requirement.required) requireRouterSetupLicense(accepted, version)
    if (accepted) requireRouterSetupLicense(true, version) else require(version == 0)
    return buildJsonObject {
        put("licenseAccepted", accepted)
        put("licenseVersion", version)
    }
}
