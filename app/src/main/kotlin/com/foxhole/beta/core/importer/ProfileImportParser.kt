package com.foxhole.beta.core.importer

import com.foxhole.beta.BuildConfig
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
        allowInsecureTls: Boolean = BuildConfig.ALLOW_INSECURE_TLS_BY_DEFAULT,
    ): ParsedImport =
        engine.parseUserInput(
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
        allowInsecureTls: Boolean = BuildConfig.ALLOW_INSECURE_TLS_BY_DEFAULT,
    ): ParsedImport =
        engine.parseSubscriptionContent(
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
        allowInsecureTls: Boolean = BuildConfig.ALLOW_INSECURE_TLS_BY_DEFAULT,
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
        allowInsecureTls: Boolean = BuildConfig.ALLOW_INSECURE_TLS_BY_DEFAULT,
    ): String =
        engine.sanitizeResolvedConfig(
            raw = raw,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowInsecureTls = allowInsecureTls,
        )
}
