package com.foxhole.guard.router.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.router.RouterSavedStatus
import com.foxhole.guard.router.RouterSavedSummary

@Composable
internal fun GuardRouterSavedTable(routers: List<RouterSavedSummary>, selectedId: String?, enabled: Boolean,
    onSelect: (String) -> Unit, onRename: (String, String) -> Unit, onSettings: (String) -> Unit,
    deleteEnabled: Boolean, onDelete: (String) -> Unit) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RectangleShape
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.router_table_router), Modifier.weight(.42f), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.router_table_version), Modifier.weight(.28f), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.router_table_actions), Modifier.weight(.30f), style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        routers.forEach { router ->
            key(router.id) {
                val label = router.label ?: stringResource(R.string.router_table_default_name, router.number)
                val local = router.status == RouterSavedStatus.AVAILABLE && router.id == selectedId
                Surface(shape = shape, border = BorderStroke(if (local) 2.dp else 1.dp,
                    if (local) routerConnectColor() else MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        if (editingId == router.id) {
                            val focus = remember { FocusRequester() }
                            val valid = name.trim().isNotEmpty() && name.length <= 64 && name.none(Char::isISOControl)
                            fun save() {
                                if (enabled && valid) { onRename(router.id, name.trim()); editingId = null; keyboard?.hide() }
                            }
                            LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
                            OutlinedTextField(name, { if (it.length <= 64) name = it },
                                Modifier.fillMaxWidth().focusRequester(focus),
                                singleLine = true, label = { Text(stringResource(R.string.router_table_name)) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { save() }))
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { editingId = null; keyboard?.hide() }, Modifier.weight(1f), shape = shape) {
                                    Text(stringResource(R.string.router_table_cancel))
                                }
                                OutlinedButton(onClick = { save() }, Modifier.weight(1f), enabled = enabled && valid, shape = shape) {
                                    Text(stringResource(R.string.router_table_save))
                                }
                            }
                        } else Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .then(if (enabled) Modifier.clickable { onSelect(router.id) } else Modifier), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(.42f), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall)
                                IconButton(onClick = { name = label; editingId = router.id }, enabled = enabled,
                                    modifier = Modifier.size(40.dp)) {
                                    Icon(painterResource(R.drawable.lin_edit), stringResource(R.string.router_table_rename), Modifier.size(20.dp))
                                }
                            }
                            Text(router.packageVersion ?: "—", Modifier.weight(.28f), style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(Modifier.weight(.30f), horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { onSettings(router.id) }, enabled = enabled && local,
                                    modifier = Modifier.size(40.dp)) {
                                    Icon(painterResource(R.drawable.lin_settings), stringResource(R.string.guard_router_router_review_settings), Modifier.size(22.dp))
                                }
                                IconButton(onClick = { onDelete(router.id) }, enabled = deleteEnabled,
                                    modifier = Modifier.size(40.dp)) {
                                    Icon(painterResource(R.drawable.lin_trash), stringResource(R.string.guard_router_router_review_unpair), Modifier.size(22.dp), tint = guardRouterDeleteColor())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
