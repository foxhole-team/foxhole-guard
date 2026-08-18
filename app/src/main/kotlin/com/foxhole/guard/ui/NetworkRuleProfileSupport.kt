package com.foxhole.guard.ui

import com.foxhole.core.model.Profile

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
