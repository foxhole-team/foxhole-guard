package com.foxhole.guard.router

import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StoredProfileSecret
import com.foxhole.guard.router.profile.VpnProfileImporter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GuardRouterProfilesTest {
    private fun snapshot(address: String = "1.1.1.1", subscription: String? = null): GuardRouterProfileCapture {
        val parsed = VpnProfileImporter().import("hysteria2://SyntheticOnly@$address:443#Test").profiles.single()
        val profile = Profile(7, "Test", if (subscription == null) ProfileSourceType.SHARE_URI else ProfileSourceType.SUBSCRIPTION_URL,
            "synthetic-secret", ProtocolHint.HYSTERIA2, 1, null, isActive = false)
        val secret = StoredProfileSecret(resolvedConfigJson = parsed.normalizedConfigJson, subscriptionUrl = subscription)
        return GuardRouterProfileCapture(profile, secret, Settings(), null, parsed.normalizedConfigJson)
    }
    private class Access(var current: GuardRouterProfileCapture) : GuardRouterProfileAccess {
        var reads = 0; var imports = 0; var onResolve: () -> Unit = {}
        override suspend fun capture(id: Long): GuardRouterProfileCapture { assertEquals(7L, id); reads++; return current }
        override fun resolve(capture: GuardRouterProfileCapture): String { onResolve(); return capture.rawConfig }
        override suspend fun isCurrent(capture: GuardRouterProfileCapture) = current.profile == capture.profile && current.secret == capture.secret
        override suspend fun importProfile(value: String) { imports++ }
    }
    @Test fun selectedExportReadsOneCommittedCaptureAndDoesNotMutateProfiles() = runTest {
        val access = Access(snapshot()); val selected = GuardRouterProfiles(access).selected("7")
        assertEquals("7", selected.phoneProfileId); assertEquals("hysteria2", selected.selectedProtocol)
        assertEquals(1, access.reads); assertEquals(0, access.imports)
        assertTrue(VpnProfileImporter().import(selected.selectedConfiguration).profiles.single().normalizedConfigJson.contains("1.1.1.1"))
    }
    @Test fun mutationDuringResolutionRefusesInsteadOfMixingSecretAndConfiguration() = runTest {
        val access = Access(snapshot()); access.onResolve = { access.current = snapshot("8.8.8.8") }
        try { GuardRouterProfiles(access).selected("7"); fail("changed profile exported") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.CONFLICT, failure.kind) }
        assertEquals(0, access.imports)
    }
    @Test fun anAbaChangeCannotSubstituteTheIntermediateConfiguration() = runTest {
        val original = snapshot(); val access = Access(original)
        access.onResolve = { access.current = snapshot("8.8.8.8"); access.current = original }
        val selected = GuardRouterProfiles(access).selected("7")
        val config = VpnProfileImporter().import(selected.selectedConfiguration).profiles.single().normalizedConfigJson
        assertTrue(config.contains("1.1.1.1")); assertFalse(config.contains("8.8.8.8")); assertEquals(0, access.imports)
    }
    @Test fun subscriptionAndSelectedConfigurationComeFromTheSameCapture() = runTest {
        val access = Access(snapshot(subscription = "https://profiles.example.com/list"))
        val selected = GuardRouterProfiles(access).selected("7")
        assertEquals("https://profiles.example.com/list", selected.sourceUrl)
        assertTrue(VpnProfileImporter().import(selected.selectedConfiguration).profiles.single().normalizedConfigJson.contains("1.1.1.1"))
    }
    @Test fun invalidIdRefusesBeforeAnyProfileRead() = runTest {
        val access = Access(snapshot())
        try { GuardRouterProfiles(access).selected("../7"); fail("invalid id read") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.UNSUPPORTED, failure.kind) }
        assertEquals(0, access.reads); assertEquals(0, access.imports)
    }
}
