package com.foxhole.core.importer

import com.foxhole.core.model.ParsedImport
import com.foxhole.core.model.ParsedSubscriptionImport
import com.foxhole.core.network.RemoteHostResolver
import kotlinx.serialization.json.Json

class ProfileImportParser(
    json: Json,
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
    ): ParsedImport =
        engine.parseUserInput(
            input = input,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowInsecureTls = allowInsecureTls,
        )

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
    ): ParsedSubscriptionImport =
        engine.parseSubscriptionProfiles(
            rawContent = rawContent,
            fallbackName = fallbackName,
            allowPrivateOutboundHosts = allowPrivateOutboundHosts,
            allowInsecureTls = allowInsecureTls,
            groupCompatibleSingleServerMultiProtocol = groupCompatibleSingleServerMultiProtocol,
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
