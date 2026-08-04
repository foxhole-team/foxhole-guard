package com.foxhole.guard.ui

import com.foxhole.core.model.Profile

/**
 * Resolves the concrete protocol pinned by a Wi-Fi/cellular rule.
 * Single-protocol profiles need no extra pin; multi-protocol profiles never return an implicit
 * "auto" choice and fall back deterministically to the profile selection or first protocol.
 */
internal fun Profile.resolveNetworkRuleProtocolOptionId(requestedOptionId: String?): String? =
    protocolOptions
        .takeIf { options -> options.size > 1 }
        ?.let { options ->
            requestedOptionId
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.takeIf { requestedId -> options.any { option -> option.id == requestedId } }
                ?: selectedProtocolOptionId
                    ?.takeIf { selectedId -> options.any { option -> option.id == selectedId } }
                ?: options.first().id
        }
