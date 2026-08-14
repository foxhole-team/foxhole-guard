package com.foxhole.guard.core.settings

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ConnectionSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Tor scope is a stored preference, not a runtime capability. Normalization used to coerce
 * «selected apps» back to whole-device whenever the Tor lane happened to be empty, which made the
 * pair unreachable in a split — every write was silently reverted — and turned removing the last
 * app into a switch that put the entire device through Tor.
 */
class PrivacyRouteScopeNormalizationTest {

    private val base =
        Settings(
            // Safe mode legitimately strips every expert setting, so it is off here: the subject is
            // the scope's own normalization, not the safe-mode reset.
            connection = ConnectionSettings(safeModeEnabled = false),
            privacyRoute =
            PrivacyRouteSettings(
                permitted = true,
                mode = PrivacyRouteMode.TOR_OVER_VPN,
                scope = PrivacyRouteScope.SELECTED_APPS,
            ),
        )

    @Test
    fun `selected apps survives an empty tor lane`() {
        val result = base.normalized()

        assertEquals(PrivacyRouteScope.SELECTED_APPS, result.privacyRoute.scope)
    }

    @Test
    fun `removing the last tor app keeps the scope instead of routing the whole device`() {
        val withApp =
            base.copy(
                expert = ExpertSettings(appAssignments = mapOf("com.app.one" to AppTunnelLane.TOR)),
            )

        val emptied = withApp.copy(expert = withApp.expert.copy(appAssignments = emptyMap())).normalized()

        assertEquals(PrivacyRouteScope.SELECTED_APPS, emptied.privacyRoute.scope)
    }
}
