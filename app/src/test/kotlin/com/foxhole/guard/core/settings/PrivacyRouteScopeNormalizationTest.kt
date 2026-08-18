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

class PrivacyRouteScopeNormalizationTest {

    private val base =
        Settings(
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
