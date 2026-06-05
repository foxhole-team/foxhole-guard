package com.foxhole.beta.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsApplications
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Troubleshoot
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R

internal object FoxholeIcons {
    val Dashboard: ImageVector = Icons.Outlined.Dashboard
    val Settings: ImageVector = Icons.Outlined.Settings
    val Profile: ImageVector = Icons.Outlined.AccountTree

    val SmartStart: ImageVector = Icons.Outlined.RocketLaunch
    val AutoStart: ImageVector = Icons.Outlined.PowerSettingsNew
    val AutoMode: ImageVector = Icons.Outlined.AutoMode
    val Latency: ImageVector = Icons.Outlined.Speed
    val Traffic: ImageVector = Icons.Outlined.SwapVert
    val Dns: ImageVector = Icons.Outlined.Dns
    val Network: ImageVector = Icons.Outlined.Public
    val NetworkRules: ImageVector = Icons.Outlined.Router
    val Lan: ImageVector = Icons.Outlined.Lan
    val Wifi: ImageVector = Icons.Outlined.Wifi
    val Cellular: ImageVector = Icons.Outlined.SignalCellularAlt
    val CellTower: ImageVector = Icons.Outlined.CellTower
    val Apps: ImageVector = Icons.Outlined.Apps
    val Application: ImageVector = Icons.Outlined.SettingsApplications
    val RoutingSites: ImageVector = Icons.Outlined.Language
    val RoutingApps: ImageVector = Icons.Outlined.Apps
    val Statistics: ImageVector = Icons.Outlined.QueryStats
    val Expert: ImageVector = Icons.Outlined.Tune
    val Diagnostics: ImageVector = Icons.Outlined.Troubleshoot
    val PrivacyLocalData: ImageVector = Icons.Outlined.PrivacyTip
    val DiagnosticsReport: ImageVector = Icons.AutoMirrored.Outlined.Article
    val BugReport: ImageVector = Icons.Outlined.BugReport
    val About: ImageVector = Icons.Outlined.Info
    val Security: ImageVector = Icons.Outlined.Shield
    val SystemSecurity: ImageVector = Icons.Outlined.Security
    val Auth: ImageVector = Icons.Outlined.Key
    val Lock: ImageVector = Icons.Outlined.Lock
    val Power: ImageVector = Icons.Outlined.PowerSettingsNew
    val Refresh: ImageVector = Icons.Outlined.Refresh
    val IpStrategy: ImageVector = Icons.Outlined.AccountTree
    val Theme: ImageVector = Icons.Outlined.Palette
    val ScreenshotsBlocked: ImageVector = Icons.Outlined.VisibilityOff
    val ScreenshotsBlockedStrong: ImageVector = Icons.Outlined.NoPhotography
    val ProxySurface: ImageVector = Icons.Outlined.Hub
    val ProxyEndpoint: ImageVector = Icons.Outlined.Lan
    val QuickSettings: ImageVector = Icons.Outlined.Widgets

    object Drawables {
        val TorRoute: Int = R.drawable.ic_tor_route
        val Firewall: Int = R.drawable.ic_firewall_shield_key
        val NotificationVpn: Int = R.drawable.ic_notification_vpn
        val NotificationTor: Int = R.drawable.ic_notification_tor
        val NotificationFirewall: Int = R.drawable.ic_notification_firewall
        val NotificationAnomaly: Int = R.drawable.ic_notification_anomaly
        val NotificationProxy: Int = R.drawable.ic_notification_proxy
        val TileVpn: Int = R.drawable.ic_tile_vpn
    }
}

internal object FoxholeIconSizes {
    val Navigation: Dp = 24.dp
}
