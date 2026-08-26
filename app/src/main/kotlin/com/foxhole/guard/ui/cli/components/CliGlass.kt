@file:Suppress("MatchingDeclarationName")

package com.foxhole.guard.ui.cli.components

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.PanelAppearance
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliTopContentGap
import com.foxhole.guard.ui.cli.LocalCliBottomChromeClearance
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.cli.cliTopBarLifted
import kotlin.math.roundToInt

internal class CliBackdropState(val layer: GraphicsLayer) {
    var sourceOrigin: Offset by mutableStateOf(Offset.Zero)
}

@Composable
internal fun rememberCliBackdrop(): CliBackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { CliBackdropState(layer) }
}

internal val LocalCliGlassBlurEnabled = staticCompositionLocalOf { false }

internal val LocalCliBackdrop = staticCompositionLocalOf<CliBackdropState?> { null }

internal fun Modifier.cliBackdropSource(backdrop: CliBackdropState): Modifier = this
    .onGloballyPositioned { backdrop.sourceOrigin = it.positionInRoot() }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawContent()
    }

internal fun cliGlassSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
internal fun cliGlassTint(): Color {
    val colors = LocalCliColors.current
    return cliGlassTintColor(LocalCliPanelAppearance.current, colors.panel)
}

internal fun cliGlassTintColor(appearance: PanelAppearance, panelColor: Color): Color =
    when (appearance) {
        PanelAppearance.LIGHT -> panelColor.copy(alpha = GLASS_TINT_LIGHT_ALPHA)
        PanelAppearance.DARK -> Color.Black.copy(alpha = GLASS_TINT_DARK_ALPHA)
        PanelAppearance.STANDARD,
        PanelAppearance.AUTO,
        -> panelColor.copy(alpha = GLASS_TINT_DARK_ALPHA)
    }

@Composable
internal fun cliGlassFallback(): Color {
    val colors = LocalCliColors.current
    return cliGlassFallbackColor(LocalCliPanelAppearance.current, colors.panel)
}

internal fun cliGlassFallbackColor(appearance: PanelAppearance, panelColor: Color): Color =
    if (appearance == PanelAppearance.DARK) {
        Color.Black
    } else {
        panelColor
    }

@Composable
internal fun CliGlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    backdrop: CliBackdropState? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val resolvedBackdrop = backdrop ?: LocalCliBackdrop.current
    val blurredChrome = LocalCliGlassBlurEnabled.current && cliGlassSupported()
    if (!blurredChrome || resolvedBackdrop == null) {
        Box(modifier = modifier.clip(shape).background(cliGlassFallback()), content = content)
        return
    }
    val tint = cliGlassTint()
    val density = LocalDensity.current
    val blurRadiusPx = with(density) { GLASS_BLUR_RADIUS.toPx() }
    val glassRenderEffect = remember(blurRadiusPx) { cliGlassRenderEffect(blurRadiusPx) }
    val glassLayer = rememberGraphicsLayer()
    var surfaceOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .onGloballyPositioned { surfaceOrigin = it.positionInRoot() }
            .clip(shape),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    val bleed = GLASS_EDGE_BLEED.toPx()
                    val sample = IntSize(
                        width = (size.width + bleed * 2f).roundToInt().coerceAtLeast(1),
                        height = (size.height + bleed * 2f).roundToInt().coerceAtLeast(1),
                    )
                    glassLayer.renderEffect = glassRenderEffect
                    glassLayer.record(size = sample) {
                        val backdropOffset = resolvedBackdrop.sourceOrigin - surfaceOrigin
                        translate(backdropOffset.x + bleed, backdropOffset.y + bleed) {
                            drawLayer(resolvedBackdrop.layer)
                        }
                    }
                    translate(-bleed, -bleed) {
                        drawLayer(glassLayer)
                    }
                    drawRect(color = tint)
                },
        )
        content()
    }
}

@android.annotation.TargetApi(Build.VERSION_CODES.S)
private fun cliGlassRenderEffect(radiusPx: Float): androidx.compose.ui.graphics.RenderEffect {
    val blur = android.graphics.RenderEffect.createBlurEffect(
        radiusPx,
        radiusPx,
        android.graphics.Shader.TileMode.CLAMP,
    )
    val saturate = android.graphics.RenderEffect.createColorFilterEffect(
        ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(GLASS_SATURATION) }),
        blur,
    )
    return saturate.asComposeRenderEffect()
}

@Composable
internal fun CliGlassHeaderScreen(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    content: @Composable (topInset: Dp) -> Unit,
) {
    val colors = LocalCliColors.current
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val topInset = with(LocalDensity.current) { headerHeightPx.toDp() }
    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            content(topInset)
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(colors.bg)
                .onSizeChanged { size -> headerHeightPx = size.height },
        ) {
            Box(
                modifier = Modifier
                    .cliTopBarLifted()
                    .statusBarsPadding()
                    .padding(
                        start = CliSpacing.md,
                        top = CliTopContentGap,
                        end = CliSpacing.md,
                    ),
            ) {
                header()
            }
        }
    }
}

@Composable
internal fun CliChromeTailSpacer(extraGap: Dp = CliSpacing.xs) {
    Spacer(modifier = Modifier.height(LocalCliBottomChromeClearance.current + extraGap))
}

private val GLASS_BLUR_RADIUS = 24.dp

private val GLASS_EDGE_BLEED = 24.dp
private const val GLASS_SATURATION = 1.35f
private const val GLASS_TINT_DARK_ALPHA = 0.68f
private const val GLASS_TINT_LIGHT_ALPHA = 0.60f
