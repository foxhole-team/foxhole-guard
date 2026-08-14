package com.foxhole.core.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Identity of the VpnService interface a session would establish (hot routing).
 *
 * Built from the TUN subtree of the assembled config — the sole source used to derive the Android
 * interface plan — rather than from mutable runtime state.
 * Route *rules* deliberately stay outside the fingerprint: a per-app rule change alters where
 * connections go inside the box, not the kernel interface, so it must not force a re-establish.
 * DNS servers, the underlying network and metering do shape the Builder — they are included.
 *
 * A null fingerprint means "could not prove the interface is unchanged" and callers must fall
 * back to a full establish.
 */
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

    // include_package/exclude_package become UID rows in the kernel tun filter at establish time.
    // The package NAMES don't change across an uninstall+reinstall — its UID does, and a reused fd
    // would keep filtering the dead UID while the reinstalled app bypasses the tun. Folding the
    // live UIDs in makes a UID change re-establish instead of reusing the fd.
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
