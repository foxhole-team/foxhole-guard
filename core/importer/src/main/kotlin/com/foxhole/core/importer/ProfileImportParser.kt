package com.foxhole.core.importer

import com.foxhole.core.model.ParsedImport
import com.foxhole.core.model.ParsedSubscriptionImport
import com.foxhole.core.model.ParsedSubscriptionProfile
import com.foxhole.core.network.RemoteHostResolver
import kotlinx.serialization.json.Json

class ProfileImportParser(
    private val json: Json,
    remoteHostResolver: RemoteHostResolver? = null,
) {
    private val engine =
        ProfileImportEngine(
            json,
            memoizedPublicHostResolver(remoteHostResolver ?: systemRemoteHostResolver),
        )

    fun parseUserInput(
        input: String,
        allowPrivateOutboundHosts: Boolean = false,
        allowInsecureTls: Boolean = false,
    ): ParsedImport {
        val unwrapped = engine.normalizeInput(unwrapV2rayTun(engine.normalizeInput(input)))
        require(!unwrapped.startsWith("v2raytun:", ignoreCase = true)) { "nested v2rayTun import is not allowed" }
        val codec = CanonicalProfileInterop(json)
        codec.parse(unwrapped) { raw ->
            engine.sanitizeResolvedConfig(raw, allowPrivateOutboundHosts, allowInsecureTls)
        }?.let { return it }
        return codec.enrich(
            engine.parseUserInput(
                input = unwrapped,
                allowPrivateOutboundHosts = allowPrivateOutboundHosts,
                allowInsecureTls = allowInsecureTls,
            )
        )
    }

    internal fun parseUserInputWithStrategy(
        input: String,
        allowPrivateOutboundHosts: Boolean = false,
        allowInsecureTls: Boolean = false,
    ): ProfileImportStrategyResult =
        engine.parseUserInputWithStrategy(
            input = input,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowInsecureTls = allowInsecureTls,
        )

    internal fun parseSubscriptionContentWithStrategy(
        rawContent: String,
        fallbackName: String,
        allowPrivateOutboundHosts: Boolean = false,
        allowInsecureTls: Boolean = false,
    ): ProfileSubscriptionContentStrategyResult =
        engine.parseSubscriptionContentWithStrategy(
            rawContent = rawContent,
            fallbackName = fallbackName,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowInsecureTls = allowInsecureTls,
        )

    fun parseSubscriptionProfiles(
        rawContent: String,
        fallbackName: String,
        allowPrivateOutboundHosts: Boolean = false,
        allowInsecureTls: Boolean = false,
        groupCompatibleSingleServerMultiProtocol: Boolean = false,
    ): ParsedSubscriptionImport {
        require(
            rawContent.length <= MAX_PORTABLE_BYTES && rawContent.toByteArray(Charsets.UTF_8).size <= MAX_PORTABLE_BYTES
        ) {
            "profile payload is too large"
        }
        CanonicalProfileInterop(json).parse(rawContent, allowUnrelatedFormats = true) { raw ->
            engine.sanitizeResolvedConfig(raw, allowPrivateOutboundHosts, allowInsecureTls)
        }?.let { parsed ->
            return ParsedSubscriptionImport(
                displayName = fallbackName,
                profiles = listOf(
                    ParsedSubscriptionProfile(
                        displayName = fallbackName,
                        protocolHint = parsed.protocolHint,
                        normalizedConfigJson = requireNotNull(parsed.normalizedConfigJson),
                        subscriptionExpiresAt = parsed.subscriptionExpiresAt,
                        protocolOptions = parsed.protocolOptions,
                        selectedProtocolOptionId = parsed.selectedProtocolOptionId,
                    )
                ),
                subscriptionExpiresAt = parsed.subscriptionExpiresAt,
                entryReports = parsed.entryReports,
            )
        }
        return engine.parseSubscriptionProfiles(
            rawContent = rawContent,
            fallbackName = fallbackName,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowInsecureTls = allowInsecureTls,
            groupCompatibleSingleServerMultiProtocol = groupCompatibleSingleServerMultiProtocol,
        )
    }

    fun sanitizeResolvedConfig(
        raw: String,
        allowPrivateOutboundHosts: Boolean = false,
        allowInsecureTls: Boolean = false,
    ): String =
        engine.sanitizeResolvedConfig(
            raw = raw,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowInsecureTls = allowInsecureTls,
        )
}
