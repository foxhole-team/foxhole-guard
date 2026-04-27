package com.foxhole.beta.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import com.foxhole.beta.R
import com.foxhole.beta.ui.theme.LocalFoxholeUiPalette
import kotlinx.coroutines.delay

internal val ScreenHorizontalPadding = 16.dp
internal val ScreenVerticalPadding = 10.dp
internal val ScreenSectionSpacing = 10.dp
internal val CardInnerPadding = 12.dp
internal val CardContentSpacing = 8.dp
internal val BottomDockOverlayPadding = 100.dp
internal val HomeTopStatusInnerSurfaceMinHeight = 74.dp
internal val HomeTopStatusInnerHorizontalPadding = 10.dp
internal val HomeTopStatusInnerVerticalPadding = 10.dp
internal val FoxholeTopBarBannerPadding = 86.dp

private val FoxholeBannerShellInset = 8.dp

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
internal val FoxholeInfoAccent = Color(0xFF6288AE)
internal val FoxholeWarningAccent = Color(0xFFE0B84A)
private val FoxholeErrorAccent = Color(0xFFC63C3C)

internal fun Modifier.foxholeAnimateContentSize(): Modifier =
    animateContentSize(
        animationSpec =
            tween(
                durationMillis = FoxholeMotionTokens.EmphasisDurationMs,
                easing = FastOutSlowInEasing,
            ),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoxholeScaffold(
    title: String,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bannerTopPadding: Dp = ScreenVerticalPadding,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    onNavigateUp?.let {
                        IconButton(onClick = it) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.navigate_back),
                            )
                        }
                    }
                },
                actions = actions,
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
        snackbarHost = {},
        content = { padding ->
            Box(
                modifier = Modifier.fillMaxSize(),
            ) {
                content(padding)
                FoxholeBannerHost(
                    snackbarHostState = snackbarHostState,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = ScreenHorizontalPadding)
                            .padding(top = bannerTopPadding),
                )
            }
        },
    )
}

internal enum class FoxholeBannerTone {
    INFO,
    ERROR,
    SUCCESS,
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
private val FoxholeBannerErrorWaveformMs = longArrayOf(0L, 25L, 60L, 25L)

internal data class FoxholeBannerVisuals(
    override val message: String,
    val tone: FoxholeBannerTone,
    override val actionLabel: String? = null,
    val durationMillis: Long? = null,
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
): SnackbarResult =
    showSnackbar(
        visuals =
            FoxholeBannerVisuals(
                message = message,
                tone = tone,
                actionLabel = actionLabel,
                durationMillis = durationMillis,
            ),
    )

internal suspend fun SnackbarHostState.showBanner(event: FoxholeBannerEvent): SnackbarResult =
    showBanner(
        message = event.message,
        tone = event.tone,
        actionLabel = event.actionLabel,
        durationMillis = event.durationMillis,
    )

@Composable
private fun FoxholeBannerHost(
    snackbarHostState: SnackbarHostState,
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
                initialOffsetY = { fullHeight -> -fullHeight },
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
                targetOffsetY = { fullHeight -> -(fullHeight / 2) },
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
    val contentColor = Color.White
    val icon =
        when (tone) {
            FoxholeBannerTone.INFO -> Icons.Outlined.Info
            FoxholeBannerTone.ERROR -> Icons.Outlined.ErrorOutline
            FoxholeBannerTone.SUCCESS -> Icons.Outlined.CheckCircle
        }
    LaunchedEffect(data, visuals?.durationMillis) {
        visuals?.durationMillis?.let { durationMillis ->
            delay(durationMillis)
            data.dismiss()
        }
    }

    Surface(
        modifier =
            modifier
                .padding(horizontal = CardInnerPadding)
                .heightIn(min = HomeTopStatusInnerSurfaceMinHeight)
                .testTag("foxhole_banner_shell"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(FoxholeBannerShellInset)
                    .testTag("foxhole_banner"),
            shape = MaterialTheme.shapes.large,
            color = containerColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
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
                    IconButton(
                        onClick = data::performAction,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = label,
                            tint = contentColor,
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
        }
    }
}

@Composable
internal fun FoxholeLazyScaffold(
    title: String,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    tag: String = "settings_screen",
    content: LazyListScope.() -> Unit,
) {
    FoxholeScaffold(
        title = title,
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        actions = actions,
        bannerTopPadding = FoxholeTopBarBannerPadding,
    ) { padding ->
        LazyColumn(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(padding)
                    .testTag(tag),
            contentPadding =
                PaddingValues(
                    start = ScreenHorizontalPadding,
                    top = ScreenVerticalPadding,
                    end = ScreenHorizontalPadding,
                    bottom = BottomDockOverlayPadding,
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
    enabled: Boolean = true,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = stringResource(R.string.save),
        )
    }
}

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
    val resolvedBorderColor =
        if (borderColor == Color.Unspecified) {
            uiPalette.cardBorderColor
        } else {
            borderColor
        }
    val colors =
        CardDefaults.cardColors(
            containerColor = resolvedContainerColor,
        )
    val border =
        BorderStroke(
            width = 1.dp,
            color = resolvedBorderColor,
        )
    val elevation =
        CardDefaults.cardElevation(
            defaultElevation = 0.dp,
        )
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = colors,
            border = border,
            elevation = elevation,
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
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = colors,
        border = border,
        elevation = elevation,
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
internal fun FoxholeGlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    containerColor: Color,
    borderColor: Color,
    blurRadius: androidx.compose.ui.unit.Dp = 18.dp,
    backgroundAlpha: Float = 0.56f,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        Surface(
            shape = shape,
            color = containerColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, borderColor),
        ) {
            Box(modifier = Modifier.clip(shape)) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(shape)
                            .blur(blurRadius)
                            .background(containerColor.copy(alpha = backgroundAlpha)),
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(shape),
                    content = content,
                )
            }
        }
    }
}

@Composable
internal fun FoxholeDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    val uiPalette = LocalFoxholeUiPalette.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 236.dp, max = 392.dp),
        offset = offset,
        shape = MaterialTheme.shapes.medium,
        containerColor = uiPalette.menuContainerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, uiPalette.menuBorderColor),
    ) {
        Column(
            modifier = Modifier.clip(MaterialTheme.shapes.medium),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            content = content,
        )
    }
}

@Composable
internal fun FoxholeDropdownItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    highlightSelected: Boolean = true,
    showBorder: Boolean = true,
    accentColor: Color = FoxholePositiveAccent,
    selectedContainerColor: Color? = null,
    shape: Shape = RectangleShape,
    minHeight: Dp = 46.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val uiPalette = LocalFoxholeUiPalette.current
    val selectedChrome = selected && highlightSelected
    val containerColor = if (selectedChrome) selectedContainerColor ?: uiPalette.menuSelectedRowColor else Color.Transparent
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().clip(shape),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = null,
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = minHeight)
                        .padding(contentPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingContent?.invoke()
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = content,
                )
                trailingContent?.let {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = it,
                    )
                }
            }
            HorizontalDivider(
                color =
                    if (showBorder) {
                        uiPalette.menuDividerColor
                    } else {
                        Color.Transparent
                    },
            )
        }
    }
}

@Composable
internal fun foxholeDropdownItemShape(
    index: Int,
    lastIndex: Int,
    hasHeader: Boolean = false,
): Shape {
    val radius = 24.dp
    val roundTop = index == 0 && !hasHeader
    val roundBottom = index == lastIndex
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
    leadingIcon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    containerColor: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    leadingIconContainerColor: Color = Color.Unspecified,
    leadingIconTint: Color = MaterialTheme.colorScheme.primary,
    summaryMaxLines: Int = 1,
) {
    val uiPalette = LocalFoxholeUiPalette.current
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
            horizontalArrangement = Arrangement.spacedBy(16.dp),
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
                                .padding(7.dp)
                                .size(18.dp),
                        tint = leadingIconTint,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                summary?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = summaryMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailingContent?.let {
                Row(
                    modifier = Modifier.widthIn(min = 52.dp, max = 168.dp),
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
) {
    val uiPalette = LocalFoxholeUiPalette.current
    val pillShape = MaterialTheme.shapes.medium
    val pillColor = uiPalette.valuePillContainerColor
    val pillColors = CardDefaults.cardColors(containerColor = pillColor)
    val pillElevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    val pillBorder =
        if (uiPalette.valuePillBorderColor == Color.Transparent) {
            null
        } else {
            BorderStroke(1.dp, uiPalette.valuePillBorderColor)
        }
    val pillContent: @Composable () -> Unit = {
        Row(
            modifier =
                Modifier
                    .widthIn(max = 184.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelLarge,
                color = uiPalette.valuePillContentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onClick != null) {
                Icon(
                    imageVector =
                        if (expanded) {
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
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
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
        modifier = modifier,
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
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors =
            SwitchDefaults.colors(
                checkedThumbColor = colorScheme.onPrimary,
                checkedTrackColor = colorScheme.primary,
                checkedBorderColor = colorScheme.primary,
                uncheckedThumbColor = colorScheme.onSurfaceVariant,
                uncheckedTrackColor = colorScheme.surfaceVariant.copy(alpha = 0.92f),
                uncheckedBorderColor = colorScheme.outline.copy(alpha = 0.95f),
                disabledCheckedThumbColor = colorScheme.onSurface.copy(alpha = 0.46f),
                disabledCheckedTrackColor = colorScheme.surfaceVariant.copy(alpha = 0.48f),
                disabledCheckedBorderColor = colorScheme.outline.copy(alpha = 0.56f),
                disabledUncheckedThumbColor = colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                disabledUncheckedTrackColor = colorScheme.surfaceVariant.copy(alpha = 0.54f),
                disabledUncheckedBorderColor = colorScheme.outline.copy(alpha = 0.62f),
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
    FoxholePreferenceCard(
        title = title,
        summary = summary,
        onClick = onClick,
        modifier = modifier,
        trailingContent = {
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = FoxholePositiveAccent,
                )
            }
        },
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
) {
    val alpha =
        rememberInfiniteTransition(label = "foxhole_skeleton").animateFloat(
            initialValue = 0.34f,
            targetValue = 0.74f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 900),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "foxhole_skeleton_alpha",
        ).value
    Box(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)),
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
