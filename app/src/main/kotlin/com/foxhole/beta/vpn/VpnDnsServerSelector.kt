package com.foxhole.beta.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object VpnDnsServerSelector {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    fun advertisedDnsServerAddress(
        configJson: String?,
        fallbackServerAddress: String,
    ): String =
        advertisedDnsServerAddresses(
            configJson = configJson,
            fallbackServerAddress = fallbackServerAddress,
        ).first()

    fun advertisedDnsServerAddresses(
        configJson: String?,
        fallbackServerAddress: String?,
    ): List<String> {
        val remoteServerAddress = remoteDnsServerAddresses(configJson).firstOrNull()
        return listOfNotNull(remoteServerAddress, fallbackServerAddress)
            .plus(REQUIRED_TUN_DNS_SERVERS)
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun remoteDnsServerAddresses(configJson: String?): List<String> =
        runCatching {
            if (configJson == null) {
                return emptyList()
            }
            val root = json.parseToJsonElement(configJson).jsonObject
            val servers = root["dns"]?.jsonObject?.get("servers")?.jsonArray.orEmpty()
            servers
                .map { it.jsonObject }
                .filterNot { server ->
                    server["tag"]?.jsonPrimitive?.content == "dns-local" ||
                        server["type"]?.jsonPrimitive?.content == "local" ||
                        server["address"]?.jsonPrimitive?.content == "local"
                }.mapNotNull { server ->
                    server["server"]?.jsonPrimitive?.content?.takeIf(::isIpLiteral)
                        ?: server["address"]?.jsonPrimitive?.content?.let(::extractHost)?.takeIf(::isIpLiteral)
                }.distinct()
        }.getOrDefault(emptyList())

    private fun extractHost(address: String): String? = address.trim().toHttpUrlOrNull()?.host

    private fun isIpLiteral(value: String): Boolean = IPV4_REGEX.matches(value) || value.contains(':')

    private val IPV4_REGEX =
        Regex(
            pattern = """^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""",
        )

    private val REQUIRED_TUN_DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")
}
