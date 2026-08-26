package com.foxhole.core.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object RuntimeTunFingerprint {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    fun of(
        configJson: String?,
        advertisedDnsServers: List<String>,
        networkHandle: Long?,
        metered: Boolean,
        packageUidResolver: (String) -> Int? = { null },
    ): String? {
        val config = configJson ?: return null
        val tunInbound =
            runCatching {
                json.parseToJsonElement(config)
                    .jsonObject["inbounds"]
                    ?.jsonArray
                    ?.firstOrNull { inbound ->
                        inbound.jsonObject["type"]?.jsonPrimitive?.content == "tun"
                    }
            }.getOrNull() ?: return null
        return buildString {
            append(tunInbound.toString())
            append("|dns=")
            append(advertisedDnsServers.joinToString(","))
            append("|net=")
            append(networkHandle ?: -1L)
            append("|metered=")
            append(metered)
            append("|uids=")
            append(kernelFilterPackageUidSalt(tunInbound, packageUidResolver))
        }
    }

    private fun kernelFilterPackageUidSalt(
        tunInbound: JsonElement,
        packageUidResolver: (String) -> Int?,
    ): String =
        listOf("include_package", "exclude_package")
            .flatMap { key ->
                runCatching {
                    tunInbound.jsonObject[key]?.jsonArray?.mapNotNull { element ->
                        element.jsonPrimitive.content.takeIf(String::isNotBlank)
                    }
                }.getOrNull().orEmpty()
            }
            .distinct()
            .sorted()
            .joinToString(",") { packageName -> "$packageName=${packageUidResolver(packageName) ?: -1}" }
}
