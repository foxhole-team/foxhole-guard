package com.foxhole.guard.router

import com.foxhole.core.network.ensurePublicSubscriptionUrl

internal class RouterProfileSelection(
    val phoneProfileId: String,
    val displayName: String,
    val selectedProtocol: String,
    val selectedNodeName: String,
    val selectedOptionId: String?,
    val sourceUrl: String?,
    val selectedConfiguration: String,
) {
    init {
        require(phoneProfileId.isNotBlank() && phoneProfileId.length <= 160 && phoneProfileId.none(Char::isISOControl))
        require(displayName.isNotBlank() && displayName.length <= 160 && displayName.none(Char::isISOControl))
        require(selectedNodeName.isNotBlank() && selectedNodeName.length <= 160 && selectedNodeName.none(Char::isISOControl))
        require(selectedOptionId == null || selectedOptionId.isNotBlank() && selectedOptionId.length <= 160 && selectedOptionId.none(Char::isISOControl))
        require(selectedProtocol.matches(Regex("[a-z0-9]{1,32}")))
        require(sourceUrl == null || sourceUrl.length in 1..16384 && sourceUrl.none(Char::isISOControl))
        require(selectedConfiguration.toByteArray(Charsets.UTF_8).size in 1..262144)
    }
    override fun toString(): String = "RouterProfileSelection(protocol=$selectedProtocol, subscription=${sourceUrl != null})"
}

