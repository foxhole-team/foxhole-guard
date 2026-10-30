package com.foxhole.guard.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterSetupLicenseTest {
    @Test fun freshSetupWithoutExplicitAcceptanceRefuses() {
        assertThrows(RouterLicenseRequired::class.java) { requireRouterSetupLicense(false) }
    }

    @Test fun acceptedCurrentAgreementAdmitsFreshSetup() {
        requireRouterSetupLicense(true)
        val fields = routerPinLicenseFields(RouterLicenseRequirement(true, 1), true, 1)
        val accepted = fields["licenseAccepted"] as JsonPrimitive
        val version = fields["licenseVersion"] as JsonPrimitive
        assertFalse(accepted.isString)
        assertFalse(version.isString)
        assertEquals(true, accepted.booleanOrNull)
        assertEquals(1, version.intOrNull)
    }

    @Test fun firstPinCannotBypassAgreementByCallingTheWireBuilderDirectly() {
        assertThrows(RouterLicenseRequired::class.java) {
            routerPinLicenseFields(RouterLicenseRequirement(true, 1), false, 0)
        }
    }

    @Test fun aDifferentAgreementVersionIsNotSilentlyAccepted() {
        assertThrows(IllegalArgumentException::class.java) { requireRouterSetupLicense(true, 2) }
        assertThrows(IllegalArgumentException::class.java) {
            routerPinLicenseFields(RouterLicenseRequirement(true, 1), true, 2)
        }
    }

    @Test fun legacyPairedUnlockDoesNotCreateRetroactiveConsent() {
        val requirement = routerLicenseRequirement(Json.parseToJsonElement("{}").jsonObject)
        assertEquals(RouterLicenseRequirement(false, 0), requirement)
        val fields = routerPinLicenseFields(requirement)
        assertEquals(false, (fields["licenseAccepted"] as JsonPrimitive).booleanOrNull)
        assertEquals(0, (fields["licenseVersion"] as JsonPrimitive).intOrNull)
    }

    @Test fun freshUnlockCarriesItsExactRequiredAgreement() {
        val requirement = routerLicenseRequirement(Json.parseToJsonElement(
            """{"licenseRequired":true,"licenseVersion":1}""").jsonObject)
        assertTrue(requirement.required)
        assertEquals(1, requirement.version)
    }

    @Test fun quotedBooleanOrVersionCannotBecomeAnAcknowledgment() {
        for (raw in listOf("""{"licenseRequired":"true","licenseVersion":1}""",
                           """{"licenseRequired":true,"licenseVersion":"1"}""",
                           """{"licenseRequired":true,"licenseVersion":1.0}""")) {
            assertThrows(IllegalArgumentException::class.java) {
                routerLicenseRequirement(Json.parseToJsonElement(raw).jsonObject)
            }
        }
    }

    @Test fun missingOrUnknownRequiredVersionIsRefused() {
        for (raw in listOf("""{"licenseRequired":true}""", """{"licenseRequired":true,"licenseVersion":2}""")) {
            assertThrows(IllegalArgumentException::class.java) {
                routerLicenseRequirement(Json.parseToJsonElement(raw).jsonObject)
            }
        }
    }
}
