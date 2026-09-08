package com.foxhole.guard.core.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

internal fun updateLauncherAppearance(context: Context, monochrome: Boolean) {
    val manager = context.packageManager
    val normal = ComponentName(context, "com.foxhole.guard.ui.cli.ColorLauncher")
    val mono = ComponentName(context, "com.foxhole.guard.ui.cli.MonochromeLauncher")
    val enabled = if (monochrome) mono else normal
    val disabled = if (monochrome) normal else mono
    if (manager.getComponentEnabledSetting(enabled) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
        manager.setComponentEnabledSetting(
            enabled,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }
    if (manager.getComponentEnabledSetting(disabled) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
        manager.setComponentEnabledSetting(
            disabled,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
