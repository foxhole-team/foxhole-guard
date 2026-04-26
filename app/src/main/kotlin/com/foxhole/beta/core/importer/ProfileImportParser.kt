package com.foxhole.beta.core.importer

import com.foxhole.beta.core.model.ParsedImport
import com.foxhole.beta.core.model.ParsedSubscriptionImport
import com.foxhole.beta.core.network.RemoteHostResolver
import kotlinx.serialization.json.Json

class ProfileImportParser(
    json: Json,
    remoteHostResolver: RemoteHostResolver? = null,
) {
    private val engine = ProfileImportEngine(json, remoteHostResolver)

    fun parseUserInput(
        input: String,
        allowPrivateOutboundHosts: Boolean = false,
        allowHttpSubscriptionUrls: Boolean = false,
        allowInsecureTls: Boolean = false,
    ): ParsedImport =
        engine.parseUserInput(
            input = input,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowHttpSubscriptionUrls = allowHttpSubscriptionUrls,
            allowInsecureTls = allowInsecureTls,
        )

    internal fun parseUserInputWithStrategy(
        input: String,
        allowPrivateOutboundHosts: Boolean = false,
        allowHttpSubscriptionUrls: Boolean = false,
        allowInsecureTls: Boolean = false,
    ): ProfileImportStrategyResult =
        engine.parseUserInputWithStrategy(
            input = input,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowHttpSubscriptionUrls = allowHttpSubscriptionUrls,
            allowInsecureTls = allowInsecureTls,
        )

    fun parseSubscriptionContent(
        rawContent: String,
        fallbackName: String,
        allowPrivateOutboundHosts: Boolean = false,
        allowHttpSubscriptionUrls: Boolean = false,
        allowInsecureTls: Boolean = false,
    ): ParsedImport =
        engine.parseSubscriptionContent(
            rawContent = rawContent,
            fallbackName = fallbackName,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowHttpSubscriptionUrls = allowHttpSubscriptionUrls,
            allowInsecureTls = allowInsecureTls,
        )

    internal fun parseSubscriptionContentWithStrategy(
        rawContent: String,
        fallbackName: String,
        allowPrivateOutboundHosts: Boolean = false,
        allowHttpSubscriptionUrls: Boolean = false,
        allowInsecureTls: Boolean = false,
    ): ProfileSubscriptionContentStrategyResult =
        engine.parseSubscriptionContentWithStrategy(
            rawContent = rawContent,
            fallbackName = fallbackName,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowHttpSubscriptionUrls = allowHttpSubscriptionUrls,
            allowInsecureTls = allowInsecureTls,
        )

    fun parseSubscriptionProfiles(
        rawContent: String,
        fallbackName: String,
        allowPrivateOutboundHosts: Boolean = false,
        allowHttpSubscriptionUrls: Boolean = false,
        allowInsecureTls: Boolean = false,
    ): ParsedSubscriptionImport =
        engine.parseSubscriptionProfiles(
            rawContent = rawContent,
            fallbackName = fallbackName,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowHttpSubscriptionUrls = allowHttpSubscriptionUrls,
            allowInsecureTls = allowInsecureTls,
        )

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
