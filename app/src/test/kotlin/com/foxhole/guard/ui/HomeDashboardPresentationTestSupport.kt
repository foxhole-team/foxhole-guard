package com.foxhole.guard.ui

import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint

internal open class HomeDashboardPresentationTestSupport {
    protected fun smartProfile(): Profile =
        Profile(
            id = 1L,
            name = "Smart",
            sourceType = ProfileSourceType.SUBSCRIPTION_URL,
            secretRef = "secret",
            protocolHint = ProtocolHint.VLESS,
            lastUpdatedAt = null,
            lastEtag = null,
            protocolOptions =
            listOf(
                ProfileProtocolOption(id = "vless", displayName = "VLESS", protocolHint = ProtocolHint.VLESS),
                ProfileProtocolOption(id = "trojan", displayName = "Trojan", protocolHint = ProtocolHint.TROJAN),
                ProfileProtocolOption(id = "wg", displayName = "WireGuard", protocolHint = ProtocolHint.WIREGUARD),
            ),
            selectedProtocolOptionId = "vless",
            isActive = true,
        )
}
