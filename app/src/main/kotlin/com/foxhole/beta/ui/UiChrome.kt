@file:Suppress("ImportOrdering", "TooManyFunctions")

package com.foxhole.beta.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ThemeMode
import com.foxhole.beta.ui.theme.FoxholeTheme
import com.foxhole.beta.ui.theme.LocalFoxholeDarkTheme
import com.foxhole.beta.ui.theme.LocalFoxholeThemeMode
import com.foxhole.beta.ui.theme.LocalFoxholeUiPalette
import com.foxhole.beta.ui.theme.foxholeAppBackgroundLayer
import eightbitlab.com.blurview.BlurTarget
import kotlin.math.max
import android.graphics.Color as AndroidColor

internal val ScreenHorizontalPadding = 16.dp
internal val ScreenVerticalPadding = 10.dp
internal val ScreenSectionSpacing = 10.dp
internal val CardInnerPadding = 12.dp
internal val CardContentSpacing = 8.dp
internal val BottomDockOverlayPadding = 100.dp
internal val FoxholeTopChromeHeight = 52.dp
internal val HomeTopStatusInnerSurfaceMinHeight = 42.dp
internal val HomeTopStatusInnerHorizontalPadding = 10.dp
internal val HomeTopStatusInnerVerticalPadding = 6.dp
internal val FoxholeTopBarBannerPadding = 86.dp

internal val FoxholeDialogShape = RoundedCornerShape(24.dp)

internal data class FoxholeBackdropBlurHost(
    val overlayHost: ViewGroup,
    val blurTarget: BlurTarget,
)

internal val LocalFoxholeBackdropBlurHost =
    staticCompositionLocalOf<FoxholeBackdropBlurHost?> {
        null
    }

internal typealias FoxholeTopChromeActions = @Composable RowScope.() -> Unit

internal class FoxholeTopChromeController {
    private var topChromeView: FoxholeTopChromeBlurView? = null
    private var latestState = FoxholeTopChromeState(visible = false)

    fun attach(view: FoxholeTopChromeBlurView) {
        topChromeView = view
        view.applyState(latestState)
    }

    fun detach(view: FoxholeTopChromeBlurView) {
        if (topChromeView === view) {
            topChromeView = null
        }
    }

    fun publish(state: FoxholeTopChromeState) {
        latestState = state
        topChromeView?.applyState(state)
    }
}

internal data class FoxholeTopChromeState(
    val title: String = "",
    val onNavigateUp: (() -> Unit)? = null,
    val actions: FoxholeTopChromeActions = {},
    val visible: Boolean = true,
)

internal val LocalFoxholeTopChromeController =
    staticCompositionLocalOf<FoxholeTopChromeController?> {
        null
    }

internal object FoxholeMotionTokens {
    const val FastDurationMs = 120
    const val StandardDurationMs = 180
    const val EmphasisDurationMs = 220
    const val NavigationEnterDurationMs = 280
    const val NavigationExitDurationMs = 240
    const val NavigationFadeDurationMs = 160
    const val NavigationIndicatorDurationMs = 320
    const val NavigationSlideFraction = 0.1f
    val NavigationIndicatorEasing = FastOutSlowInEasing
    val NavigationEnterEasing = LinearOutSlowInEasing
    val NavigationExitEasing = FastOutLinearInEasing
}

internal val FoxholePositiveAccent = Color(0xFF2F9E6A)
internal val FoxholeAnalysisAccent = Color(0xFF6288AE)
internal val FoxholeInfoAccent = Color(0xFFA8ADB3)
internal val FoxholeWarningAccent = Color(0xFFE0B84A)
private val FoxholeErrorAccent = Color(0xFFC63C3C)
private val FoxholeCardShadowElevation = 3.dp
private val FoxholeDropdownShadowElevation = 8.dp
private const val TOP_CHROME_SCRIM_DARK_ALPHA = 0.14f
private const val TOP_CHROME_SCRIM_LIGHT_ALPHA = 0.24f
private const val TOP_CHROME_FROST_DARK_ALPHA = 0.018f
private const val TOP_CHROME_FROST_LIGHT_ALPHA = 0.030f
private const val TOP_CHROME_MIN_SCROLL_ALPHA = 0.64f
private const val BOTTOM_DOCK_CONTAINER_DARK_ALPHA = 0.88f
private const val BOTTOM_DOCK_CONTAINER_LIGHT_ALPHA = 0.92f
private const val BOTTOM_DOCK_BORDER_ALPHA = 0.14f

internal fun foxholeTopChromeBackgroundColor(): Int = Color.Transparent.toArgb()

@Composable
internal fun foxholeBottomDockBackgroundColor(): Int {
    val dark = LocalFoxholeDarkTheme.current
    return when (LocalFoxholeThemeMode.current) {
        ThemeMode.SYSTEM ->
            if (dark) {
                MaterialTheme.colorScheme.surfaceDim
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }

        ThemeMode.DARK -> Color(0xFF050506)
        ThemeMode.LIGHT -> Color(0xFFECECEA)
    }.toArgb()
}

@Composable
internal fun foxholeSystemAwareAccentColor(
    fallback: Color = MaterialTheme.colorScheme.primary,
    darkFallback: Color = FoxholeInfoAccent,
): Color =
    when (LocalFoxholeThemeMode.current) {
        ThemeMode.SYSTEM -> MaterialTheme.colorScheme.primary
        ThemeMode.DARK -> darkFallback
        ThemeMode.LIGHT -> fallback
    }

@Composable
internal fun foxholeSystemProfileSelectionColor(): Color =
    foxholeSystemAwareAccentColor(
        darkFallback = FoxholeInfoAccent,
    )

@Composable
internal fun foxholeTransportBadgeColor(): Color =
    when (LocalFoxholeThemeMode.current) {
        ThemeMode.SYSTEM -> MaterialTheme.colorScheme.primary
        ThemeMode.DARK -> MaterialTheme.colorScheme.onSurfaceVariant
        ThemeMode.LIGHT -> FoxholeInfoAccent
    }

@Composable
internal fun foxholeHorizontalSafePadding(): Pair<Dp, Dp> {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    return remember(view, configuration, density) {
        val insets = ViewCompat.getRootWindowInsets(view)
        val cutoutInsets = insets?.getInsets(WindowInsetsCompat.Type.displayCutout())
        val systemInsets = insets?.getInsets(WindowInsetsCompat.Type.systemBars())
        with(density) {
            val start = max(cutoutInsets?.left ?: 0, systemInsets?.left ?: 0).toDp()
            val end = max(cutoutInsets?.right ?: 0, systemInsets?.right ?: 0).toDp()
            start to end
        }
    }
}

internal fun Modifier.foxholeAnimateContentSize(): Modifier =
    animateContentSize(
        animationSpec =
            tween(
                durationMillis = FoxholeMotionTokens.EmphasisDurationMs,
                easing = FastOutSlowInEasing,
            ),
    )

@Composable
internal fun Modifier.foxholeMenuShadow(
    shape: Shape,
    elevation: Dp = FoxholeCardShadowElevation,
): Modifier {
    val shadowColor =
        if (LocalFoxholeDarkTheme.current) {
            Color.Black.copy(alpha = 0.30f)
        } else {
            Color.Black.copy(alpha = 0.16f)
        }
    return shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = shadowColor,
        spotColor = shadowColor,
    )
}

@Composable
internal fun FoxholeScaffold(
    title: String,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bannerTopPadding: Dp = ScreenVerticalPadding,
    bannerBottomPadding: Dp = ScreenVerticalPadding,
    bannerPlacement: FoxholeBannerPlacement = FoxholeBannerPlacement.TOP,
    topChromeScrimProgress: () -> Float = { 0f },
    content: @Composable (PaddingValues) -> Unit,
) {
    val statusTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentTopPadding = statusTopPadding + FoxholeTopChromeHeight
    val resolvedTopBannerPadding =
        if (bannerTopPadding > contentTopPadding) {
            bannerTopPadding
        } else {
            contentTopPadding
        }
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {},
        content = { scaffoldPadding ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = scaffoldPadding.calculateBottomPadding()),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize(),
                ) {
                    content(PaddingValues(top = contentTopPadding))
                }
                FoxholeTopChrome(
                    title = title,
                    statusTopPadding = statusTopPadding,
                    contentTopPadding = contentTopPadding,
                    onNavigateUp = onNavigateUp,
                    actions = actions,
                    scrimProgress = topChromeScrimProgress,
                )
                val bannerModifier =
                    when (bannerPlacement) {
                        FoxholeBannerPlacement.TOP ->
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(horizontal = ScreenHorizontalPadding)
                                .padding(top = resolvedTopBannerPadding)

                        FoxholeBannerPlacement.BOTTOM ->
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = ScreenHorizontalPadding)
                                .padding(bottom = bannerBottomPadding + navigationBottomPadding)
                    }
                FoxholeBannerHost(
                    snackbarHostState = snackbarHostState,
                    placement = bannerPlacement,
                    modifier = bannerModifier,
                )
            }
        },
    )
}

@Composable
private fun BoxScope.FoxholeTopChrome(
    title: String,
    statusTopPadding: Dp,
    contentTopPadding: Dp,
    onNavigateUp: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    scrimProgress: () -> Float,
) {
    if (LocalFoxholeBackdropBlurHost.current != null) {
        LocalFoxholeTopChromeController.current?.let { controller ->
            SideEffect {
                controller.publish(
                    FoxholeTopChromeState(
                        title = title,
                        onNavigateUp = onNavigateUp,
                        actions = actions,
                    ),
                )
            }
        }
        return
    }
    FoxholeTopChromeContent(
        title = title,
        statusTopPadding = statusTopPadding,
        contentTopPadding = contentTopPadding,
        onNavigateUp = onNavigateUp,
        actions = actions,
        scrimProgress = scrimProgress,
    )
}

@Composable
internal fun FoxholeRootTopChromeOverlay(
    host: FoxholeBackdropBlurHost,
    controller: FoxholeTopChromeController,
) {
    val density = LocalDensity.current
    val themeMode = LocalFoxholeThemeMode.current
    val backgroundColor = foxholeTopChromeBackgroundColor()
    val statusTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topChromeHeight = statusTopPadding + FoxholeTopChromeHeight
    val topChromeHeightPx = with(density) { topChromeHeight.roundToPx() }
    val topChromeView =
        remember(host.overlayHost, host.blurTarget) {
            FoxholeTopChromeBlurView(host.overlayHost.context)
        }

    DisposableEffect(topChromeView, host.overlayHost) {
        host.overlayHost.addView(topChromeView)
        controller.attach(topChromeView)
        onDispose {
            controller.detach(topChromeView)
            host.overlayHost.removeView(topChromeView)
        }
    }

    SideEffect {
        topChromeView.apply {
            configureBackground(
                backgroundColor = backgroundColor,
            )
            statusTopPaddingState.value = statusTopPadding
            contentTopPaddingState.value = topChromeHeight
            themeModeState.value = themeMode
            layoutParams =
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    topChromeHeightPx,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                }
            elevation = 0f
        }
    }
}

internal class FoxholeTopChromeBlurView(
    context: Context,
) : FrameLayout(context) {
    val titleState = mutableStateOf("")
    val statusTopPaddingState = mutableStateOf(0.dp)
    val contentTopPaddingState = mutableStateOf(FoxholeTopChromeHeight)
    val onNavigateUpState = mutableStateOf<(() -> Unit)?>(null, referentialEqualityPolicy())
    val actionsState = mutableStateOf<FoxholeTopChromeActions>({}, referentialEqualityPolicy())
    val themeModeState = mutableStateOf(ThemeMode.SYSTEM)

    init {
        clipChildren = true
        clipToPadding = true
        addView(
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    FoxholeTheme(themeMode = themeModeState.value) {
                        val configuration = LocalConfiguration.current
                        Box(
                            Modifier
                                .fillMaxSize()
                                .foxholeAppBackgroundLayer(
                                    gradientHeight = configuration.screenHeightDp.dp,
                                ),
                        ) {
                            FoxholeTopChromeContent(
                                title = titleState.value,
                                statusTopPadding = statusTopPaddingState.value,
                                contentTopPadding = contentTopPaddingState.value,
                                onNavigateUp = onNavigateUpState.value,
                                actions = actionsState.value,
                                scrimProgress = { 1f },
                                drawScrimLayer = false,
                            )
                        }
                    }
                }
            },
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    fun applyState(state: FoxholeTopChromeState) {
        visibility = if (state.visible) View.VISIBLE else View.GONE
        titleState.value = state.title
        onNavigateUpState.value = state.onNavigateUp
        actionsState.value = state.actions
    }

    fun configureBackground(
        backgroundColor: Int,
    ) {
        setBackgroundColor(backgroundColor)
    }
}

@Composable
private fun BoxScope.FoxholeTopChromeContent(
    title: String,
    statusTopPadding: Dp,
    contentTopPadding: Dp,
    onNavigateUp: (() -> Unit)?,
    actions: FoxholeTopChromeActions,
    scrimProgress: () -> Float,
    drawScrimLayer: Boolean = true,
) {
    if (drawScrimLayer) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(contentTopPadding),
        ) {
            FoxholeTopScrimLayer(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(FoxholeTopChromeHeight),
                progress = scrimProgress,
            )
        }
    }
    Row(
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = statusTopPadding)
                .height(FoxholeTopChromeHeight)
                .padding(
                    start = if (onNavigateUp == null) 18.dp else 4.dp,
                    end = 8.dp,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onNavigateUp != null) {
            IconButton(
                onClick = onNavigateUp,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.navigate_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(
                        start = if (onNavigateUp == null) 0.dp else 8.dp,
                        end = 8.dp,
                    ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground,
                style =
                    TextStyle(
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        lineHeight = 24.sp,
                        letterSpacing = 0.sp,
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

@Composable
internal fun FoxholeTopScrimLayer(
    modifier: Modifier = Modifier,
    progress: () -> Float = { 1f },
) {
    val dark = LocalFoxholeDarkTheme.current
    val scheme = MaterialTheme.colorScheme
    val topScrimColor =
        if (dark) {
            scheme.background.copy(alpha = TOP_CHROME_SCRIM_DARK_ALPHA)
        } else {
            scheme.surface.copy(alpha = TOP_CHROME_SCRIM_LIGHT_ALPHA)
        }
    val frostTopColor =
        if (dark) {
            Color.White.copy(alpha = TOP_CHROME_FROST_DARK_ALPHA)
        } else {
            Color.White.copy(alpha = TOP_CHROME_FROST_LIGHT_ALPHA)
        }
    Box(
        modifier =
            modifier.drawWithCache {
                onDrawBehind {
                    val rawProgress = progress().coerceIn(0f, 1f)
                    val scrimProgress =
                        if (rawProgress > 0f) {
                            TOP_CHROME_MIN_SCROLL_ALPHA + ((1f - TOP_CHROME_MIN_SCROLL_ALPHA) * rawProgress)
                        } else {
                            0f
                        }
                    if (scrimProgress > 0f) {
                        drawRect(topScrimColor, alpha = scrimProgress)
                        drawRect(frostTopColor, alpha = scrimProgress)
                    }
                }
            },
    )
}

@Composable
internal fun rememberFoxholeTopChromeScrimProgress(listState: LazyListState): () -> Float {
    val density = LocalDensity.current
    val scrollRangePx = with(density) { 28.dp.toPx() }.coerceAtLeast(1f)
    return remember(listState, scrollRangePx) {
        {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / scrollRangePx).coerceIn(0f, 1f)
            }
        }
    }
}

internal enum class FoxholeBannerTone {
    INFO,
    ERROR,
    SUCCESS,
}

internal enum class FoxholeBannerPlacement {
    TOP,
    BOTTOM,
}

internal class FoxholeBannerHapticGate(
    private val nowElapsedMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private var lastHapticAt: Long? = null

    fun consume(tone: FoxholeBannerTone): Boolean {
        if (tone == FoxholeBannerTone.INFO) {
            return false
        }
        val now = nowElapsedMs()
        val previous = lastHapticAt
        if (previous != null && now - previous < FoxholeBannerHapticCooldownMs) {
            return false
        }
        lastHapticAt = now
        return true
    }
}

internal data class FoxholeBannerEvent(
    val message: String,
    val tone: FoxholeBannerTone,
    val actionLabel: String? = null,
    val action: FoxholeBannerAction? = null,
    val durationMillis: Long? = null,
    val expiresAtElapsedMs: Long? = null,
)

internal enum class FoxholeBannerAction {
    ACCEPT_PROTOCOL_RECOMMENDATION,
}

internal fun handleSnackbarHaptic(
    event: FoxholeBannerEvent,
    context: Context,
    gate: FoxholeBannerHapticGate,
) {
    if (!gate.consume(event.tone)) {
        return
    }
    when (event.tone) {
        FoxholeBannerTone.SUCCESS -> vibrateBannerSuccess(context)
        FoxholeBannerTone.ERROR -> vibrateBannerError(context)
        FoxholeBannerTone.INFO -> Unit
    }
}

private fun vibrateBannerSuccess(context: Context) {
    val vibrator = context.getSystemService<Vibrator>() ?: return
    vibrator.vibrate(
        VibrationEffect.createOneShot(
            FoxholeBannerHapticPulseMs,
            VibrationEffect.DEFAULT_AMPLITUDE,
        ),
    )
}

private fun vibrateBannerError(context: Context) {
    val vibrator = context.getSystemService<Vibrator>() ?: return
    vibrator.vibrate(
        VibrationEffect.createWaveform(FoxholeBannerErrorWaveformMs, -1),
    )
}

private const val FoxholeBannerHapticCooldownMs = 1_200L
private const val FoxholeBannerHapticPulseMs = 25L
internal const val FOXHOLE_BANNER_SHORT_DURATION_MS = 6_000L
internal const val FOXHOLE_BANNER_LONG_DURATION_MS = 9_000L
internal const val FOXHOLE_BANNER_MIN_VISIBLE_DURATION_MS = 3_000L
private val FoxholeBannerErrorWaveformMs = longArrayOf(0L, 25L, 60L, 25L)

internal fun defaultBannerDurationMillis(tone: FoxholeBannerTone): Long =
    when (tone) {
        FoxholeBannerTone.ERROR -> FOXHOLE_BANNER_LONG_DURATION_MS
        FoxholeBannerTone.INFO,
        FoxholeBannerTone.SUCCESS,
        -> FOXHOLE_BANNER_SHORT_DURATION_MS
    }

internal fun resolvedBannerExpiresAtElapsedMs(
    nowElapsedMs: Long,
    durationMillis: Long,
    requestedExpiresAtElapsedMs: Long?,
): Long {
    val defaultExpiresAt = nowElapsedMs + durationMillis
    val requestedExpiresAt = requestedExpiresAtElapsedMs ?: return defaultExpiresAt
    val minimumExpiresAt = nowElapsedMs + minOf(durationMillis, FOXHOLE_BANNER_MIN_VISIBLE_DURATION_MS)
    return requestedExpiresAt.coerceAtLeast(minimumExpiresAt)
}

internal data class FoxholeBannerVisuals(
    override val message: String,
    val tone: FoxholeBannerTone,
    override val actionLabel: String? = null,
    val durationMillis: Long? = null,
    val expiresAtElapsedMs: Long? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration =
        if (durationMillis != null) {
            SnackbarDuration.Indefinite
        } else {
            when (tone) {
                FoxholeBannerTone.ERROR -> SnackbarDuration.Long
                FoxholeBannerTone.INFO,
                FoxholeBannerTone.SUCCESS,
                -> SnackbarDuration.Short
            }
        },
) : SnackbarVisuals

internal suspend fun SnackbarHostState.showBanner(
    message: String,
    tone: FoxholeBannerTone,
    actionLabel: String? = null,
    durationMillis: Long? = null,
    expiresAtElapsedMs: Long? = null,
): SnackbarResult =
    showSnackbar(
        visuals =
            FoxholeBannerVisuals(
                message = message,
                tone = tone,
                actionLabel = actionLabel,
                durationMillis = durationMillis ?: defaultBannerDurationMillis(tone),
                expiresAtElapsedMs = expiresAtElapsedMs,
            ),
    )

internal suspend fun SnackbarHostState.showBanner(event: FoxholeBannerEvent): SnackbarResult =
    showBanner(
        message = event.message,
        tone = event.tone,
        actionLabel = event.actionLabel,
        durationMillis = event.durationMillis,
        expiresAtElapsedMs = event.expiresAtElapsedMs,
    )

@Composable
internal fun rememberDeadlineProgress(
    expiresAtElapsedMs: Long?,
    totalDurationMs: Long,
    onExpired: () -> Unit = {},
): Float {
    val progress = remember { Animatable(0f) }
    val latestOnExpired = rememberUpdatedState(onExpired)
    LaunchedEffect(expiresAtElapsedMs, totalDurationMs) {
        if (expiresAtElapsedMs == null) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        val remainingMs = (expiresAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val startProgress =
            (remainingMs.toFloat() / totalDurationMs.coerceAtLeast(1L).toFloat())
                .coerceIn(0f, 1f)
        progress.snapTo(startProgress)
        if (remainingMs > 0L) {
            progress.animateTo(
                targetValue = 0f,
                animationSpec =
                    tween(
                        durationMillis = remainingMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        easing = LinearEasing,
                    ),
            )
        }
        latestOnExpired.value()
    }
    return progress.value
}

@Composable
private fun FoxholeBannerHost(
    snackbarHostState: SnackbarHostState,
    placement: FoxholeBannerPlacement,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = snackbarHostState.currentSnackbarData != null,
        modifier = modifier.fillMaxWidth(),
        enter =
            slideInVertically(
                animationSpec =
                    tween(
                        durationMillis = FoxholeMotionTokens.EmphasisDurationMs,
                        easing = FoxholeMotionTokens.NavigationEnterEasing,
                    ),
                initialOffsetY = { fullHeight ->
                    when (placement) {
                        FoxholeBannerPlacement.TOP -> -fullHeight
                        FoxholeBannerPlacement.BOTTOM -> fullHeight
                    }
                },
            ) +
                fadeIn(
                    animationSpec =
                        tween(
                            durationMillis = FoxholeMotionTokens.StandardDurationMs,
                            easing = FoxholeMotionTokens.NavigationEnterEasing,
                        ),
                ),
        exit =
            slideOutVertically(
                animationSpec =
                    tween(
                        durationMillis = FoxholeMotionTokens.StandardDurationMs,
                        easing = FoxholeMotionTokens.NavigationExitEasing,
                    ),
                targetOffsetY = { fullHeight ->
                    when (placement) {
                        FoxholeBannerPlacement.TOP -> -(fullHeight / 2)
                        FoxholeBannerPlacement.BOTTOM -> fullHeight / 2
                    }
                },
            ) +
                fadeOut(
                    animationSpec =
                        tween(
                            durationMillis = FoxholeMotionTokens.StandardDurationMs,
                            easing = FoxholeMotionTokens.NavigationExitEasing,
                        ),
                ),
    ) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.fillMaxWidth(),
        ) { data ->
            FoxholeBanner(
                data = data,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FoxholeBanner(
    data: SnackbarData,
    modifier: Modifier = Modifier,
) {
    val visuals = data.visuals as? FoxholeBannerVisuals
    val tone = visuals?.tone ?: FoxholeBannerTone.INFO
    val containerColor =
        when (tone) {
            FoxholeBannerTone.INFO -> FoxholeInfoAccent.copy(alpha = 0.96f)
            FoxholeBannerTone.ERROR -> FoxholeErrorAccent.copy(alpha = 0.96f)
            FoxholeBannerTone.SUCCESS -> FoxholePositiveAccent.copy(alpha = 0.96f)
        }
    val contentColor = readableBannerContentColor(containerColor)
    val icon =
        when (tone) {
            FoxholeBannerTone.INFO -> Icons.Outlined.Info
            FoxholeBannerTone.ERROR -> Icons.Outlined.ErrorOutline
            FoxholeBannerTone.SUCCESS -> Icons.Outlined.CheckCircle
        }
    val expiresAtElapsedMs =
        remember(data, visuals?.expiresAtElapsedMs, visuals?.durationMillis) {
            val durationMillis = visuals?.durationMillis ?: defaultBannerDurationMillis(tone)
            resolvedBannerExpiresAtElapsedMs(
                nowElapsedMs = SystemClock.elapsedRealtime(),
                durationMillis = durationMillis,
                requestedExpiresAtElapsedMs = visuals?.expiresAtElapsedMs,
            )
        }
    val countdownDurationMillis = visuals?.durationMillis ?: defaultBannerDurationMillis(tone)
    val countdownProgress =
        rememberDeadlineProgress(
            expiresAtElapsedMs = expiresAtElapsedMs,
            totalDurationMs = countdownDurationMillis,
            onExpired = data::dismiss,
        )

    Surface(
        modifier =
            modifier
                .heightIn(min = HomeTopStatusInnerSurfaceMinHeight)
                .testTag("foxhole_banner_shell"),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("foxhole_banner"),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                )
                Text(
                    text = visuals?.message ?: data.visuals.message,
                    modifier = Modifier.weight(1f),
                    color = contentColor,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                visuals?.actionLabel?.let { label ->
                    TextButton(
                        onClick = data::performAction,
                        modifier = Modifier.heightIn(min = 32.dp),
                    ) {
                        Text(
                            text = label,
                            color = contentColor,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(
                    onClick = data::dismiss,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = contentColor,
                    )
                }
            }
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(countdownProgress.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(
                            color = contentColor.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(999.dp),
                        ),
            )
        }
    }
}

private fun readableBannerContentColor(containerColor: Color): Color =
    if (containerColor.luminance() >= FOXHOLE_BANNER_LIGHT_CONTAINER_LUMINANCE) {
        Color(0xFF111418)
    } else {
        Color.White
    }

private const val FOXHOLE_BANNER_LIGHT_CONTAINER_LUMINANCE = 0.36f

@Composable
internal fun FoxholeLazyScaffold(
    title: String,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    tag: String = "settings_screen",
    bannerPlacement: FoxholeBannerPlacement = FoxholeBannerPlacement.TOP,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    val topChromeScrimProgress = rememberFoxholeTopChromeScrimProgress(listState)
    FoxholeScaffold(
        title = title,
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        actions = actions,
        bannerTopPadding = FoxholeTopBarBannerPadding,
        bannerPlacement = bannerPlacement,
        topChromeScrimProgress = topChromeScrimProgress,
    ) { padding ->
        val (safeStartPadding, safeEndPadding) = foxholeHorizontalSafePadding()
        val navigationBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            state = listState,
            modifier =
                modifier
                    .fillMaxSize()
                    .testTag(tag),
            contentPadding =
                PaddingValues(
                    start = ScreenHorizontalPadding + safeStartPadding,
                    top = padding.calculateTopPadding() + ScreenVerticalPadding,
                    end = ScreenHorizontalPadding + safeEndPadding,
                    bottom = BottomDockOverlayPadding + navigationBottomPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(ScreenSectionSpacing),
        ) {
            content()
        }
    }
}

@Composable
internal fun FoxholeSaveAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
    icon: ImageVector = Icons.Outlined.Check,
) {
    if (label != null) {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = FoxholePositiveAccent,
            )
            Text(
                text = label,
                modifier = Modifier.padding(start = 8.dp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        return
    }

    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(R.string.save),
            tint = FoxholePositiveAccent,
        )
    }
}

@Composable
internal fun FoxholeDialogTitle(
    title: String,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    iconContainerColor: Color? = null,
) {
    val uiPalette = LocalFoxholeUiPalette.current
    if (icon == null) {
        Text(text = title)
        return
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = iconContainerColor ?: uiPalette.leadingIconContainerColor,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier =
                    Modifier
                        .padding(8.dp)
                        .size(18.dp),
                tint = iconTint ?: MaterialTheme.colorScheme.primary,
            )
        }
        Text(text = title)
    }
}

@Composable
internal fun FoxholeDialogConfirmButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(FoxholeDialogActionButtonHeight),
        shape = MaterialTheme.shapes.medium,
        contentPadding = FoxholeDialogActionButtonPadding,
    ) {
        Text(label ?: stringResource(R.string.save))
    }
}

@Composable
internal fun FoxholeDialogDismissButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(FoxholeDialogActionButtonHeight),
        shape = MaterialTheme.shapes.medium,
        contentPadding = FoxholeDialogActionButtonPadding,
    ) {
        Text(label ?: stringResource(R.string.close))
    }
}

@Composable
internal fun FoxholeDialogSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(FoxholeDialogActionButtonHeight),
        shape = MaterialTheme.shapes.medium,
        contentPadding = FoxholeDialogActionButtonPadding,
    ) {
        Text(label)
    }
}

private val FoxholeDialogActionButtonHeight = 34.dp
private val FoxholeDialogActionButtonPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)

@Composable
internal fun Modifier.foxholeDialogChrome(): Modifier =
    this.shadow(elevation = 18.dp, shape = FoxholeDialogShape, clip = false)

@Composable
internal fun FoxholeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val uiPalette = LocalFoxholeUiPalette.current
    val resolvedContainerColor =
        if (containerColor == Color.Unspecified) {
            uiPalette.cardContainerColor
        } else {
            containerColor
        }
    val colors =
        CardDefaults.cardColors(
            containerColor = resolvedContainerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    val resolvedBorder =
        if (borderColor == Color.Unspecified) {
            null
        } else {
            BorderStroke(1.dp, borderColor)
        }
    val elevation =
        CardDefaults.cardElevation(
            defaultElevation = FoxholeCardShadowElevation,
        )
    val cardShape = MaterialTheme.shapes.large
    val cardModifier =
        modifier
            .fillMaxWidth()
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            shape = cardShape,
            colors = colors,
            elevation = elevation,
            border = resolvedBorder,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(CardInnerPadding),
                verticalArrangement = Arrangement.spacedBy(CardContentSpacing),
                content = content,
            )
        }
        return
    }
    Card(
        modifier = cardModifier,
        shape = cardShape,
        colors = colors,
        elevation = elevation,
        border = resolvedBorder,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(CardContentSpacing),
            content = content,
        )
    }
}

@Composable
internal fun FoxholeBottomDockGlassLayer(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    borderColor: Color,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = LocalFoxholeDarkTheme.current
    val scheme = MaterialTheme.colorScheme
    val containerColor =
        if (dark) {
            scheme.background.copy(alpha = BOTTOM_DOCK_CONTAINER_DARK_ALPHA)
        } else {
            scheme.surface.copy(alpha = BOTTOM_DOCK_CONTAINER_LIGHT_ALPHA)
        }
    val frostColor =
        if (dark) {
            Color.White.copy(alpha = 0.020f)
        } else {
            Color.White.copy(alpha = 0.10f)
        }
    Surface(
        modifier =
            modifier.foxholeMenuShadow(
                shape = shape,
                elevation = FoxholeDropdownShadowElevation,
            ),
        shape = shape,
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, borderColor.copy(alpha = BOTTOM_DOCK_BORDER_ALPHA)),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .drawWithCache {
                        val container =
                            Brush.verticalGradient(
                                colorStops =
                                    arrayOf(
                                        0.00f to containerColor.copy(alpha = containerColor.alpha * 0.96f),
                                        0.54f to containerColor,
                                        1.00f to containerColor,
                                    ),
                            )
                        val frost =
                            Brush.verticalGradient(
                                colorStops =
                                    arrayOf(
                                        0.00f to frostColor,
                                        1.00f to Color.Transparent,
                                    ),
                            )
                        onDrawBehind {
                            drawRect(container)
                            drawRect(frost)
                        }
                    },
            content = content,
        )
    }
}

@Composable
internal fun FoxholeDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    popupGap: Dp = 0.dp,
    horizontalAlignment: FoxholeDropdownHorizontalAlignment = FoxholeDropdownHorizontalAlignment.AnchorEnd,
    screenEndPadding: Dp = ScreenHorizontalPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) {
        return
    }
    val density = LocalDensity.current
    val menuOffset =
        with(density) {
            IntOffset(
                x = offset.x.roundToPx(),
                y = (offset.y + popupGap).roundToPx(),
            )
        }
    val screenEndPaddingPx = with(density) { screenEndPadding.roundToPx() }
    val menuMaxHeight = (LocalConfiguration.current.screenHeightDp.dp - 72.dp).coerceAtLeast(160.dp)
    Popup(
        popupPositionProvider =
            remember(menuOffset, horizontalAlignment, screenEndPaddingPx) {
                FoxholeDropdownPositionProvider(
                    offset = menuOffset,
                    horizontalAlignment = horizontalAlignment,
                    screenEndPaddingPx = screenEndPaddingPx,
                )
            },
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier =
                modifier
                    .widthIn(max = 392.dp)
                    .heightIn(max = menuMaxHeight)
                    .foxholeMenuShadow(
                        shape = FoxholeDropdownShape,
                        elevation = FoxholeDropdownShadowElevation,
                    )
                    .clip(FoxholeDropdownShape),
            shape = FoxholeDropdownShape,
            color = MenuDefaults.containerColor,
            tonalElevation = MenuDefaults.TonalElevation,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                content = content,
            )
        }
    }
}

private val FoxholeDropdownShape = RoundedCornerShape(18.dp)
private val FoxholeDropdownInternalVerticalPadding = 8.dp

internal enum class FoxholeDropdownHorizontalAlignment {
    AnchorStart,
    AnchorEnd,
    ScreenEnd,
}

private class FoxholeDropdownPositionProvider(
    private val offset: IntOffset,
    private val horizontalAlignment: FoxholeDropdownHorizontalAlignment,
    private val screenEndPaddingPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val requestedX =
            when (horizontalAlignment) {
                FoxholeDropdownHorizontalAlignment.AnchorStart ->
                    when (layoutDirection) {
                        LayoutDirection.Ltr -> anchorBounds.left + offset.x
                        LayoutDirection.Rtl -> anchorBounds.right - popupContentSize.width - offset.x
                    }
                FoxholeDropdownHorizontalAlignment.AnchorEnd ->
                    when (layoutDirection) {
                        LayoutDirection.Ltr -> anchorBounds.right - popupContentSize.width + offset.x
                        LayoutDirection.Rtl -> anchorBounds.left - offset.x
                    }
                FoxholeDropdownHorizontalAlignment.ScreenEnd ->
                    when (layoutDirection) {
                        LayoutDirection.Ltr -> windowSize.width - popupContentSize.width - screenEndPaddingPx + offset.x
                        LayoutDirection.Rtl -> screenEndPaddingPx - offset.x
                    }
            }
        val maxX = max(0, windowSize.width - popupContentSize.width)
        val x = requestedX.coerceIn(0, maxX)
        val belowY = anchorBounds.bottom + offset.y
        val aboveY = anchorBounds.top - popupContentSize.height - offset.y
        val requestedY =
            if (belowY + popupContentSize.height <= windowSize.height || aboveY < 0) {
                belowY
            } else {
                aboveY
            }
        val maxY = max(0, windowSize.height - popupContentSize.height)
        return IntOffset(x = x, y = requestedY.coerceIn(0, maxY))
    }
}

@Composable
internal fun rememberFoxholeDropdownMenuWidth(
    labels: List<String>,
    textStyle: TextStyle,
    hasIcons: Boolean,
    minWidth: Dp = 0.dp,
    maxWidth: Dp = 260.dp,
): Dp {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxTextWidthPx =
        remember(labels, textStyle) {
            labels.maxOfOrNull { label ->
                textMeasurer.measure(text = label, style = textStyle).size.width
            } ?: 0
        }
    val screenLimit = (screenWidth - ScreenHorizontalPadding * 2).coerceAtLeast(0.dp)
    val boundedMaxWidth = maxWidth.coerceAtMost(screenLimit).coerceAtLeast(minWidth)
    return with(density) {
        val rowHorizontalChrome =
            if (hasIcons) {
                54.dp
            } else {
                24.dp
            }
        (maxTextWidthPx.toDp() + rowHorizontalChrome)
            .coerceAtLeast(minWidth)
            .coerceAtMost(boundedMaxWidth)
    }
}

@Composable
internal fun FoxholeDropdownItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    highlightSelected: Boolean = true,
    selectedContainerColor: Color? = null,
    extendSelectedToMenuTop: Boolean = false,
    extendSelectedToMenuBottom: Boolean = false,
    shape: Shape = RectangleShape,
    minHeight: Dp = 46.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val uiPalette = LocalFoxholeUiPalette.current
    val selectedChrome = selected && highlightSelected
    val containerColor =
        if (selectedChrome) {
            selectedContainerColor ?: uiPalette.menuSelectedRowColor
        } else {
            Color.Transparent
        }
    val selectedBackgroundModifier =
        if (selectedChrome && (extendSelectedToMenuTop || extendSelectedToMenuBottom)) {
            Modifier.drawBehind {
                val extensionPx = FoxholeDropdownInternalVerticalPadding.toPx()
                val top = if (extendSelectedToMenuTop) -extensionPx else 0f
                val bottom = if (extendSelectedToMenuBottom) extensionPx else 0f
                drawRect(
                    color = containerColor,
                    topLeft = Offset(0f, top),
                    size = Size(width = size.width, height = size.height - top + bottom),
                )
            }
        } else {
            Modifier.background(containerColor, shape)
        }
    DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        },
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .then(selectedBackgroundModifier),
        leadingIcon = leadingContent,
        trailingIcon =
            trailingContent?.let {
                {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = it,
                    )
                }
            },
        contentPadding = contentPadding,
    )
}

@Composable
internal fun foxholeDropdownItemShape(
    index: Int,
    lastIndex: Int,
    hasHeader: Boolean = false,
    hasFooter: Boolean = false,
): Shape {
    val radius = 24.dp
    val roundTop = index == 0 && !hasHeader
    val roundBottom = index == lastIndex && !hasFooter
    return when {
        roundTop && roundBottom -> MaterialTheme.shapes.medium
        roundTop ->
            RoundedCornerShape(
                topStart = radius,
                topEnd = radius,
            )
        roundBottom ->
            RoundedCornerShape(
                bottomStart = radius,
                bottomEnd = radius,
            )
        else -> RectangleShape
    }
}

@Composable
internal fun FoxholePreferenceCard(
    modifier: Modifier = Modifier,
    title: String,
    summary: String? = null,
    infoBody: String? = null,
    leadingIcon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    containerColor: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    leadingIconContainerColor: Color = Color.Unspecified,
    leadingIconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleTrailingContent: (@Composable RowScope.() -> Unit)? = null,
    titleMaxLines: Int = 1,
    summaryMaxLines: Int = 3,
) {
    val uiPalette = LocalFoxholeUiPalette.current
    val cardInfoBody = infoBody?.takeIf(String::isNotBlank)
    FoxholeCard(
        modifier = modifier,
        onClick = onClick,
        containerColor = containerColor,
        borderColor = borderColor,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (summary.isNullOrBlank()) 48.dp else 60.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.let { icon ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color =
                        if (leadingIconContainerColor == Color.Unspecified) {
                            uiPalette.leadingIconContainerColor
                        } else {
                            leadingIconContainerColor
                        },
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier =
                            Modifier
                                .padding(4.dp)
                                .size(24.dp),
                        tint = leadingIconTint,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = titleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                    titleTrailingContent?.invoke(this)
                    cardInfoBody?.let { body ->
                        SettingsHelpAction(
                            title = title,
                            body = body,
                        )
                    }
                }
                summary?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = summaryMaxLines,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
            trailingContent?.let {
                Row(
                    modifier = Modifier.widthIn(min = 52.dp, max = 232.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = it,
                )
            }
        }
    }
}

@Composable
internal fun FoxholeValuePill(
    value: String,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onClick: (() -> Unit)? = null,
    fillContent: Boolean = false,
    actionIcon: ImageVector? = null,
) {
    val uiPalette = LocalFoxholeUiPalette.current
    val pillShape = MaterialTheme.shapes.medium
    val pillColor = uiPalette.valuePillContainerColor
    val pillColors = CardDefaults.cardColors(containerColor = pillColor)
    val pillElevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    val pillBorder =
        if (uiPalette.valuePillBorderColor == Color.Transparent) {
            null
        } else {
            BorderStroke(1.dp, uiPalette.valuePillBorderColor)
        }
    val contentEndPadding = if (onClick != null) 9.dp else 11.dp
    val contentSpacing = if (onClick != null) 4.dp else 6.dp
    val pillContent: @Composable () -> Unit = {
        Box(
            modifier =
                Modifier
                    .then(
                        if (fillContent) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier
                        },
                    )
                    .padding(start = 11.dp, end = contentEndPadding, top = 7.dp, bottom = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier =
                    if (fillContent) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.widthIn(max = 184.dp)
                    },
                horizontalArrangement =
                    if (fillContent && onClick != null) {
                        Arrangement.spacedBy(contentSpacing)
                    } else {
                        Arrangement.spacedBy(contentSpacing, Alignment.CenterHorizontally)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = value,
                    modifier =
                        if (fillContent && onClick != null) {
                            Modifier.weight(1f)
                        } else {
                            Modifier.weight(1f, fill = false)
                        },
                    style = MaterialTheme.typography.labelLarge,
                    color = uiPalette.valuePillContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onClick != null) {
                    Icon(
                        imageVector =
                            actionIcon
                                ?: if (expanded) {
                                    Icons.Outlined.KeyboardArrowUp
                                } else {
                                    Icons.Outlined.KeyboardArrowDown
                                },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = uiPalette.valuePillContentColor,
                    )
                }
            }
        }
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.heightIn(min = 36.dp),
            shape = pillShape,
            colors = pillColors,
            elevation = pillElevation,
            border = pillBorder,
        ) {
            pillContent()
        }
        return
    }
    Card(
        modifier = modifier.heightIn(min = 34.dp),
        shape = pillShape,
        colors = pillColors,
        elevation = pillElevation,
        border = pillBorder,
    ) {
        pillContent()
    }
}

@Composable
internal fun FoxholeSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme
    val darkTheme = LocalFoxholeDarkTheme.current
    val checkedTrackColor =
        if (darkTheme) {
            Color.White.copy(alpha = 0.94f)
        } else {
            colorScheme.onSurface.copy(alpha = 0.82f)
        }
    val checkedThumbColor =
        if (darkTheme) {
            colorScheme.surfaceVariant
        } else {
            colorScheme.surface
        }
    val checkedIconColor =
        if (darkTheme) {
            Color.White.copy(alpha = 0.94f)
        } else {
            colorScheme.onSurface.copy(alpha = 0.76f)
        }
    val uncheckedTrackColor =
        if (darkTheme) {
            colorScheme.surface.copy(alpha = 0.18f)
        } else {
            colorScheme.surface.copy(alpha = 0.82f)
        }
    val uncheckedThumbColor =
        if (darkTheme) {
            colorScheme.surfaceVariant.copy(alpha = 0.86f)
        } else {
            colorScheme.surfaceContainerHighest
        }
    val uncheckedIconColor = colorScheme.onSurfaceVariant.copy(alpha = 0.86f)
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = {
            Icon(
                imageVector =
                    if (checked) {
                        Icons.Outlined.Check
                    } else {
                        Icons.Outlined.Close
                    },
                contentDescription = null,
                modifier = Modifier.size(15.dp),
            )
        },
        colors =
            SwitchDefaults.colors(
                checkedThumbColor = checkedThumbColor,
                checkedTrackColor = checkedTrackColor,
                checkedBorderColor = checkedTrackColor,
                checkedIconColor = checkedIconColor,
                uncheckedThumbColor = uncheckedThumbColor,
                uncheckedTrackColor = uncheckedTrackColor,
                uncheckedBorderColor = colorScheme.outline.copy(alpha = 0.88f),
                uncheckedIconColor = uncheckedIconColor,
                disabledCheckedThumbColor = checkedThumbColor.copy(alpha = 0.44f),
                disabledCheckedTrackColor = checkedTrackColor.copy(alpha = 0.34f),
                disabledCheckedBorderColor = checkedTrackColor.copy(alpha = 0.34f),
                disabledCheckedIconColor = checkedIconColor.copy(alpha = 0.44f),
                disabledUncheckedThumbColor = uncheckedThumbColor.copy(alpha = 0.48f),
                disabledUncheckedTrackColor = uncheckedTrackColor.copy(alpha = 0.48f),
                disabledUncheckedBorderColor = colorScheme.outline.copy(alpha = 0.50f),
                disabledUncheckedIconColor = uncheckedIconColor.copy(alpha = 0.50f),
            ),
    )
}

@Composable
internal fun FoxholeChoiceCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    val uiPalette = LocalFoxholeUiPalette.current
    FoxholePreferenceCard(
        title = title,
        summary = summary,
        onClick = onClick,
        modifier = modifier,
        containerColor = if (selected) uiPalette.menuSelectedRowColor else Color.Unspecified,
    )
}

@Composable
internal fun FoxholeSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        label = { Text(label) },
        singleLine = true,
        leadingIcon = {
            Icon(Icons.Outlined.Search, contentDescription = null)
        },
    )
}

@Composable
internal fun FoxholeSkeletonBlock(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val shimmerProgress =
        rememberInfiniteTransition(label = "foxhole_skeleton").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 1_150, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "foxhole_skeleton_shimmer",
        ).value
    val baseColor = color.copy(alpha = if (LocalFoxholeDarkTheme.current) 0.46f else 0.40f)
    val highlightColor = Color.White.copy(alpha = if (LocalFoxholeDarkTheme.current) 0.16f else 0.30f)
    Box(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.medium)
                .background(baseColor)
                .drawWithCache {
                    val travel = size.width * 2.4f
                    val startX = -size.width + travel * shimmerProgress
                    val shimmer =
                        Brush.linearGradient(
                            colors =
                                listOf(
                                    Color.Transparent,
                                    highlightColor,
                                    Color.Transparent,
                                ),
                            start = Offset(startX, 0f),
                            end = Offset(startX + size.width, size.height),
                        )
                    onDrawWithContent {
                        drawContent()
                        drawRect(brush = shimmer)
                    }
                },
    )
}

internal fun copyTextToClipboard(
    context: Context,
    label: String,
    value: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}
