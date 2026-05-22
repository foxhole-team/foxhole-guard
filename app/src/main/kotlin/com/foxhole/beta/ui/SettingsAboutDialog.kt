package com.foxhole.beta.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.R

@Composable
internal fun AboutSettingsDialog(
    appVersion: String,
    onRepositoryClick: () -> Unit,
    onSupportBotClick: () -> Unit,
    onSingBoxClick: () -> Unit,
    onTorClick: () -> Unit,
    onAdGuardDnsClick: () -> Unit,
    onVersionClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .foxholeDialogChrome()
            .testTag("settings_about_dialog"),
        shape = FoxholeDialogShape,
        title = {
            FoxholeDialogTitle(
                title = stringResource(R.string.about_settings_title),
                icon = Icons.Outlined.Info,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AboutDialogSectionTitle(text = stringResource(R.string.about_core_versions_title))
                AboutDialogLinkRow(
                    title = stringResource(R.string.about_sing_box_title),
                    value = BuildConfig.LIBBOX_SOURCE_VERSION,
                    summary = stringResource(R.string.about_sing_box_summary),
                    icon = Icons.Outlined.AccountTree,
                    testTag = "settings_about_sing_box_repository_action",
                    onClick = onSingBoxClick,
                )
                AboutDialogLinkRow(
                    title = stringResource(R.string.about_tor_title),
                    value = BuildConfig.TOR_BUNDLE_VERSION,
                    summary = stringResource(R.string.about_tor_bundle_summary),
                    icon = Icons.Outlined.Shield,
                    testTag = "settings_about_tor_repository_action",
                    onClick = onTorClick,
                )
                AboutDialogLinkRow(
                    title = stringResource(R.string.about_adguard_dns_title),
                    value = stringResource(R.string.about_adguard_dns_value),
                    summary = stringResource(R.string.about_adguard_dns_summary),
                    icon = Icons.Outlined.Dns,
                    testTag = "settings_about_adguard_dns_repository_action",
                    onClick = onAdGuardDnsClick,
                )
                AboutDialogSectionTitle(text = stringResource(R.string.about_licenses_title))
                AboutDialogLicenseList()
                SettingsFooterVersionText(
                    text = stringResource(R.string.settings_footer_version, appVersion),
                    summary = stringResource(R.string.settings_home_version_summary_hidden),
                    onRepositoryClick = onRepositoryClick,
                    onSupportBotClick = onSupportBotClick,
                    onClick = onVersionClick,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            FoxholeDialogDismissButton(onClick = onDismiss)
        },
    )
}

@Composable
private fun AboutDialogSectionTitle(text: String) {
    Text(
        text = text,
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
        modifier = Modifier.fillMaxWidth(),
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
