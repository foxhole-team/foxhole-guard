package com.foxhole.guard.ui

import com.foxhole.core.model.Settings
import com.foxhole.guard.core.settings.newInstallationSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FoxholeDbAutoUpdatePolicyTest {
    private val fresh = newInstallationSettings("https://example.com/ip")

    @Test fun freshInstallationEnablesEveryDatabasePolicy() {
        assertTrue(foxholeDbAutoUpdateEnabled(fresh))
    }

    @Test fun storedOptOutRemainsDisabled() {
        val json = Json { ignoreUnknownKeys = true }
        listOf("{}", """{"connection":{"componentAutoUpdateEnabled":false},"dns":{"autoUpdateFilters":false},"privacyRoute":{"bridgesAutoUpdate":false}}""").forEach { payload ->
            assertFalse(foxholeDbAutoUpdateEnabled(json.decodeFromString(Settings.serializer(), payload)))
        }
    }

    @Test fun disabledChecksOrMasterPolicyAreNotReportedAsEnabled() {
        assertFalse(foxholeDbAutoUpdateEnabled(fresh.copy(connection = fresh.connection.copy(componentUpdateCheckEnabled = false))))
        assertFalse(foxholeDbAutoUpdateEnabled(fresh.copy(connection = fresh.connection.copy(componentAutoUpdateEnabled = false))))
    }

    @Test fun partiallyDisabledDatabasePoliciesAreNotReportedAsEnabled() {
        listOf(
            fresh.copy(connection = fresh.connection.copy(geoIpAutoUpdate = false)),
            fresh.copy(connection = fresh.connection.copy(tlsFingerprintAutoUpdate = false)),
            fresh.copy(dns = fresh.dns.copy(autoUpdateFilters = false)),
            fresh.copy(privacyRoute = fresh.privacyRoute.copy(bridgesAutoUpdate = false)),
        ).forEach { assertFalse(foxholeDbAutoUpdateEnabled(it)) }
    }
}
