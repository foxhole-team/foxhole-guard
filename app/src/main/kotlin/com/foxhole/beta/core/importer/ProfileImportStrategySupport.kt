package com.foxhole.beta.core.importer

import com.foxhole.beta.core.model.ParsedImport

internal data class NormalizedRoutePort(
    val ports: List<Int>,
    val portRanges: List<String>,
)

internal data class UserInputStrategyContext(
    val input: String,
    val allowPrivateOutboundHosts: Boolean,
    val allowInsecureTls: Boolean,
)

internal interface UserInputImportStrategy {
    val id: ProfileImportStrategyId

    fun tryParse(context: UserInputStrategyContext): ParsedImport?
}

internal data class SubscriptionContentStrategyContext(
    val input: String,
    val fallbackName: String,
    val allowPrivateOutboundHosts: Boolean,
    val allowInsecureTls: Boolean,
)

internal interface SubscriptionContentImportStrategy {
    val id: ProfileSubscriptionContentStrategyId

    fun tryParse(context: SubscriptionContentStrategyContext): ParsedImport?
}
