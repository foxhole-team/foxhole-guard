package com.foxhole.guard.core.data

import com.foxhole.core.importer.ProfileImportParser
import com.foxhole.core.model.ParsedImport
import com.foxhole.core.model.ParsedSubscriptionImport
import com.foxhole.core.model.ParsedSubscriptionProfile
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.StoredProfileProtocolOption
import com.foxhole.core.model.StoredProfileSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

data class InsecureTlsImportIssue(
    val protocolLabel: String,
)

data class InsecureTlsImportWarning(
    val issues: List<InsecureTlsImportIssue>,
    val canExcludeAndApply: Boolean,
)

class InsecureTlsProfileConsentRequiredException(
    val warning: InsecureTlsImportWarning? = null,
) : IllegalStateException(
    "INSECURE TLS is not allowed without profile consent",
)

internal suspend fun rawImportRequiresInsecureTls(
    parser: ProfileImportParser,
    rawInput: String,
    allowPrivateOutboundHosts: Boolean,
): Boolean =
    withContext(Dispatchers.IO) {
        parser
            .parseStrictlyOrInsecureTlsFailure(
                rawInput = rawInput,
                allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            )
    }

suspend fun ProfileRepository.rawInputInsecureTlsWarning(
    rawInput: String,
): InsecureTlsImportWarning? {
    val settings = settingsRepository.current()
    val requiresConsent =
        rawImportRequiresInsecureTls(
            parser = parser,
            rawInput = rawInput,
            allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
        )
    if (!requiresConsent) {
        return null
    }
    return withContext(Dispatchers.IO) {
        val parsed =
            parser.parseUserInput(
                input = rawInput,
                allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
                allowInsecureTls = true,
            )
        val localParsedProfiles =
            if (parsed.sourceType == com.foxhole.core.model.ProfileSourceType.SUBSCRIPTION_URL) {
                null
            } else {
                runCatching {
                    parser.parseSubscriptionProfiles(
                        rawContent = rawInput,
                        fallbackName = parsed.displayName,
                        allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
                        allowInsecureTls = true,
                    )
                }.getOrNull()
            }
        localParsedProfiles?.insecureTlsImportWarning(json) ?: parsed.insecureTlsImportWarning(json)
    }
}

private fun ProfileImportParser.parseStrictlyOrInsecureTlsFailure(
    rawInput: String,
    allowPrivateOutboundHosts: Boolean,
): Boolean =
    runCatching {
        parseUserInput(
            input = rawInput,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowInsecureTls = false,
        )
    }.exceptionOrNull()
        ?.isInsecureTlsPolicyFailure() == true

internal fun Throwable.isInsecureTlsPolicyFailure(): Boolean =
    generateSequence(this) { it.cause }
        .map { error -> error.message.orEmpty().lowercase(Locale.US) }
        .any { message -> message.contains("insecure tls is not allowed") }

internal fun allowsInsecureTlsForStoredProfileRuntime(
    allowInsecureTlsGlobally: Boolean,
    secret: StoredProfileSecret,
): Boolean =
    allowInsecureTlsGlobally || secret.hasInsecureTlsConsent()

internal fun ParsedImport.requiresInsecureTls(json: Json): Boolean =
    normalizedConfigJson?.requiresInsecureTls(json) == true ||
        protocolOptions.any { option -> option.normalizedConfigJson.requiresInsecureTls(json) }

internal fun ParsedSubscriptionImport.requiresInsecureTls(json: Json): Boolean =
    profiles.any { profile -> profile.requiresInsecureTls(json) }

internal fun ParsedSubscriptionProfile.requiresInsecureTls(json: Json): Boolean =
    normalizedConfigJson.requiresInsecureTls(json) ||
        protocolOptions.any { option -> option.normalizedConfigJson.requiresInsecureTls(json) }

internal fun ParsedImport.insecureTlsImportWarning(json: Json): InsecureTlsImportWarning? {
    val issues = protocolOptions.insecureTlsImportIssues(json)
        .ifEmpty {
            if (normalizedConfigJson?.requiresInsecureTls(json) == true) {
                listOf(InsecureTlsImportIssue(protocolHint.importWarningLabel()))
            } else {
                emptyList()
            }
        }
    if (issues.isEmpty()) {
        return null
    }
    val secureOptionCount = protocolOptions.count { option -> !option.resolvedRequiresInsecureTls(json) }
    return InsecureTlsImportWarning(
        issues = issues,
        canExcludeAndApply = protocolOptions.size > 1 && secureOptionCount > 0,
    )
}

internal fun ParsedSubscriptionImport.insecureTlsImportWarning(json: Json): InsecureTlsImportWarning? {
    val issues =
        profiles
            .flatMap { profile ->
                profile.protocolOptions.insecureTlsImportIssues(json)
                    .ifEmpty {
                        if (profile.normalizedConfigJson.requiresInsecureTls(json)) {
                            listOf(InsecureTlsImportIssue(profile.protocolHint.importWarningLabel()))
                        } else {
                            emptyList()
                        }
                    }
            }
            .distinctBy(InsecureTlsImportIssue::protocolLabel)
    if (issues.isEmpty()) {
        return null
    }
    val hasInsecureOption =
        profiles.any { profile ->
            if (profile.protocolOptions.isEmpty()) {
                profile.normalizedConfigJson.requiresInsecureTls(json)
            } else {
                profile.protocolOptions.any { option -> option.resolvedRequiresInsecureTls(json) }
            }
        }
    val hasSecureOption =
        profiles.any { profile ->
            if (profile.protocolOptions.isEmpty()) {
                !profile.normalizedConfigJson.requiresInsecureTls(json)
            } else {
                profile.protocolOptions.any { option -> !option.resolvedRequiresInsecureTls(json) }
            }
        }
    val canExclude = hasInsecureOption && hasSecureOption
    return InsecureTlsImportWarning(
        issues = issues,
        canExcludeAndApply = canExclude,
    )
}

internal fun ParsedImport.withoutInsecureTlsOptions(json: Json): ParsedImport {
    if (protocolOptions.isEmpty()) {
        require(normalizedConfigJson?.requiresInsecureTls(json) != true) {
            "no secure protocols remain after excluding INSECURE TLS"
        }
        return this
    }
    val secureOptions = protocolOptions.filterNot { option -> option.resolvedRequiresInsecureTls(json) }
    require(secureOptions.isNotEmpty()) { "no secure protocols remain after excluding INSECURE TLS" }
    val selectedOption =
        secureOptions.firstOrNull { option -> option.id == selectedProtocolOptionId }
            ?: secureOptions.first()
    return copy(
        protocolHint = selectedOption.protocolHint,
        normalizedConfigJson = selectedOption.normalizedConfigJson,
        protocolOptions = secureOptions,
        selectedProtocolOptionId = selectedOption.id,
    )
}

internal fun ParsedSubscriptionImport.withoutInsecureTlsOptions(json: Json): ParsedSubscriptionImport {
    val secureProfiles = profiles.mapNotNull { profile -> profile.withoutInsecureTlsOptionsOrNull(json) }
    require(secureProfiles.isNotEmpty()) { "no secure protocols remain after excluding INSECURE TLS" }
    return copy(profiles = secureProfiles)
}

private fun ParsedSubscriptionProfile.withoutInsecureTlsOptionsOrNull(json: Json): ParsedSubscriptionProfile? =
    if (protocolOptions.isEmpty()) {
        takeUnless { normalizedConfigJson.requiresInsecureTls(json) }
    } else {
        protocolOptions
            .filterNot { option -> option.resolvedRequiresInsecureTls(json) }
            .takeIf { secureOptions -> secureOptions.isNotEmpty() }
            ?.let { secureOptions ->
                val selectedOption =
                    secureOptions.firstOrNull { option -> option.id == selectedProtocolOptionId }
                        ?: secureOptions.first()
                copy(
                    protocolHint = selectedOption.protocolHint,
                    normalizedConfigJson = selectedOption.normalizedConfigJson,
                    protocolOptions = secureOptions,
                    selectedProtocolOptionId = selectedOption.id,
                )
            }
    }

internal fun StoredProfileSecret.withInsecureTlsMarkers(
    json: Json,
    forceRequiresInsecureTls: Boolean = false,
    grantInsecureTlsConsent: Boolean = false,
): StoredProfileSecret {
    val markedOptions = protocolOptions.withInsecureTlsMarkers(json)
    val resolvedRequiresInsecureTls = resolvedConfigJson?.requiresInsecureTls(json) == true
    return copy(
        protocolOptions = markedOptions,
        requiresInsecureTls =
        requiresInsecureTls ||
            forceRequiresInsecureTls ||
            resolvedRequiresInsecureTls ||
            markedOptions.any(StoredProfileProtocolOption::requiresInsecureTls),
        insecureTlsConsentGranted =
        when {
            insecureTlsConsentGranted == true || grantInsecureTlsConsent -> true
            insecureTlsConsentGranted == false -> false
            else -> false
        },
    )
}

internal fun StoredProfileSecret.hasInsecureTlsConsent(): Boolean =
    when (insecureTlsConsentGranted) {
        true -> true
        false -> false

        null -> requiresInsecureTls
    }

internal fun List<StoredProfileProtocolOption>.withInsecureTlsMarkers(json: Json): List<StoredProfileProtocolOption> =
    map { option ->
        option.copy(
            requiresInsecureTls = option.requiresInsecureTls || option.normalizedConfigJson.requiresInsecureTls(json),
        )
    }

private fun List<StoredProfileProtocolOption>.insecureTlsImportIssues(json: Json): List<InsecureTlsImportIssue> =
    filter { option -> option.protocolHint.hasInsecureTlsImportRisk() && option.resolvedRequiresInsecureTls(json) }
        .map { option -> InsecureTlsImportIssue(option.protocolHint.importWarningLabel()) }
        .distinctBy(InsecureTlsImportIssue::protocolLabel)

private fun StoredProfileProtocolOption.resolvedRequiresInsecureTls(json: Json): Boolean =
    requiresInsecureTls || normalizedConfigJson.requiresInsecureTls(json)

private fun ProtocolHint.hasInsecureTlsImportRisk(): Boolean =
    this in setOf(
        ProtocolHint.VLESS,
        ProtocolHint.TROJAN,
        ProtocolHint.HYSTERIA2,
    )

private fun ProtocolHint.importWarningLabel(): String =
    when (this) {
        ProtocolHint.VLESS -> "VLESS"
        ProtocolHint.TROJAN -> "TROJAN"
        ProtocolHint.SHADOWSOCKS -> "SHADOWSOCKS"
        ProtocolHint.WIREGUARD -> "WIREGUARD"
        ProtocolHint.HYSTERIA2 -> "HYSTERIA2"
        ProtocolHint.VMESS -> "VMESS"
        ProtocolHint.OUTLINE -> "OUTLINE"
        ProtocolHint.NAIVE -> "NAIVE"
        ProtocolHint.TUIC -> "TUIC"
        ProtocolHint.ANYTLS -> "ANYTLS"
        ProtocolHint.TOR -> "TOR"
        ProtocolHint.LOCAL_GUARD -> "LOCAL-GUARD"
        ProtocolHint.CUSTOM_CONFIG -> "CUSTOM-CONFIG"
        ProtocolHint.UNKNOWN -> "UNKNOWN"
    }

internal fun String.requiresInsecureTls(json: Json): Boolean =
    runCatching {
        json.parseToJsonElement(this).containsInsecureTls()
    }.getOrDefault(false)

private fun JsonElement.containsInsecureTls(): Boolean =
    when (this) {
        is JsonObject ->
            any { (key, value) ->
                key.isInsecureTlsKey() && value.isJsonTrue() ||
                    value.containsInsecureTls()
            }

        is JsonArray -> any(JsonElement::containsInsecureTls)
        else -> false
    }

private fun String.isInsecureTlsKey(): Boolean =
    equals("insecure", ignoreCase = true) ||
        equals("allowInsecure", ignoreCase = true) ||
        equals("allow_insecure", ignoreCase = true)

private fun JsonElement.isJsonTrue(): Boolean =
    this is JsonPrimitive &&
        (jsonPrimitive.booleanOrNull == true || jsonPrimitive.content.equals("true", ignoreCase = true))
