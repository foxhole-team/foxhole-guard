package com.foxhole.core.runtime

import com.foxhole.core.model.FoxCoreTunPlan
import com.foxhole.core.model.FoxCoreTunRoute
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun translateFoxCoreTun(root: JsonObject): FoxCoreTunPlan {
    val (tun, path) = root.singleManagedTun()
    tun.validateManagedTun(path)
    val mtu =
        tun.optionalInt("mtu", path)
            ?.takeIf { it in 1280..65_535 }
            ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.mtu")
    val prefixes = tun.parseTunPrefixes("address", path, allowDefaultRoute = false)
    val ipv4 = prefixes.filterNot(ParsedTunPrefix::ipv6)
    val ipv6 = prefixes.filter(ParsedTunPrefix::ipv6)
    if (ipv4.size != 1 || ipv6.size > 1) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.address")
    }
    val explicitRoutes = tun["route_address"] != null
    val routes = tun.translateTunRoutes(path, explicitRoutes, ipv6.isNotEmpty())
    val allowedApplications = tun.validatedPackages("include_package", path)
    val disallowedApplications = tun.validatedPackages("exclude_package", path)
    if (allowedApplications.isNotEmpty() && disallowedApplications.isNotEmpty()) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
    }
    val localDnsAdvertise =
        if (explicitRoutes) {
            routes.singleOrNull { route ->
                route.prefixLength == IPV4_HOST_PREFIX &&
                    route.address.isFoxCoreIpv4Literal() &&
                    route.address != ipv4.single().address
            }?.address
        } else {
            null
        }
    return FoxCoreTunPlan(
        mtu = mtu,
        ipv4Address = ipv4.single().address,
        ipv4PrefixLength = ipv4.single().prefixLength,
        ipv6Address = ipv6.singleOrNull()?.address,
        ipv6PrefixLength = ipv6.singleOrNull()?.prefixLength,
        routes = routes,
        advertisedDnsServers = listOfNotNull(localDnsAdvertise),
        allowedApplications = allowedApplications,
        disallowedApplications = disallowedApplications,
    )
}

internal fun translateFoxCoreControlProxy(root: JsonObject): JsonObject? {
    val inbounds =
        root["inbounds"]
            ?.asFoxCoreArray("$.inbounds")
            ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$.inbounds")
    val runtimeInbounds =
        inbounds.mapIndexedNotNull { index, element ->
            val path = "$.inbounds[$index]"
            val inbound = element.asFoxCoreObject(path)
            if (inbound["type"]?.jsonPrimitive?.contentOrNull != "tun") {
                inbound to path
            } else {
                null
            }
        }
    if (runtimeInbounds.size > 1) {
        rejectFoxCoreConfig(
            FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
            "$.inbounds",
        )
    }
    return runtimeInbounds.singleOrNull()?.let { (inbound, path) ->
        translateManagedRuntimeInbound(inbound, path)
    }
}

private fun JsonObject.singleManagedTun(): Pair<JsonObject, String> {
    val inbounds =
        this["inbounds"]
            ?.asFoxCoreArray("$.inbounds")
            ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$.inbounds")
    val objects = inbounds.mapIndexed { index, element -> element.asFoxCoreObject("$.inbounds[$index]") }
    val tunIndexes =
        objects.mapIndexedNotNull { index, inbound ->
            index.takeIf { inbound["type"]?.jsonPrimitive?.contentOrNull == "tun" }
        }
    if (tunIndexes.size != 1) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$.inbounds")
    }
    val tunIndex = tunIndexes.single()
    objects.forEachIndexed { index, inbound ->
        if (index != tunIndex) {
            translateManagedRuntimeInbound(inbound, "$.inbounds[$index]")
        }
    }
    return objects[tunIndex] to "$.inbounds[$tunIndex]"
}

private fun JsonObject.validateManagedTun(path: String) {
    requireOnlyKeys(TUN_KEYS, path)
    if (requiredString("type", path) != "tun") {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.type")
    }
    if (optionalBoolean("auto_route", path) != true) {
        rejectFoxCoreConfig(
            FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
            "$path.auto_route",
        )
    }
    optionalBoolean("strict_route", path)
    optionalString("stack", path)?.let { stack ->
        if (stack !in SUPPORTED_TUN_STACKS) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.stack")
        }
    }
}

private fun JsonObject.parseTunPrefixes(
    key: String,
    path: String,
    allowDefaultRoute: Boolean,
): List<ParsedTunPrefix> =
    stringList(key, path).mapIndexed { index, value ->
        parseTunPrefix(
            value = value,
            path = "$path.$key[$index]",
            allowDefaultRoute = allowDefaultRoute,
        )
    }

private fun JsonObject.translateTunRoutes(
    path: String,
    explicitRoutes: Boolean,
    ipv6Enabled: Boolean,
): List<FoxCoreTunRoute> {
    if (!explicitRoutes) {
        return buildList {
            add(FoxCoreTunRoute("0.0.0.0", 0))
            if (ipv6Enabled) {
                add(FoxCoreTunRoute("::", 0))
            }
        }
    }
    val prefixes = parseTunPrefixes("route_address", path, allowDefaultRoute = true)
    if (prefixes.isEmpty()) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.route_address")
    }
    return prefixes.map { prefix -> FoxCoreTunRoute(prefix.address, prefix.prefixLength) }
}

private fun translateManagedRuntimeInbound(
    inbound: JsonObject,
    path: String,
): JsonObject {
    inbound.requireOnlyKeys(RUNTIME_LOOPBACK_KEYS, path)
    if (inbound.requiredString("type", path) != "http" ||
        inbound.requiredString("tag", path) != RUNTIME_LOOPBACK_PROXY_INBOUND_TAG ||
        inbound.requiredString("listen", path) != RUNTIME_LOOPBACK_HOST
    ) {
        rejectFoxCoreConfig(
            FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
            path,
        )
    }
    val port = inbound.requiredPort("listen_port", path)
    val users =
        inbound["users"]
            ?.asFoxCoreArray("$path.users")
            ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.users")
    if (users.size != 1) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.users")
    }
    val userPath = "$path.users[0]"
    val user = users.single().asFoxCoreObject(userPath)
    user.requireOnlyKeys(setOf("username", "password"), userPath)
    val username = user.requiredString("username", userPath)
    if (username != RUNTIME_LOOPBACK_USERNAME) {
        rejectFoxCoreConfig(
            FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
            userPath,
        )
    }
    val password = user.requiredString("password", userPath)
    if (password.toByteArray(Charsets.UTF_8).size > MAX_CONTROL_PROXY_PASSWORD_BYTES) {
        rejectFoxCoreConfig(
            FoxCoreConfigRejection.INVALID_SHAPE,
            "$userPath.password",
        )
    }
    return buildJsonObject {
        put("http_port", port)
        put("username", username)
        put("password", password)
    }
}

private fun parseTunPrefix(
    value: String,
    path: String,
    allowDefaultRoute: Boolean,
): ParsedTunPrefix {
    val parts = value.split('/', limit = 2)
    if (parts.size != 2) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
    }
    val address = parts[0]
    val prefix = parts[1].toIntOrNull()
        ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
    val ipv6 = address.isFoxCoreIpv6Literal()
    val valid =
        if (ipv6) {
            prefix in (if (allowDefaultRoute) 0 else 1)..128
        } else {
            address.isFoxCoreIpv4Literal() &&
                prefix in (if (allowDefaultRoute) 0 else 1)..IPV4_HOST_PREFIX
        }
    if (!valid) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
    }
    return ParsedTunPrefix(address, prefix, ipv6)
}

private fun JsonObject.validatedPackages(
    key: String,
    path: String,
): List<String> =
    stringList(key, path)
        .also { packages ->
            if (packages.size > MAX_TUN_PACKAGES ||
                packages.distinct().size != packages.size ||
                packages.any { packageName -> !PACKAGE_NAME_PATTERN.matches(packageName) }
            ) {
                rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.$key")
            }
        }

private data class ParsedTunPrefix(
    val address: String,
    val prefixLength: Int,
    val ipv6: Boolean,
)

private val TUN_KEYS =
    setOf(
        "type",
        "tag",
        "interface_name",
        "mtu",
        "auto_route",
        "strict_route",
        "stack",
        "address",
        "route_address",
        "include_package",
        "exclude_package",
    )

private val RUNTIME_LOOPBACK_KEYS =
    setOf(
        "type",
        "tag",
        "listen",
        "listen_port",
        "users",
    )

private val SUPPORTED_TUN_STACKS = setOf("system", "gvisor")
private val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z0-9._]{1,255}$")
private const val RUNTIME_LOOPBACK_HOST = "127.0.0.1"
private const val RUNTIME_LOOPBACK_USERNAME = "foxhole-runtime"
private const val MAX_CONTROL_PROXY_PASSWORD_BYTES = 255
private const val MAX_TUN_PACKAGES = 4_096
private const val IPV4_HOST_PREFIX = 32
