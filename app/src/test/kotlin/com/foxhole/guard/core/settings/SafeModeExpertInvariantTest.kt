package com.foxhole.guard.core.settings

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.DiagnosticsRetention
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.KnownApplicationIdentity
import com.foxhole.core.model.LocalAuthSettings
import com.foxhole.core.model.LocalSurfaceSettings
import com.foxhole.core.model.PendingQuarantineAppDetails
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.RetentionPolicy
import com.foxhole.core.model.RetentionPreset
import com.foxhole.core.model.RoutingRuleAction
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural guard for [disarmedBySafeMode].
 *
 * Safe mode used to be four hand-written positive whitelists that had already drifted apart from
 * each other, and any [ExpertSettings] field added after they were written was silently reset on
 * the next normalization — with no test failing. This test makes that impossible: every field of
 * the serialized shape must be classified below, so adding one to the model fails here until
 * somebody decides whether safe mode disarms it.
 */
internal class SafeModeExpertInvariantTest {
    /** Survives safe mode: identity, consent, the firewall block lane, journal policy. */
    private val preserved =
        setOf(
            "unlockedAt",
            "warningAcknowledgedAt",
            "blockScreenshots",
            "firewallEnabled",
            "newAppQuarantineEnabled",
            "systemDnsProtectionEnabled",
            "networkActivityLogging",
            "diagnosticsRetention",
            "diagnosticsRetentionPolicy",
            "allowInsecureTls",
            "quarantineKnownApplications",
            "pendingQuarantinePackages",
            "pendingQuarantineAppDetails",
            "quarantinePolicyRevision",
            "blockedPackagesEnabled",
            "blockAppsAlways",
        )

    /** Reset to its default: every routing lane and every locally published surface. */
    private val disarmed =
        setOf(
            "sniff",
            "strictRoute",
            "bypassLan",
            "allowPrivateOutboundHosts",
            "perAppRoutingMode",
            "siteRoutingAction",
            "localSurfaces",
            "rawLiveDiagnostics",
        )

    /** Neither kept verbatim nor reset — filtered down to the BLOCK lane. */
    private val transformed = setOf("appAssignments")

    private val json = Json { encodeDefaults = true }

    @OptIn(ExperimentalSerializationApi::class)
    private val fieldNames: List<String> =
        serializer<ExpertSettings>().descriptor.let { descriptor ->
            (0 until descriptor.elementsCount).map(descriptor::getElementName)
        }

    @Test
    fun `every expert field is explicitly classified`() {
        val classified = preserved + disarmed + transformed

        assertEquals(
            "ExpertSettings gained or lost a field — classify it in SafeModeExpertInvariantTest " +
                "and decide in disarmedBySafeMode() whether safe mode disarms it",
            fieldNames.toSet(),
            classified,
        )
        assertEquals(
            "a field must not appear in two classification sets",
            fieldNames.size,
            preserved.size + disarmed.size + transformed.size,
        )
    }

    @Test
    fun `disarming keeps preserved fields and resets disarmed ones`() {
        val defaults = json.encodeToJsonElement(ExpertSettings()) as JsonObject
        val loud = json.encodeToJsonElement(fullyNonDefault()) as JsonObject
        val result = json.encodeToJsonElement(fullyNonDefault().disarmedBySafeMode()) as JsonObject

        preserved.forEach { field ->
            assertEquals("safe mode must not touch $field", loud[field], result[field])
        }
        disarmed.forEach { field ->
            assertEquals("safe mode must reset $field", defaults[field], result[field])
        }
    }

    @Test
    fun `disarming keeps only the block lane`() {
        val result = fullyNonDefault().disarmedBySafeMode()

        assertEquals(mapOf("com.blocked" to AppTunnelLane.BLOCK), result.appAssignments)
        assertTrue("quarantine belongs to the firewall lane", result.pendingQuarantinePackages.isNotEmpty())
    }

    @Test
    fun `the diagnostics retention policy survives — it used to be silently dropped`() {
        val policy = RetentionPolicy(preset = RetentionPreset.CUSTOM, customDays = 7)

        val result = ExpertSettings(diagnosticsRetentionPolicy = policy).disarmedBySafeMode()

        assertEquals(policy, result.diagnosticsRetentionPolicy)
    }

    /**
     * Every field moved away from its default, so a field that is wrongly reset shows up as a
     * mismatch instead of coincidentally matching the default.
     */
    private fun fullyNonDefault(): ExpertSettings =
        ExpertSettings(
            unlockedAt = 111L,
            warningAcknowledgedAt = 222L,
            blockScreenshots = true,
            firewallEnabled = true,
            newAppQuarantineEnabled = true,
            systemDnsProtectionEnabled = true,
            networkActivityLogging = false,
            diagnosticsRetention = DiagnosticsRetention.DAYS_7,
            diagnosticsRetentionPolicy = RetentionPolicy(preset = RetentionPreset.CUSTOM, customDays = 3),
            rawLiveDiagnostics = true,
            allowInsecureTls = true,
            sniff = false,
            strictRoute = false,
            bypassLan = true,
            allowPrivateOutboundHosts = true,
            perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
            appAssignments =
            mapOf(
                "com.vpn" to AppTunnelLane.VPN,
                "com.tor" to AppTunnelLane.TOR,
                "com.excluded" to AppTunnelLane.EXCLUDE,
                "com.blocked" to AppTunnelLane.BLOCK,
            ),
            quarantineKnownApplications =
            listOf(
                KnownApplicationIdentity(
                    packageName = "com.blocked",
                    signingCertificateSha256 = "ab".repeat(32),
                    firstSeenAtMs = 333L,
                ),
            ),
            pendingQuarantinePackages = listOf("com.blocked"),
            pendingQuarantineAppDetails =
            listOf(
                PendingQuarantineAppDetails(
                    packageName = "com.blocked",
                    label = "Blocked",
                    firstInstallTime = 100L,
                    detectedAt = 200L,
                    installerPackageName = "com.store",
                ),
            ),
            quarantinePolicyRevision = 7L,
            blockedPackagesEnabled = true,
            blockAppsAlways = true,
            siteRoutingAction = RoutingRuleAction.DIRECT,
            localSurfaces =
            LocalSurfaceSettings(
                lanAuth = LocalAuthSettings(enabled = true, username = "u", password = "p"),
            ),
        )
}
