package com.foxhole.guard.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterPhoneProfileTransferTest {
    private fun capability(core: Set<String> = setOf("vless", "wireguard", "vmess"),
        legacy: Set<String> = setOf("hysteria2")) = RouterPanelCapabilities(
        core, legacy, emptySet(), true, true, true, true,
    )

    @Test fun installedModuleFactsRestrictTheCompiledCoreProtocolList() {
        val result = routerPhoneTransferCapabilities(capability(), setOf(RouterInstallProtocol.VLESS))
        assertEquals(setOf("vless"), result.coreProtocols)
        assertEquals(setOf("hysteria2"), result.legacyProtocols)
    }

    @Test fun emptyCoreInventoryPreservesAnAuthenticatedLegacyClient() {
        val result = routerPhoneTransferCapabilities(capability(), emptySet())
        assertTrue(result.coreProtocols.isEmpty())
        assertEquals(setOf("hysteria2"), result.legacyProtocols)
    }

    @Test fun olderRoutersWithoutInventoryUseTheirActualCapabilities() {
        assertEquals(capability(), routerPhoneTransferCapabilities(capability(), null))
    }

    @Test fun aCatalogEntryDoesNotCreateAnUnadvertisedCapability() {
        val result = routerPhoneTransferCapabilities(capability(emptySet(), emptySet()),
            setOf(RouterInstallProtocol.WIREGUARD))
        assertTrue(result.coreProtocols.isEmpty())
        assertTrue(result.legacyProtocols.isEmpty())
    }

    private fun selection(config: String = "secret configuration", source: String? = "https://example.org/private-token",
        option: String? = "node-1", id: String = "profile-1", protocol: String = "vless",
        name: String = "Profile", node: String = "Node") = RouterProfileSelection(
        id, name, protocol, node, option, source, config,
    )

    @Test fun unchangedSelectionStillMatches() {
        val original = selection()
        assertTrue(RouterPhoneTransferChoice.capture(original).matches(selection()))
    }

    @Test fun changedConfigurationIsRejectedBeforeTransfer() {
        assertFalse(RouterPhoneTransferChoice.capture(selection()).matches(selection(config = "different secret")))
    }

    @Test fun changedSubscriptionIsRejectedBeforeTransfer() {
        assertFalse(RouterPhoneTransferChoice.capture(selection()).matches(selection(source = "https://example.org/other-token")))
    }

    @Test fun changedProtocolChoiceIsRejectedBeforeTransfer() {
        val choice = RouterPhoneTransferChoice.capture(selection())
        assertFalse(choice.matches(selection(option = "node-2")))
        assertFalse(choice.matches(selection(protocol = "wireguard")))
        assertFalse(choice.matches(selection(node = "Different node")))
    }

    @Test fun differentOrRenamedProfileIsRejected() {
        val choice = RouterPhoneTransferChoice.capture(selection())
        assertFalse(choice.matches(selection(id = "profile-2")))
        assertFalse(choice.matches(selection(name = "Different profile")))
    }

    @Test fun presentationObjectsNeverExposeConfigurationOrSubscriptionSecrets() {
        val choice = RouterPhoneTransferChoice.capture(selection())
        assertFalse(choice.toString().contains("secret"))
        assertFalse(choice.toString().contains("private-token"))
        assertFalse(RouterPhoneTransferCatalog("pair-1", 1, listOf(choice)).toString().contains("private-token"))
    }

    @Test fun partialBatchCannotBeReportedAsComplete() {
        assertFalse(RouterPhoneTransferResult(3, setOf("profile-1", "profile-2")).complete)
        assertTrue(RouterPhoneTransferResult(2, setOf("profile-1", "profile-2")).complete)
        assertFalse(RouterPhoneTransferResult(0, emptySet()).complete)
    }

    @Test fun allSavedSubscriptionsAndDirectProfilesContributeCandidates() {
        val saved = listOf("subscription-1" to listOf("first", "second"),
            "subscription-2" to listOf("first", "third"), "direct" to emptyList())
        val requests = saved.flatMap { (id, options) -> routerPhoneTransferRequests(id, options) }
        assertEquals(5, requests.size)
        assertEquals(setOf("subscription-1", "subscription-2", "direct"), requests.map { it.profileId }.toSet())
        assertEquals(5, requests.map { it.choiceId }.toSet().size)
        assertTrue(RouterPhoneTransferRequest("subscription-2", "first") in requests)
        assertTrue(RouterPhoneTransferRequest("direct", null) in requests)
    }

    @Test fun duplicateNodeIdsAreCollapsedOnlyWithinTheSameSavedProfile() {
        assertEquals(2, routerPhoneTransferRequests("subscription", listOf("first", "first", "second")).size)
        assertFalse(RouterPhoneTransferRequest("subscription-1", "first").choiceId ==
            RouterPhoneTransferRequest("subscription-2", "first").choiceId)
    }

    @Test fun twoNodesOfTheSameSubscriptionHaveIndependentSelectionsAndConfirmations() {
        val first = RouterPhoneTransferChoice.capture(selection(option = "first"))
        val second = RouterPhoneTransferChoice.capture(selection(option = "second"))
        assertEquals(first.phoneProfileId, second.phoneProfileId)
        assertFalse(first.choiceId == second.choiceId)
        assertEquals("second", second.request.optionId)
        assertFalse(RouterPhoneTransferResult(2, setOf(first.choiceId)).complete)
        assertTrue(RouterPhoneTransferResult(2, setOf(first.choiceId, second.choiceId)).complete)
    }
}
