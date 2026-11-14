package com.foxhole.guard.router.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import com.foxhole.guard.ui.cli.components.CliButton
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.painterResource
import com.foxhole.guard.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun GuardRouterReviewTable(rows: List<Pair<String, String>>, icons: List<Int> = emptyList(), countryCodes: Map<Int, String> = emptyMap(), filled: Boolean = true) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (filled) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent,
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
                    val flag = countryCodes[index]
                    if (flag != null) Row(Modifier.weight(.58f), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                        com.foxhole.guard.ui.cli.components.CliFlagIcon(countryCode = flag, style = MaterialTheme.typography.bodyMedium)
                        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
                    } else Text(value, Modifier.weight(.58f), style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.End)
                }
            }
        }
    }
}

internal val GuardRouterActionHeight = 64.dp

@Composable
internal fun routerConnectColor(): Color = com.foxhole.guard.ui.cli.LocalCliColors.current.vpn

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun GuardRouterNavigationFooter(backEnabled: Boolean, onBack: (() -> Unit)?, onClose: (() -> Unit)?) {
    if (onClose != null) CliButton(
        label = stringResource(R.string.guard_router_vpn_close),
        onClick = onClose,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        enabled = true,
        filled = false,
    )
}

@Composable
internal fun GuardRouterIllustration(modifier: Modifier = Modifier, searching: Boolean = false, connected: Boolean = false) {
    val searchActive by rememberUpdatedState(searching)
    val signal = remember { Animatable(if (searching) 0f else 1f) }
    // Keep the current rise alive when discovery completes.
    LaunchedEffect(Unit) {
        while (currentCoroutineContext().isActive) {
            snapshotFlow { searchActive }.first { it }
            do {
                signal.snapTo(0f)
                signal.animateTo(1f, androidx.compose.animation.core.tween(2600,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing))
                if (searchActive) delay(180)
            } while (searchActive && currentCoroutineContext().isActive)
        }
    }
    val ink = LocalGuardRouterColors.current.muted
    val signalInk = ink
    val arcs = remember { listOf(
                    androidx.compose.ui.graphics.Path().apply { moveTo(126f,69f); quadraticTo(140f,55f,154f,69f) },
                    androidx.compose.ui.graphics.Path().apply { moveTo(115f,57f); quadraticTo(140f,34f,165f,57f) },
                    androidx.compose.ui.graphics.Path().apply { moveTo(104f,45f); quadraticTo(140f,13f,176f,45f) },
    ) }

    androidx.compose.foundation.layout.Box(modifier.graphicsLayer { scaleX = 1.16f; scaleY = 1.16f }) {
        Icon(painterResource(R.drawable.router_review_body), null, Modifier.fillMaxSize().offset(y = 1.dp).blur(2.dp), tint = ink.copy(alpha = .12f))
        Icon(painterResource(R.drawable.router_review_body), null, Modifier.fillMaxSize(), tint = ink)
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val factor = minOf(size.width / 280f, size.height / 168f)
            withTransform({
                translate((size.width - 280f * factor) / 2f, (size.height - 168f * factor) / 2f)
                scale(factor, factor, pivot = androidx.compose.ui.geometry.Offset.Zero)
            }) {
                drawCircle(signalInk.copy(alpha = .08f), radius = 6f, center = androidx.compose.ui.geometry.Offset(140f, 78f))
                drawCircle(signalInk, radius = 3f, center = androidx.compose.ui.geometry.Offset(140f, 78f))
                arcs.forEachIndexed { index, path ->
                    val alpha = androidx.compose.animation.core.FastOutSlowInEasing.transform(routerSignalOpacity(signal.value, index))
                    drawPath(path, signalInk.copy(alpha = alpha * .05f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(8f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    drawPath(path, signalInk.copy(alpha = alpha * .10f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    drawPath(path, signalInk.copy(alpha = alpha * .92f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    val sweep = 90f + 170f * routerSignalOpacity(signal.value, index)
                    val shimmer = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(signalInk.copy(alpha = 0f), signalInk.copy(alpha = alpha * .6f), signalInk.copy(alpha = 0f)),
                        start = androidx.compose.ui.geometry.Offset(sweep - 36f, 0f),
                        end = androidx.compose.ui.geometry.Offset(sweep + 36f, 0f))
                    drawPath(path, shimmer,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
        }
    }
}

internal fun routerSignalOpacity(progress: Float, index: Int): Float =
    ((progress - .10f - index * .20f) / .22f).coerceIn(0f, 1f)

@Composable
internal fun RouterPrimaryActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, active: Boolean = false, icon: Int? = null) {
    androidx.compose.material3.Button(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 60.dp), colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = if (com.foxhole.guard.ui.cli.LocalCliMonochrome.current) com.foxhole.guard.ui.cli.LocalCliColors.current.panel
                else androidx.compose.ui.graphics.Color(if (active) 0xFFB3261E else 0xFF1E6C39),
            contentColor = if (com.foxhole.guard.ui.cli.LocalCliMonochrome.current) com.foxhole.guard.ui.cli.LocalCliColors.current.fg else androidx.compose.ui.graphics.Color.White)) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(22.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
