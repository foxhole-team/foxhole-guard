package com.foxhole.guard.router.profile

import com.foxhole.core.importer.ProfileImportParser
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.SubscriptionEntryStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.security.MessageDigest
import java.net.URI
import java.util.UUID

internal data class ImportedVpnProfiles(val profiles: List<VpnProfile>, val rejected: Int)

internal class VpnProfileImporter(private val json: Json = Json { ignoreUnknownKeys = false }) {
    private val parser = ProfileImportParser(json)

    fun subscriptionUrlOrNull(input: String, allowInsecureTls: Boolean): String? =
        parseSafely {
            require(input.toByteArray().size in 1..MAX_IMPORT_BYTES)
            val parsed = parser.parseUserInput(input, allowInsecureTls = true)
            if (parsed.sourceType == ProfileSourceType.SUBSCRIPTION_URL) parsed.sourceUrl else null
        }

    fun import(input: String, allowInsecureTls: Boolean = false): ImportedVpnProfiles = parseSafely {
        require(input.toByteArray().size in 1..MAX_IMPORT_BYTES)
        // The shared parser can omit refused nodes, so inspect the complete normalized result before granting consent.
        val parsed = parser.parseUserInput(input, allowInsecureTls = true)
        require(parsed.sourceType != ProfileSourceType.SUBSCRIPTION_URL)
        requireSupported(parsed.protocolHint)
        val requiresConsent = (
            listOfNotNull(parsed.normalizedConfigJson) + parsed.protocolOptions.map {
                it.normalizedConfigJson
            }
            )
            .any(::requiresInsecureTls)
        if (requiresConsent && !allowInsecureTls) throw InsecureTlsConsentRequired()
        ImportedVpnProfiles(
            profiles = listOf(
                VpnProfile(
                    id = UUID.randomUUID().toString(),
                    name = displayName(parsed.displayName),
                    protocol = parsed.protocolHint.name,
                    normalizedConfigJson = requireNotNull(parsed.normalizedConfigJson),
                    insecureTlsConsent = requiresConsent && allowInsecureTls,
                    protocolOptions = parsed.protocolOptions,
                    selectedProtocolOptionId = parsed.selectedProtocolOptionId,
                    subscriptionExpiresAt = validSubscriptionExpiry(parsed.subscriptionExpiresAt),
                ),
            ),
            rejected = parsed.entryReports.count { it.status == SubscriptionEntryStatus.IGNORED_UNSUPPORTED },
        )
    }

    fun importSubscription(
        body: String,
        sourceUrl: String,
        allowInsecureTls: Boolean,
        displayName: String? = null,
        subscriptionExpiresAt: Long? = null,
    ): ImportedVpnProfiles = parseSafely {
        require(body.toByteArray().size in 1..MAX_IMPORT_BYTES)
        val title = displayName?.takeIf(String::isNotBlank) ?: if (sourceUrl.startsWith("ssconf://", ignoreCase = true)) {
            runCatching { URI(sourceUrl).fragment }.getOrNull()?.takeIf(String::isNotBlank)
        } else null
        val parsed = parser.parseSubscriptionProfiles(
            body,
            "VPN",
            allowInsecureTls = true,
            groupCompatibleSingleServerMultiProtocol = true,
        )
        val profiles = parsed.profiles.mapIndexedNotNull { index, profile ->
            if (profile.protocolHint !in supported) return@mapIndexedNotNull null
            val requiresConsent = (listOf(profile.normalizedConfigJson) + profile.protocolOptions.map { it.normalizedConfigJson })
                .any(::requiresInsecureTls)
            if (requiresConsent && !allowInsecureTls) throw InsecureTlsConsentRequired()
            val identity = identity(sourceUrl, profile.displayName, profile.protocolHint.name, index.toString())
            VpnProfile(
                id = identity,
                name = this.displayName(
                    if (parsed.profiles.size == 1 && !title.isNullOrBlank()) title else profile.displayName
                ),
                protocol = profile.protocolHint.name,
                normalizedConfigJson = profile.normalizedConfigJson,
                sourceUrl = sourceUrl,
                sourceIdentity = identity,
                insecureTlsConsent = requiresConsent && allowInsecureTls,
                protocolOptions = profile.protocolOptions,
                selectedProtocolOptionId = profile.selectedProtocolOptionId,
                subscriptionExpiresAt = validSubscriptionExpiry(profile.subscriptionExpiresAt)
                    ?: validSubscriptionExpiry(parsed.subscriptionExpiresAt)
                    ?: validSubscriptionExpiry(subscriptionExpiresAt)
                    ?: subscriptionExpiry(null, sourceUrl),
            )
        }
        require(profiles.isNotEmpty() && profiles.size <= 256)
        ImportedVpnProfiles(
            profiles,
            parsed.entryReports.count { it.status == SubscriptionEntryStatus.IGNORED_UNSUPPORTED } +
                parsed.profiles.size - profiles.size,
        )
    }

    private fun requireSupported(protocol: ProtocolHint) = require(protocol in supported)

    private fun requiresInsecureTls(config: String): Boolean = containsInsecureTls(json.parseToJsonElement(config))

    private fun containsInsecureTls(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.any { (key, value) ->
            (
                key.lowercase() in setOf("insecure", "allowinsecure", "allow_insecure") &&
                    value is JsonPrimitive && (value.booleanOrNull == true || value.content.equals("true", ignoreCase = true))
                ) ||
                containsInsecureTls(value)
        }
        is JsonArray -> element.any(::containsInsecureTls)
        else -> false
    }

    private fun displayName(value: String): String = value.filterNot(Char::isISOControl).take(160).ifBlank { "VPN" }

    private fun identity(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }

    private inline fun <T> parseSafely(block: () -> T): T = try {
        block()
    } catch (error: InsecureTlsConsentRequired) {
        throw error
    } catch (error: IllegalArgumentException) {
        if (error.message == "INSECURE TLS is not allowed") throw InsecureTlsConsentRequired()
        throw ProfileImportFailure()
    } catch (error: IllegalStateException) {
        if (error.message == "INSECURE TLS is not allowed") throw InsecureTlsConsentRequired()
        throw ProfileImportFailure()
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 1024 * 1024
        val supported = setOf(
            ProtocolHint.VLESS, ProtocolHint.VMESS, ProtocolHint.TROJAN, ProtocolHint.SHADOWSOCKS,
            ProtocolHint.OUTLINE, ProtocolHint.WIREGUARD, ProtocolHint.HYSTERIA2, ProtocolHint.NAIVE,
            ProtocolHint.TUIC, ProtocolHint.ANYTLS,
        )
    }
}
