package com.foxhole.guard.router.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class GuardRouterColors(val foreground: Color, val muted: Color)
internal object LocalGuardRouterColors {
    val current: GuardRouterColors @Composable get() = LocalCliColors.current.let { GuardRouterColors(it.fg, it.dim) }
}
internal val LocalGuardRouterSheetBodyGestures = staticCompositionLocalOf { Modifier }
internal val LocalGuardRouterNotices = staticCompositionLocalOf<GuardRouterNotices?> { null }
internal class GuardRouterNotices(private val scope: CoroutineScope, private val snackbar: SnackbarHostState) {
    fun show(message: String, error: Boolean = false) {
        scope.launch { snackbar.showSnackbar(message, withDismissAction = error) }
    }
}

@Composable
internal fun GuardRouterTypography(content: @Composable () -> Unit) {
    val type = LocalCliType.current
    MaterialTheme(typography = MaterialTheme.typography.copy(
        bodyLarge = type.body, bodyMedium = type.body, bodySmall = type.small,
        titleLarge = type.title.copy(fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = type.title.copy(fontSize = 16.sp, lineHeight = 22.sp),
        labelLarge = type.button, labelSmall = type.small,
    ), content = content)
}

@Composable
internal fun guardRouterDeleteColor(): Color = LocalCliColors.current.err

@Composable
internal fun GuardRouterSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val colors = LocalCliColors.current
    Switch(checked, onCheckedChange, enabled = enabled, colors = SwitchDefaults.colors(
        checkedThumbColor = colors.onAccent, checkedTrackColor = colors.accent,
        uncheckedThumbColor = colors.dim, uncheckedTrackColor = colors.panelAlt,
        uncheckedBorderColor = colors.border,
    ))
}

internal fun shareGuardRouterProfile(context: Context, text: String) {
    require(text.isNotBlank() && text.length <= 262144 && text.toByteArray(Charsets.UTF_8).size <= 262144)
    val content = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    val chooser = Intent.createChooser(content, context.getString(R.string.guard_router_vpn_profile_share))
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
