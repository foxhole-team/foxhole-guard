package com.foxhole.beta.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.R
import kotlinx.coroutines.launch

@Composable
internal fun AboutSettingsScreen(
    appVersion: String,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repositoryOpenFailed = stringResource(R.string.open_repository_failed)
    val supportChannelOpenFailed = stringResource(R.string.support_channel_open_failed)
    val showRepositoryOpenError: () -> Unit = {
        scope.launch {
            snackbarHostState.showBanner(
                repositoryOpenFailed,
                FoxholeBannerTone.ERROR,
            )
        }
    }
    val onRepositoryClick: () -> Unit = {
        if (!openFoxholeRepository(context)) {
            showRepositoryOpenError()
        }
    }
    val onSingBoxClick: () -> Unit = {
        if (!openSingBoxRepository(context)) {
            showRepositoryOpenError()
        }
    }
    val onTorClick: () -> Unit = {
        if (!openTorRepository(context)) {
            showRepositoryOpenError()
        }
    }
    val onAdGuardDnsClick: () -> Unit = {
        if (!openAdGuardDnsFilterRepository(context)) {
            showRepositoryOpenError()
        }
    }
    val onSupportBotClick: () -> Unit = {
        if (!openSupportChannel(context)) {
            scope.launch {
                snackbarHostState.showBanner(
                    supportChannelOpenFailed,
                    FoxholeBannerTone.ERROR,
                )
            }
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.about_settings_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        tag = "settings_about_screen",
    ) {
        item {
            SettingsControlGroup {
                AboutDialogSectionTitle(text = stringResource(R.string.about_core_versions_title))
                SettingsControlGroupDivider()
                AboutDialogLinkRow(
                    title = stringResource(R.string.about_sing_box_title),
                    value = BuildConfig.LIBBOX_SOURCE_VERSION,
                    summary = stringResource(R.string.about_sing_box_summary),
                    icon = Icons.Outlined.AccountTree,
                    testTag = "settings_about_sing_box_repository_action",
                    onClick = onSingBoxClick,
                )
                SettingsControlGroupDivider()
                AboutDialogLinkRow(
                    title = stringResource(R.string.about_tor_title),
                    value = BuildConfig.TOR_BUNDLE_VERSION,
                    summary = stringResource(R.string.about_tor_bundle_summary),
                    icon = Icons.Outlined.Shield,
                    testTag = "settings_about_tor_repository_action",
                    onClick = onTorClick,
                )
                SettingsControlGroupDivider()
                AboutDialogLinkRow(
                    title = stringResource(R.string.about_adguard_dns_title),
                    value = stringResource(R.string.about_adguard_dns_value),
                    summary = stringResource(R.string.about_adguard_dns_summary),
                    icon = Icons.Outlined.Dns,
                    testTag = "settings_about_adguard_dns_repository_action",
                    onClick = onAdGuardDnsClick,
                )
            }
        }
        item {
            SettingsControlGroup {
                AboutDialogSectionTitle(text = stringResource(R.string.about_licenses_title))
                SettingsControlGroupDivider()
                AboutDialogLicenseList()
            }
        }
        item {
            SettingsFooterVersionText(
                text = stringResource(R.string.settings_footer_version, appVersion),
                summary = stringResource(R.string.settings_home_version_summary_hidden),
                onRepositoryClick = onRepositoryClick,
                onSupportBotClick = onSupportBotClick,
            )
        }
    }
}

@Composable
private fun AboutDialogSectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun AboutDialogLinkRow(
    title: String,
    value: String,
    summary: String,
    icon: ImageVector,
    testTag: String,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AboutDialogLicenseList() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.about_license_foxhole),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.about_license_sing_box),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.about_license_tor),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.about_license_adguard_dns),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
