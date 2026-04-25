package com.foxhole.beta.core.data

import com.foxhole.beta.core.importer.ProfileImportParser
import com.foxhole.beta.core.model.ParsedImport
import com.foxhole.beta.core.model.ParsedSubscriptionImport
import com.foxhole.beta.core.model.ParsedSubscriptionProfile
import com.foxhole.beta.core.model.StoredProfileProtocolOption
import com.foxhole.beta.core.model.StoredProfileSecret
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

class InsecureTlsProfileConsentRequiredException : IllegalStateException(
    "insecure tls is not allowed without profile consent",
)

internal suspend fun rawImportRequiresInsecureTls(
    parser: ProfileImportParser,
    rawInput: String,
    allowPrivateOutboundHosts: Boolean,
    allowHttpSubscriptionUrls: Boolean,
): Boolean =
    withContext(Dispatchers.IO) {
        parser
            .parseStrictlyOrInsecureTlsFailure(
                rawInput = rawInput,
                allowPrivateOutboundHosts = allowPrivateOutboundHosts,
                allowHttpSubscriptionUrls = allowHttpSubscriptionUrls,
            )
    }

private fun ProfileImportParser.parseStrictlyOrInsecureTlsFailure(
    rawInput: String,
    allowPrivateOutboundHosts: Boolean,
    allowHttpSubscriptionUrls: Boolean,
): Boolean =
    runCatching {
        parseUserInput(
            input = rawInput,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowHttpSubscriptionUrls = allowHttpSubscriptionUrls,
            allowInsecureTls = false,
        )
    }.exceptionOrNull()
        ?.isInsecureTlsPolicyFailure() == true

internal fun Throwable.isInsecureTlsPolicyFailure(): Boolean =
    generateSequence(this) { it.cause }
        .map { error -> error.message.orEmpty().lowercase(Locale.US) }
        .any { message -> message.contains("insecure tls is not allowed") }

internal fun ParsedImport.requiresInsecureTls(json: Json): Boolean =
    normalizedConfigJson?.requiresInsecureTls(json) == true ||
        protocolOptions.any { option -> option.normalizedConfigJson.requiresInsecureTls(json) }

internal fun ParsedSubscriptionImport.requiresInsecureTls(json: Json): Boolean =
    profiles.any { profile -> profile.requiresInsecureTls(json) }

internal fun ParsedSubscriptionProfile.requiresInsecureTls(json: Json): Boolean =
    normalizedConfigJson.requiresInsecureTls(json) ||
        protocolOptions.any { option -> option.normalizedConfigJson.requiresInsecureTls(json) }

internal fun StoredProfileSecret.withInsecureTlsMarkers(
    json: Json,
    forceRequiresInsecureTls: Boolean = false,
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
    )
}

internal fun List<StoredProfileProtocolOption>.withInsecureTlsMarkers(json: Json): List<StoredProfileProtocolOption> =
    map { option ->
        option.copy(
            requiresInsecureTls = option.requiresInsecureTls || option.normalizedConfigJson.requiresInsecureTls(json),
        )
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
