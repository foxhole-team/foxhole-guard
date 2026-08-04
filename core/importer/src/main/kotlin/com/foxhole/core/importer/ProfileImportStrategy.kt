package com.foxhole.core.importer

import com.foxhole.core.model.ParsedImport

internal enum class ProfileImportStrategyId {
    SUBSCRIPTION_URL,
    SMART_CONFIG,
    WIREGUARD_TEXT,
    RAW_XRAY_JSON,
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
