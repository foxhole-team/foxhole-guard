package com.foxhole.beta.core.importer

import com.foxhole.beta.core.model.ParsedImport

internal enum class ProfileImportStrategyId {
    SUBSCRIPTION_URL,
    SMART_CONFIG,
    WIREGUARD_TEXT,
    RAW_XRAY_JSON,
    RAW_SING_BOX_JSON,
    DIRECT_NODE_LINES,
}

internal data class ProfileImportStrategyResult(
    val strategyId: ProfileImportStrategyId,
    val parsed: ParsedImport,
)

internal enum class ProfileSubscriptionContentStrategyId {
    USER_INPUT,
    SMART_CONFIG_PAYLOAD,
    BASE64_SUBSCRIPTION_PAYLOAD,
    DIRECT_SUBSCRIPTION_PAYLOAD,
}

internal data class ProfileSubscriptionContentStrategyResult(
    val strategyId: ProfileSubscriptionContentStrategyId,
    val parsed: ParsedImport,
)

internal fun orderedUserInputStrategyIds(): List<ProfileImportStrategyId> =
    listOf(
        ProfileImportStrategyId.SUBSCRIPTION_URL,
        ProfileImportStrategyId.SMART_CONFIG,
        ProfileImportStrategyId.WIREGUARD_TEXT,
        ProfileImportStrategyId.RAW_XRAY_JSON,
        ProfileImportStrategyId.RAW_SING_BOX_JSON,
        ProfileImportStrategyId.DIRECT_NODE_LINES,
    )

internal fun orderedSubscriptionContentStrategyIds(): List<ProfileSubscriptionContentStrategyId> =
    listOf(
        ProfileSubscriptionContentStrategyId.USER_INPUT,
        ProfileSubscriptionContentStrategyId.SMART_CONFIG_PAYLOAD,
        ProfileSubscriptionContentStrategyId.BASE64_SUBSCRIPTION_PAYLOAD,
        ProfileSubscriptionContentStrategyId.DIRECT_SUBSCRIPTION_PAYLOAD,
    )
