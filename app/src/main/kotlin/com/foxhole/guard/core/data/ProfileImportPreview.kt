package com.foxhole.guard.core.data

import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.SubscriptionEntryStatus
import com.foxhole.core.network.ensurePublicUrl
import com.foxhole.core.network.requirePublicUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URI

/**
 * Lightweight parse of a raw import for the add-profile confirmation sheet: the display name,
 * the protocols the payload carries, and the server/subscription domain — WITHOUT touching the
 * database or the network. Null when the payload does not parse (the ordinary import path then
 * surfaces its own human error).
 */
data class ProfileImportPreview(
    val displayName: String,
    val subscription: Boolean,
    val protocolHints: List<ProtocolHint>,
    val host: String?,
    val nodesCount: Int,
    val protocolRows: List<ProfileImportProtocolRow> = emptyList(),
    val insecureTlsProtocolLabels: List<String> = emptyList(),
    val canExcludeInsecureTls: Boolean = false,
)

data class ProfileImportProtocolRow(
    val protocolLabel: String,
    val accepted: Boolean,
    val count: Int,
    val reason: String? = null,
)

/**
 * The protocols a SUBSCRIPTION carries — which cannot be known without fetching its body, so the
 * preview above honestly reports none and this fills them in afterwards. The confirmation sheet
 * used to sit on a permanent "—" for every subscription: the one question it exists to answer
 * ("what am I about to add?") was the one it could not answer.
 *
 * Fetches and parses only; nothing is stored. A failure returns null and the sheet keeps its dash —
 * the ordinary import path will surface the real error if the user goes ahead.
 */
suspend fun ProfileRepository.subscriptionProtocolsPreview(rawInput: String): ProfileImportPreview? {
    val settings = settingsRepository.current()
    return withContext(Dispatchers.IO) {
        runCatching {
            val parsed =
                parser.parseUserInput(
                    input = rawInput,
                    allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
                    allowInsecureTls = true,
                )
            if (parsed.sourceType != ProfileSourceType.SUBSCRIPTION_URL) {
                return@runCatching null
            }
            val sourceUrl = parsed.sourceUrl ?: return@runCatching null
            val safeUrl =
                sourceUrl
                    .ensurePublicUrl(allowHttp = false)
                    .requirePublicUrl(allowHttp = false, resolveHost = true)
            val response =
                subscriptionFetchUseCase.fetchSubscriptionResponse(
                    sourceUrl = sourceUrl,
                    safeUrl = safeUrl,
                    lastEtag = null,
                    allowHttp = false,
                )
            val body = response.body.orEmpty().takeIf(String::isNotBlank) ?: return@runCatching null
            val parsedSubscription =
                parser.parseSubscriptionProfiles(
                    rawContent = body,
                    fallbackName = parsed.displayName,
                    allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
                    allowInsecureTls = true,
                )
            val hints =
                parsedSubscription.profiles
                    .flatMap { profile ->
                        listOf(profile.protocolHint) + profile.protocolOptions.map { it.protocolHint }
                    }
                    .filter { hint -> hint != ProtocolHint.UNKNOWN }
                    .distinct()
            val protocolRows =
                parsedSubscription.entryReports
                    .groupBy { report -> report.protocolLabel to report.status }
                    .map { (key, reports) ->
                        ProfileImportProtocolRow(
                            protocolLabel = key.first,
                            accepted = key.second == SubscriptionEntryStatus.ACCEPTED,
                            count = reports.size,
                            reason = reports.firstNotNullOfOrNull { report -> report.reason },
                        )
                    }
                    .sortedWith(
                        compareByDescending<ProfileImportProtocolRow> { row -> row.accepted }
                            .thenBy { row -> row.protocolLabel },
                    )
            val insecureTlsWarning = parsedSubscription.insecureTlsImportWarning(json)
            val acceptedNodes =
                parsedSubscription.entryReports.count { report ->
                    report.status == SubscriptionEntryStatus.ACCEPTED
                }.takeIf { parsedSubscription.entryReports.isNotEmpty() }
                    ?: parsedSubscription.nodesCount
            val insecureTlsProtocolLabels =
                insecureTlsWarning?.issues?.map { issue -> issue.protocolLabel }.orEmpty()
            ProfileImportPreview(
                displayName = parsed.displayName,
                subscription = true,
                protocolHints = hints,
                host = runCatching { URI(sourceUrl).host }.getOrNull(),
                nodesCount = acceptedNodes,
                protocolRows = protocolRows,
                insecureTlsProtocolLabels = insecureTlsProtocolLabels,
                canExcludeInsecureTls = insecureTlsWarning?.canExcludeAndApply == true,
            )
        }.getOrNull()
    }
}

suspend fun ProfileRepository.rawInputImportPreview(rawInput: String): ProfileImportPreview? {
    val settings = settingsRepository.current()
    return withContext(Dispatchers.IO) {
        runCatching {
            val parsed =
                parser.parseUserInput(
                    input = rawInput,
                    allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
                    allowInsecureTls = true,
                )
            val subscription = parsed.sourceType == ProfileSourceType.SUBSCRIPTION_URL
            val hints =
                (listOf(parsed.protocolHint) + parsed.protocolOptions.map { option -> option.protocolHint })
                    .filter { hint -> hint != ProtocolHint.UNKNOWN }
                    .distinct()
            val host =
                if (subscription) {
                    parsed.sourceUrl?.let { url -> runCatching { URI(url).host }.getOrNull() }
                } else {
                    parsed.protocolOptions
                        .firstNotNullOfOrNull { option -> firstOutboundServer(option.normalizedConfigJson) }
                        ?: parsed.normalizedConfigJson?.let(::firstOutboundServer)
                }
            val protocolRows =
                parsed.entryReports
                    .groupBy { report -> report.protocolLabel to report.status }
                    .map { (key, reports) ->
                        ProfileImportProtocolRow(
                            protocolLabel = key.first,
                            accepted = key.second == SubscriptionEntryStatus.ACCEPTED,
                            count = reports.size,
                            reason = reports.firstNotNullOfOrNull { report -> report.reason },
                        )
                    }.ifEmpty {
                        hints.map { hint ->
                            ProfileImportProtocolRow(
                                protocolLabel = hint.name,
                                accepted = true,
                                count = 1,
                            )
                        }
                    }
            ProfileImportPreview(
                displayName = parsed.displayName,
                subscription = subscription,
                protocolHints = hints,
                host = host,
                nodesCount = parsed.nodesCount,
                protocolRows = protocolRows,
            )
        }.getOrNull()
    }
}

/**
 * Duplicate detection for the add-profile flow: the SAME source already stored earlier —
 * subscription imports match on the stored subscription URL, config imports on the exact trimmed
 * raw payload. Secrets are read per profile (profile counts are small); any read failure simply
 * reports "no duplicate" and the ordinary import proceeds.
 */
suspend fun ProfileRepository.findProfileMatchingRawImport(rawInput: String): Profile? {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) {
        return null
    }
    return withContext(Dispatchers.IO) {
        runCatching {
            val subscriptionUrl =
                parser.parseUserInput(
                    input = trimmed,
                    allowPrivateOutboundHosts =
                    settingsRepository.current().expert.allowPrivateOutboundHosts,
                    allowInsecureTls = true,
                ).takeIf { parsed -> parsed.sourceType == ProfileSourceType.SUBSCRIPTION_URL }
                    ?.sourceUrl
                    ?.let(::normalizedSubscriptionSourceIdentity)
            val rawFingerprint = stableRawImportFingerprint(trimmed)
            for (entity in dao.getAllProfiles()) {
                val secret = runCatching { secretStore.read(entity.secretRef) }.getOrNull() ?: continue
                val duplicate =
                    when {
                        subscriptionUrl != null ->
                            secret.subscriptionUrl
                                ?.let(::normalizedSubscriptionSourceIdentity)
                                ?.equals(subscriptionUrl) == true
                        else ->
                            secret.rawInput
                                ?.let(::stableRawImportFingerprint)
                                ?.equals(rawFingerprint) == true
                    }
                if (duplicate) {
                    return@runCatching resolveDomainProfile(entity)
                }
            }
            null
        }.getOrNull()
    }
}

internal fun normalizedSubscriptionSourceIdentity(rawInput: String): String? =
    runCatching {
        val source = URI(rawInput.trim())
        val scheme =
            source.scheme?.lowercase()?.takeIf { it == "https" || it == "http" }
                ?: return@runCatching null
        val host = source.host?.lowercase()?.takeIf(String::isNotBlank) ?: return@runCatching null
        val port =
            source.port.takeUnless { candidate ->
                candidate == -1 ||
                    (scheme == "https" && candidate == 443) ||
                    (scheme == "http" && candidate == 80)
            } ?: -1
        URI(
            scheme,
            source.rawUserInfo,
            host,
            port,
            source.rawPath,
            source.rawQuery,
            null,
        ).toASCIIString()
    }.getOrNull()

/** First outbound/endpoint `server` host in FoxHole's normalized import document. */
private fun ProfileRepository.firstOutboundServer(configJson: String): String? =
    runCatching {
        val root = json.parseToJsonElement(configJson).jsonObject
        val outbounds = root["outbounds"]?.jsonArray.orEmpty()
        val endpoints = root["endpoints"]?.jsonArray.orEmpty()
        (outbounds + endpoints)
            .mapNotNull { element -> element as? JsonObject }
            .firstNotNullOfOrNull { outbound ->
                (outbound["server"] as? JsonPrimitive)?.contentOrNull?.takeUnless(String::isBlank)
            }
    }.getOrNull()
