package com.foxhole.guard.router

import java.security.MessageDigest

internal fun routerPhoneTransferCapabilities(
    capability: RouterPanelCapabilities,
    installed: Set<RouterInstallProtocol>?,
): RouterPanelCapabilities = if (installed == null) capability else capability.copy(
    coreProtocols = capability.coreProtocols.intersect(installed.map { it.canonical }.toSet()),
)

internal data class RouterPhoneTransferRequest(val profileId: String, val optionId: String?) {
    init {
        require(profileId.isNotBlank() && profileId.length <= 160 && profileId.none(Char::isISOControl))
        require(optionId == null || optionId.isNotBlank() && optionId.length <= 160 && optionId.none(Char::isISOControl))
    }
    val choiceId: String = routerProfileDigest(profileId + '\u0000' + optionId.orEmpty())
    override fun toString(): String = "RouterPhoneTransferRequest"
}

internal fun routerPhoneTransferRequests(profileId: String, optionIds: List<String>): List<RouterPhoneTransferRequest> =
    if (optionIds.isEmpty()) listOf(RouterPhoneTransferRequest(profileId, null))
    else optionIds.distinct().map { RouterPhoneTransferRequest(profileId, it) }

internal class RouterPhoneTransferChoice private constructor(
    val phoneProfileId: String,
    val displayName: String,
    val protocol: String,
    val nodeName: String,
    private val optionId: String?,
    private val configurationHash: String,
    private val sourceHash: String,
) {
    val request: RouterPhoneTransferRequest = RouterPhoneTransferRequest(phoneProfileId, optionId)
    val choiceId: String get() = request.choiceId

    fun matches(selection: RouterProfileSelection): Boolean =
        phoneProfileId == selection.phoneProfileId && displayName == selection.displayName &&
            protocol == selection.selectedProtocol && nodeName == selection.selectedNodeName &&
            optionId == selection.selectedOptionId && configurationHash == fingerprint(selection.selectedConfiguration) &&
            sourceHash == fingerprint(selection.sourceUrl.orEmpty())

    override fun toString(): String = "RouterPhoneTransferChoice(protocol=$protocol)"

    companion object {
        fun capture(selection: RouterProfileSelection): RouterPhoneTransferChoice = RouterPhoneTransferChoice(
            selection.phoneProfileId, selection.displayName, selection.selectedProtocol,
            selection.selectedNodeName, selection.selectedOptionId,
            fingerprint(selection.selectedConfiguration), fingerprint(selection.sourceUrl.orEmpty()),
        )

        private fun fingerprint(value: String): String {
            val bytes = value.toByteArray(Charsets.UTF_8)
            return try {
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            } finally { bytes.fill(0) }
        }
    }
}

internal class RouterPhoneTransferCatalog(
    val pairingId: String,
    val generation: Long,
    val choices: List<RouterPhoneTransferChoice>,
) {
    override fun toString(): String = "RouterPhoneTransferCatalog(choices=${choices.size})"
}

internal data class RouterPhoneTransferResult(
    val requested: Int,
    val confirmedChoiceIds: Set<String>,
) {
    val complete: Boolean get() = requested > 0 && confirmedChoiceIds.size == requested
}
