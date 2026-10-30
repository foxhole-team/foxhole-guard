package com.foxhole.guard.router.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.foxhole.guard.R

@Composable
internal fun GuardRouterSheetContent(title: Int, icon: Int, modifier: Modifier = Modifier, busy: Boolean = false, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp),
                tint = if (icon == R.drawable.lin_trash) guardRouterDeleteColor() else LocalContentColor.current)
            Text(stringResource(title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (busy) {
                    val working = stringResource(R.string.guard_router_vpn_working)
                    CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = working }, strokeWidth = 2.dp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                GuardRouterAddonSettingsButton()
                actions()
            }
        }
        content()
    }
}

@Composable
internal fun GuardRouterSheetContent(
    title: Int,
    icon: Int,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp),
                tint = if (icon == R.drawable.lin_trash) guardRouterDeleteColor() else LocalContentColor.current)
            Text(stringResource(title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (busy) {
                val working = stringResource(R.string.guard_router_vpn_working)
                CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = working }, strokeWidth = 2.dp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                GuardRouterAddonSettingsButton()
                actions()
            }
        }
        val body = Modifier.weight(1f).fillMaxWidth().then(LocalGuardRouterSheetBodyGestures.current)
        Column(if (scrollable) body.verticalScroll(rememberScrollState()) else body,
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        if (footer != null) {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp).then(LocalGuardRouterSheetBodyGestures.current),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
        }
    }
}
