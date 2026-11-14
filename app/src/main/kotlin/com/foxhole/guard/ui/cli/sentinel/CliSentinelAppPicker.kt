package com.foxhole.guard.ui.cli.sentinel

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliCheckGlyph
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.settings.CliInstalledAppRow
import com.foxhole.guard.ui.cli.settings.rememberCliAppIcon
import java.util.Locale

internal fun sentinelAppVisibleLabel(app: InstalledAppOption?, unknown: String): String {
    if (app == null) return unknown
    return app.label.trim().takeIf { it.isNotEmpty() && it != app.packageName } ?: unknown
}

@Composable
internal fun SentinelAppIdentity(app: InstalledAppOption?, packageName: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val locales = LocalConfiguration.current.locales.toLanguageTags()
    var resolvedName by remember(packageName, app?.lastUpdateTime, locales) { mutableStateOf<String?>(null) }
    LaunchedEffect(packageName, app?.label, app?.lastUpdateTime, locales) {
        if (!packageName.isNullOrBlank() && (app == null || app.label.isBlank() || app.label == packageName)) {
            resolvedName = withContext(Dispatchers.IO) {
                try {
                    val manager = context.packageManager
                    manager.getApplicationInfo(packageName, 0).loadLabel(manager).toString()
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
            }
        }
    }
    val label = sentinelCurrentAppLabel(app, packageName, resolvedName,
        stringResource(R.string.sentinel_static_preparing_app))
    val icon = packageName?.let { rememberCliAppIcon(it, app?.versionCode, app?.lastUpdateTime, 22.dp) }
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Image(icon, null, Modifier.size(22.dp)) else Spacer(Modifier.size(22.dp))
        Spacer(Modifier.width(CliSpacing.sm))
        Text(label, Modifier.weight(1f), style = CliType.small, color = LocalCliColors.current.fg,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun CliSentinelAppPicker(apps: List<InstalledAppOption>, selected: String?, onSelect: (String) -> Unit,
    onDismiss: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    val unknown = stringResource(R.string.sentinel_static_current_app_unknown)
    val visible = remember(apps, filter, showSystem) {
        apps.distinctBy { it.packageName }.filter { showSystem || !it.isSystemApp }
            .filter { filter.isBlank() || it.label.contains(filter, true) || it.packageName.contains(filter, true) }
            .sortedWith(compareBy({ it.isSystemApp }, { it.label.lowercase(Locale.ROOT) }, { it.packageName }))
    }
    CliBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.sentinel_static_select_app),
        icon = R.drawable.lin_apps, contentScrollEnabled = false, heightFraction = .72f) {
        CliInputRow(prompt = stringResource(R.string.sentinel_static_search_app), value = filter,
            onValueChange = { filter = it }, trailingChipLabel = stringResource(
                if (showSystem) R.string.cli_route_pick_hide_system else R.string.cli_route_pick_show_system),
            onTrailingChip = { showSystem = !showSystem })
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(CliSpacing.xs)) {
            items(visible, key = { it.packageName }) { app ->
                CliInstalledAppRow(app.copy(label = sentinelAppVisibleLabel(app, unknown)),
                    trailing = { CliCheckGlyph(checked = app.packageName == selected) },
                    onTap = { onSelect(app.packageName); onDismiss() })
            }
        }
    }
}

internal fun sentinelCurrentAppLabel(app: InstalledAppOption?, packageName: String?, resolvedName: String?, preparing: String): String {
    fun readable(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() && it != packageName }
    return readable(app?.label) ?: readable(resolvedName) ?: packageName?.takeIf { it.isNotBlank() } ?: preparing
}
