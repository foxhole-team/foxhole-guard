package com.foxhole.guard.router.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import com.foxhole.guard.R

// Retain landing geometry without a snapshot write that would schedule another layout frame.
private class RouterHubBounds {
    var width = -1
    var maximumHeight = -1
    var height = 0
}

@Composable
internal fun GuardRouterConnectingContent(connecting: Boolean, multipleRouters: Boolean, modifier: Modifier = Modifier,
    bottomContent: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val bounds = remember(multipleRouters, density.fontScale, configuration.orientation,
        configuration.locales.toLanguageTags(), MaterialTheme.typography) { RouterHubBounds() }
    val opacity by animateFloatAsState(if (connecting) 0f else 1f,
        animationSpec = com.foxhole.guard.ui.cli.cliVerticalScalarSpec(), label = "router-hub-content-opacity")
    Box(modifier.fillMaxWidth().layout { measurable, constraints ->
        if (bounds.width != constraints.maxWidth || bounds.maximumHeight != constraints.maxHeight) {
            bounds.width = constraints.maxWidth
            bounds.maximumHeight = constraints.maxHeight
            bounds.height = 0
        }
        val child = measurable.measure(constraints)
        val height = constraints.constrainHeight(maxOf(bounds.height, child.height))
        bounds.height = height
        layout(child.width, height) { child.placeRelative(0, (height - child.height) / 2) }
    }) {
        Column(Modifier.fillMaxSize().graphicsLayer {
            alpha = opacity
            translationY = (1f - opacity) * with(density) { 8.dp.toPx() }
        }.then(if (connecting) Modifier.clearAndSetSemantics { } else Modifier),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            bottomContent()
        }
        AnimatedVisibility(connecting, modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(com.foxhole.guard.ui.cli.cliVerticalScalarSpec()), exit = fadeOut(com.foxhole.guard.ui.cli.cliVerticalScalarSpec())) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.foxhole.guard.ui.cli.components.CliMetricSpinner()
                Text(stringResource(R.string.router_loader_connecting), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
