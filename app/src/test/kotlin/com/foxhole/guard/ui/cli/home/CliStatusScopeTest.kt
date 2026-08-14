package com.foxhole.guard.ui.cli.home

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.Settings
import com.foxhole.core.model.tunnelKeptOutPackages
import com.foxhole.guard.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Область действия в блоке `status`: строка TOR-внутри-VPN и полоса per-app правил обязаны
 * различать туннель на всё устройство и per-app (proxy/split) режим.
 *
 * После переименования split→proxy слово PROXY означает именно per-app режим, поэтому одна общая
 * строка на оба случая называла проксёй туннель на всё устройство — прямая ложь о том, как ходит
 * трафик.
 */
class CliStatusScopeTest {

    private fun wholeTunnel() = Settings(expert = ExpertSettings(perAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL))

    /** Per-app режим = не FULL_TUNNEL И непустой набор закреплённых приложений. */
    private fun perApp() =
        Settings(
            expert = ExpertSettings(
                perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                appAssignments = mapOf("org.example.app" to AppTunnelLane.VPN),
            ),
        )

    @Test
    fun `compact home status names proxy legs while full-device legs stay generic`() {
        assertEquals(
            R.string.cli_st_split_vpn,
            CliCompactRouteStatus.VPN_PROXY.labelRes,
        )
        assertEquals(
            R.string.cli_st_split_tor,
            CliCompactRouteStatus.TOR_PROXY.labelRes,
        )
        assertEquals(
            R.string.cli_st_vpn_tor_proxy,
            CliCompactRouteStatus.VPN_TOR_PROXY.labelRes,
        )
        assertEquals(
            R.string.cli_st_tor_proxy_in_vpn,
            CliCompactRouteStatus.TOR_PROXY_IN_VPN.labelRes,
        )
        assertEquals(
            R.string.cli_st_vpn_proxy_tor_proxy,
            CliCompactRouteStatus.VPN_PROXY_TOR_PROXY.labelRes,
        )
        assertEquals(
            R.string.cli_st_tor_proxy_in_vpn_proxy,
            CliCompactRouteStatus.TOR_PROXY_IN_VPN_PROXY.labelRes,
        )
    }

    @Test
    fun `a live whole-device tunnel reports the device, never a pin list`() {
        assertEquals(
            CliVpnLaneScope.WHOLE_DEVICE,
            vpnLaneScope(settings = wholeTunnel(), vpnLive = true),
        )
    }

    @Test
    fun `whole-device tunnel with nothing running claims no rule at all`() {
        assertEquals(
            CliVpnLaneScope.INERT,
            vpnLaneScope(settings = wholeTunnel(), vpnLive = false),
        )
    }

    @Test
    fun `per-app mode still lists its pins`() {
        assertEquals(
            CliVpnLaneScope.PER_APP,
            vpnLaneScope(settings = perApp(), vpnLive = true),
        )
        // И в оффлайне: закрепления per-app режима остаются осмысленной конфигурацией.
        assertEquals(
            CliVpnLaneScope.PER_APP,
            vpnLaneScope(settings = perApp(), vpnLive = false),
        )
    }

    /**
     * Найдено владельцем на устройстве: в режиме «все, кроме выбранных» строка печатала
     * закреплённые приложения под «in VPN» — то есть называла туннелем ровно те приложения,
     * которые рантайм отдаёт билдеру туна как ИСКЛЮЧЁННЫЕ. Туннель здесь несёт всё остальное,
     * и сказать он должен именно это.
     */
    @Test
    fun `exclude mode reports the device and never lists its pins as tunnelled`() {
        val exclude = excludeMode()

        assertEquals(CliVpnLaneScope.WHOLE_DEVICE, vpnLaneScope(settings = exclude, vpnLive = true))
        assertEquals(CliVpnLaneScope.INERT, vpnLaneScope(settings = exclude, vpnLive = false))
    }

    /** И эти же приложения обязаны быть видны там, где они на самом деле — среди исключённых. */
    @Test
    fun `pins of the exclude mode are the apps kept out of the tunnel`() {
        assertEquals(
            listOf("org.example.app", "org.example.other"),
            excludeMode().expert.tunnelKeptOutPackages(),
        )
        // Во включающем режиме закрепление в лейне VPN — это членство, а не исключение.
        assertEquals(emptyList<String>(), perApp().expert.tunnelKeptOutPackages())
    }

    private fun excludeMode() =
        Settings(
            expert = ExpertSettings(
                perAppRoutingMode = PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                appAssignments = mapOf(
                    "org.example.app" to AppTunnelLane.VPN,
                    "org.example.other" to AppTunnelLane.EXCLUDE,
                ),
            ),
        )
}
