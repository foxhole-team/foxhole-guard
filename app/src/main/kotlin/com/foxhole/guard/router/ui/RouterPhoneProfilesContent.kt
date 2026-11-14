package com.foxhole.guard.router.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import java.util.Locale

@Composable
internal fun RouterPhoneProfilesContent(picker: RouterPhonePickerState, enabled: Boolean, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .55f),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.router_transfer_body), style = MaterialTheme.typography.bodyMedium)
        when {
            picker.loading -> Box(Modifier.fillMaxWidth().heightIn(min = 80.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            picker.catalog == null || !picker.current -> {
                Text(stringResource(R.string.router_transfer_failed), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onRetry, enabled = enabled && !picker.busy) {
                    Text(stringResource(R.string.router_transfer_retry))
                }
            }
            picker.catalog?.choices.isNullOrEmpty() ->
                Text(stringResource(R.string.router_transfer_empty), style = MaterialTheme.typography.bodyMedium)
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)
                .testTag("router-phone-transfer-list")) {
                items(picker.catalog?.choices.orEmpty(), key = { it.choiceId }) { choice ->
                    val checked = choice.choiceId in picker.selected
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .toggleable(checked, enabled = enabled && !picker.busy && (checked || picker.selected.size < 256), role = Role.Checkbox,
                            onValueChange = { picker.toggle(choice.choiceId) })
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                        .testTag("router-phone-transfer-${choice.choiceId}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked, onCheckedChange = null, enabled = enabled && !picker.busy && (checked || picker.selected.size < 256))
                        Column(Modifier.weight(1f)) {
                            Text(choice.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(choice.protocol.uppercase(Locale.ROOT) +
                                if (choice.nodeName != choice.displayName) " · ${choice.nodeName}" else "",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RouterPhoneProfilesFooter(enabled: Boolean, canSave: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.foxhole.guard.ui.cli.components.CliButton(label = stringResource(R.string.router_transfer_cancel),
            onClick = onCancel, enabled = enabled, filled = false,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp))
        com.foxhole.guard.ui.cli.components.CliButton(label = stringResource(R.string.router_transfer_save),
            onClick = onSave, enabled = enabled && canSave, filled = false, color = routerConnectColor(),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp))
    }
}
