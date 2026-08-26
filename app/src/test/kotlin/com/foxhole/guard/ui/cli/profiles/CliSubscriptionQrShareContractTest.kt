package com.foxhole.guard.ui.cli.profiles

import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.guard.ui.ProfilesExportSelectionState
import com.foxhole.guard.ui.subscriptionQrProfileIdOrNull
import com.foxhole.guard.ui.toggleSingleProfile
import com.foxhole.guard.ui.toggleSmartProfileAll
import com.foxhole.guard.ui.toggleSmartProfileChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliSubscriptionQrShareContractTest {
    @Test
    fun `subscription qr is exclusive and individual config selection blocks it`() {
        val subscription = profile(
            id = 7L,
            sourceType = ProfileSourceType.SUBSCRIPTION_URL,
            options = listOf(option("vless", ProtocolHint.VLESS), option("vmess", ProtocolHint.VMESS)),
        )
        val other = profile(
            id = 8L,
            sourceType = ProfileSourceType.RAW_CONFIG_JSON,
            options = emptyList(),
            protocolHint = ProtocolHint.WIREGUARD,
        )
        val priorSelection = ProfilesExportSelectionState().toggleSingleProfile(other)
        assertNull(priorSelection.subscriptionQrProfileIdOrNull())

        val subscriptionQr = priorSelection.toggleSmartProfileAll(subscription)

        assertEquals(7L, subscriptionQr.subscriptionQrProfileIdOrNull())
        assertEquals(setOf(7L), subscriptionQr.selectedKeysByProfileId.keys)

        val individualConfig = subscriptionQr.toggleSmartProfileChoice(subscription, "vmess")
        assertNull(individualConfig.subscriptionQrProfileIdOrNull())
        assertEquals(setOf("vless"), individualConfig.selectedKeysByProfileId.getValue(7L))

        val allConfigsSelectedIndividually =
            individualConfig.toggleSmartProfileChoice(subscription, "vmess")
        assertNull(allConfigsSelectedIndividually.subscriptionQrProfileIdOrNull())
        assertEquals(
            setOf("vless", "vmess"),
            allConfigsSelectedIndividually.selectedKeysByProfileId.getValue(7L),
        )

        val withAnotherProfile = subscriptionQr.toggleSingleProfile(other)
        assertNull(withAnotherProfile.subscriptionQrProfileIdOrNull())
        assertTrue(withAnotherProfile.selectedKeysByProfileId.keys.containsAll(listOf(7L, 8L)))
    }

    @Test
    fun `subscription qr reads the stored auto refresh url while manual config qr stays blocked`() {
        val transfer = source("CliProfileTransfer.kt")
        val qrHandler = transfer
            .substringAfter("private fun handleQrTransfer(")
            .substringBefore("private fun handleClipboardTransfer(")
        val resolver = transfer
            .substringAfter("private suspend fun HomeViewModel.resolveSubscriptionQrExport")
            .substringBefore("private fun handleClipboardTransfer(")

        assertTrue(qrHandler.contains("if (subscriptionQrProfileId == null) return"))
        assertTrue(qrHandler.contains("launchSubscriptionQrExport("))
        assertTrue(!qrHandler.contains("runExport"))
        assertTrue(!qrHandler.contains("single.configs"))
        assertTrue(resolver.contains("candidate.sourceType == ProfileSourceType.SUBSCRIPTION_URL"))
        assertTrue(resolver.contains("repository.secretStore.read(profile.secretRef)?.subscriptionUrl"))
        assertTrue(resolver.contains("return CliQrExport(name = profile.name, text = subscriptionUrl)"))
        assertTrue(!resolver.contains("refreshProfile"))
        assertTrue(!resolver.contains("emitError"))
        assertTrue(transfer.contains("val qrReady = subscriptionQrReady"))
        assertTrue(!transfer.contains("selectedKeyCount"))
        assertTrue(transfer.substringAfter("private fun handleFileTransfer(").contains("runExport { exports ->"))
        assertTrue(transfer.substringAfter("private fun handleClipboardTransfer(").contains("runExport { exports ->"))
    }

    private fun option(id: String, protocolHint: ProtocolHint) = ProfileProtocolOption(
        id = id,
        displayName = id,
        protocolHint = protocolHint,
    )

    private fun profile(
        id: Long,
        sourceType: ProfileSourceType,
        options: List<ProfileProtocolOption>,
        protocolHint: ProtocolHint = ProtocolHint.CUSTOM_CONFIG,
    ) = Profile(
        id = id,
        name = if (sourceType == ProfileSourceType.SUBSCRIPTION_URL) "v2rayTun subscription" else "Profile $id",
        sourceType = sourceType,
        secretRef = "secret-$id",
        protocolHint = protocolHint,
        lastUpdatedAt = null,
        lastEtag = null,
        protocolOptions = options,
        selectedProtocolOptionId = options.firstOrNull()?.id,
        isActive = false,
    )

    private fun source(relative: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli/profiles/$relative"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli/profiles/$relative"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli/profiles/$relative"),
        ).first(File::isFile).readText()
}
