package com.foxhole.guard.router.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import com.foxhole.guard.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun GuardRouterReviewTable(rows: List<Pair<String, String>>, icons: List<Int> = emptyList()) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                GuardRouterTableRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp).semantics(mergeDescendants = true) {}) {
                    Icon(painterResource(icons.getOrNull(index) ?: R.drawable.lin_info), null,
                        Modifier.size(20.dp), tint = LocalGuardRouterColors.current.muted)
                    Text(label, Modifier.weight(.42f), style = MaterialTheme.typography.bodySmall,
                        color = LocalGuardRouterColors.current.muted)
                    Text(value, Modifier.weight(.58f), style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
internal fun routerConnectColor(): Color = com.foxhole.guard.ui.cli.LocalCliColors.current.vpn

@Composable
internal fun GuardRouterNavigationFooter(backEnabled: Boolean, onBack: (() -> Unit)?, onClose: (() -> Unit)?) {
    if (onClose != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onBack != null) OutlinedButton(onClick = onBack, enabled = backEnabled,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Text(stringResource(R.string.guard_router_router_review_back))
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Text(stringResource(R.string.guard_router_vpn_close))
            }
        }
    } else if (onBack != null) OutlinedButton(onClick = onBack, enabled = backEnabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.guard_router_router_review_back))
    }
}

@Composable
internal fun GuardRouterIllustration(modifier: Modifier = Modifier) {
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { opened = true }
    val opacity by animateFloatAsState(if (opened) 1f else 0f, GuardRouterSheetMotion.fastEffectsSpec(), label = "router-image-opacity")
    val scale by animateFloatAsState(if (opened) 1f else .97f, GuardRouterSheetMotion.defaultSpatialSpec(), label = "router-image-scale")
    Icon(painterResource(R.drawable.router_review_illustration), null, modifier.graphicsLayer {
        alpha = opacity
        scaleX = scale
        scaleY = scale
    }, tint = LocalGuardRouterColors.current.foreground)
}
