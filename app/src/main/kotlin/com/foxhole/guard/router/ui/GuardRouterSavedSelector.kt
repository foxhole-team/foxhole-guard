package com.foxhole.guard.router.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.foxhole.guard.ui.cli.components.CliButton
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.router.RouterSavedStatus
import com.foxhole.guard.router.RouterSavedSummary

@Composable
internal fun GuardRouterSavedSelector(routers: List<RouterSavedSummary>, selectedId: String?,
    enabled: Boolean, showIllustration: Boolean, onSelect: (String) -> Unit) {
    val selected = routers.singleOrNull { it.id == selectedId }
    var expanded by remember(selectedId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val menuWidth = maxWidth
            CliButton(
                label = selected?.let { savedRouterName(it) } ?: stringResource(R.string.guard_router_router_review_choose_router),
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = enabled,
                icon = R.drawable.lin_arrow_down,
                filled = false,
            )
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false },
                modifier = Modifier.width(menuWidth).heightIn(max = 280.dp)) {
                routers.forEach { router ->
                    DropdownMenuItem(text = {
                        Column {
                            Text(savedRouterName(router), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(when (router.status) {
                                RouterSavedStatus.AVAILABLE -> R.string.guard_router_router_review_saved_available
                                RouterSavedStatus.UNAVAILABLE -> R.string.guard_router_router_review_saved_unavailable
                                RouterSavedStatus.UNKNOWN -> R.string.guard_router_router_review_saved_unknown
                            }), style = MaterialTheme.typography.labelSmall, color = LocalGuardRouterColors.current.muted)
                        }
                    }, leadingIcon = { Icon(painterResource(R.drawable.lin_router), null, Modifier.size(22.dp)) },
                        onClick = { expanded = false; onSelect(router.id) })
                }
            }
        }
        if (showIllustration) GuardRouterIllustration(Modifier.fillMaxWidth().height(128.dp).testTag("router-management-vector"))
    }
}

@Composable
private fun savedRouterName(router: RouterSavedSummary): String = router.label?.takeIf { it.isNotBlank() }
    ?: stringResource(R.string.guard_router_router_review_saved_number, router.number)
