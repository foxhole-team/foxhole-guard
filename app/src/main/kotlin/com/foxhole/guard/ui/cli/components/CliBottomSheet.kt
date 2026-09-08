package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.PanelAppearance
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CLI_ICON_OPTICAL_OFFSET
import com.foxhole.guard.ui.cli.CliRadius
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.cli.LocalCliPixelArtEnabled
import com.foxhole.guard.ui.cli.cliHeadingOpticalOffsetFor
import com.foxhole.guard.ui.cli.cliHeadingText
import com.foxhole.guard.ui.cli.cliTitleStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
internal fun CliBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    @DrawableRes icon: Int? = null,
    headerIconRole: CliSheetHeaderIconRole = CliSheetHeaderIconRole.DEFAULT,
    trailing: (@Composable () -> Unit)? = null,
    sheetGesturesEnabled: Boolean = true,
    contentScrollEnabled: Boolean = true,
    closeActionTag: String? = null,
    closeLabel: String? = null,
    autoDismissAfterMillis: Long? = null,
    footerLeading: (@Composable RowScope.() -> Unit)? = null,
    footerTrailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalCliColors.current
    val pixelArtEnabled = LocalCliPixelArtEnabled.current
    val appearance = LocalCliPanelAppearance.current
    val sheetShape = RoundedCornerShape(topStart = CliRadius.sheet, topEnd = CliRadius.sheet)
    val bodyMaxHeight = (LocalConfiguration.current.screenHeightDp * CLI_SHEET_MAX_BODY_FRACTION).dp
    val bodyScrollState = rememberScrollState()
    val resolvedHeaderIconRole = cliModalHeaderIconRoleFor(headerIconRole, icon)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetMotionSpec = MaterialSheetMotion.slowSpatialSpec()
    val dismissRequests = rememberCliBottomSheetDismissRequests(sheetState, onDismiss)
    val requestDismiss = dismissRequests.now
    val requestDismissAfter = dismissRequests.afterHidden
    LaunchedEffect(autoDismissAfterMillis) {
        autoDismissAfterMillis?.let { holdMillis ->
            delay(holdMillis)
            requestDismiss()
        }
    }
    require(footerLeading == null || footerTrailing == null)
    ModalBottomSheet(
        onDismissRequest = requestDismiss,
        modifier = modifier,
        shape = sheetShape,
        sheetState = sheetState,
        sheetGesturesEnabled = sheetGesturesEnabled,
        containerColor = cliModalSurfaceColor(appearance, colors.panel),
        contentColor = colors.fg,
        scrimColor = colors.bg.copy(alpha = 0.72f),
        contentWindowInsets = { BottomSheetDefaults.windowInsets },
        dragHandle = { CliSheetHandle() },
    ) {
        SideEffect {
            MaterialSheetMotion.applySlowSpatialSpec(sheetState, sheetMotionSpec)
        }
        CompositionLocalProvider(
            LocalCliBottomSheetDismissRequest provides requestDismiss,
            LocalCliBottomSheetDismissAfter provides requestDismissAfter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CliSpacing.md)
                    .padding(bottom = CliSpacing.lg),
            ) {
                if (title != null) {
                    val shownTitle = cliHeadingText(title)
                    Row(verticalAlignment = Alignment.Top) {
                        if (icon != null) {
                            val headerIconSize = cliModalHeaderIconSizeFor(resolvedHeaderIconRole)
                            Box(
                                modifier = Modifier
                                    .width(headerIconSize)
                                    .height(CliHeaderControlSlotHeight),
                                contentAlignment = Alignment.Center,
                            ) {
                                CliIcon(
                                    id = icon,
                                    contentDescription = null,
                                    size = headerIconSize,
                                    tint = when {
                                        icon == R.drawable.lin_trash -> colors.err
                                        resolvedHeaderIconRole == CliSheetHeaderIconRole.INFORMATION -> colors.info
                                        else -> colors.accent
                                    },
                                    modifier = Modifier.offset(
                                        y = cliModalHeaderIconOffsetFor(
                                            shownTitle,
                                            resolvedHeaderIconRole,
                                            pixelArtEnabled,
                                        ),
                                    ),
                                )
                            }
                            Spacer(modifier = Modifier.width(CliSpacing.xs))
                        }
                        Text(
                            text = shownTitle,
                            style = cliTitleStyle(shownTitle),
                            color = colors.fg,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = trailing != null)
                                .offset(y = cliHeadingOpticalOffsetFor(shownTitle, pixelArtEnabled)),
                        )
                        if (trailing != null) {
                            Spacer(modifier = Modifier.width(CliSpacing.xs))
                            Box(
                                modifier = Modifier
                                    .height(CliHeaderControlSlotHeight)
                                    .offset(
                                        y = cliModalHeaderControlOffsetFor(
                                            shownTitle,
                                            pixelArtEnabled,
                                        ),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                trailing()
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(CliSpacing.sm))
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = bodyMaxHeight)
                        .then(
                            if (contentScrollEnabled) {
                                Modifier.verticalScroll(bodyScrollState)
                            } else {
                                Modifier
                            },
                        ),
                    content = content,
                )
                Spacer(modifier = Modifier.height(CliSpacing.md))
                CliSheetFooter(footerLeading, footerTrailing, requestDismiss, closeLabel, closeActionTag)
            }
        }
    }
}

@Composable
private fun CliSheetFooter(
    leading: (@Composable RowScope.() -> Unit)?,
    trailing: (@Composable RowScope.() -> Unit)?,
    onDismiss: () -> Unit,
    closeLabel: String?,
    closeActionTag: String?,
) {
    if (leading == null && trailing == null) {
        CliModalCloseButton(
            onClick = onDismiss,
            label = closeLabel,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (closeActionTag == null) Modifier else Modifier.testTag(closeActionTag),
                ),
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke(this)
            CliModalCloseButton(
                onClick = onDismiss,
                label = closeLabel,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (closeActionTag == null) Modifier else Modifier.testTag(closeActionTag),
                    ),
            )
            trailing?.invoke(this)
        }
    }
}

private data class CliBottomSheetDismissRequests(
    val now: () -> Unit,
    val afterHidden: (() -> Unit) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberCliBottomSheetDismissRequests(
    sheetState: SheetState,
    onDismiss: () -> Unit,
): CliBottomSheetDismissRequests {
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var dismissInProgress by remember { mutableStateOf(false) }
    val dismissSheet: ((() -> Unit)?) -> Unit = { afterHidden ->
        if (!dismissInProgress) {
            dismissInProgress = true
            scope.launch {
                try {
                    if (sheetState.isVisible) {
                        sheetState.hide()
                    }
                } finally {
                    if (sheetState.isVisible) {
                        dismissInProgress = false
                    } else if (afterHidden == null) {
                        currentOnDismiss()
                    } else {
                        afterHidden()
                    }
                }
            }
        }
    }
    return CliBottomSheetDismissRequests(
        now = { dismissSheet(null) },
        afterHidden = { action -> dismissSheet(action) },
    )
}

internal val LocalCliBottomSheetDismissRequest = staticCompositionLocalOf<() -> Unit> {
    error("CliBottomSheet dismiss request is unavailable")
}

internal val LocalCliBottomSheetDismissAfter = staticCompositionLocalOf<(() -> Unit) -> Unit> {
    error("CliBottomSheet deferred dismiss request is unavailable")
}

internal enum class CliSheetHeaderIconRole {
    DEFAULT,
    INFORMATION,
}

internal fun cliModalHeaderIconRoleFor(
    role: CliSheetHeaderIconRole,
    @DrawableRes icon: Int?,
): CliSheetHeaderIconRole = when {
    role == CliSheetHeaderIconRole.INFORMATION -> CliSheetHeaderIconRole.INFORMATION
    icon == R.drawable.lin_info || icon == R.drawable.lin_help -> CliSheetHeaderIconRole.INFORMATION
    else -> CliSheetHeaderIconRole.DEFAULT
}

internal fun cliModalHeaderIconSizeFor(
    @Suppress("UNUSED_PARAMETER") role: CliSheetHeaderIconRole,
): Dp = CLI_SECTION_HEADER_ICON_SIZE

internal fun cliModalHeaderIconOffsetFor(
    text: String,
    @Suppress("UNUSED_PARAMETER") role: CliSheetHeaderIconRole = CliSheetHeaderIconRole.DEFAULT,
    pixelArtEnabled: Boolean = true,
): Dp = cliModalHeaderControlOffsetFor(text, pixelArtEnabled) + CLI_MODAL_HEADER_ICON_LIFT

internal fun cliModalHeaderControlOffsetFor(
    text: String,
    pixelArtEnabled: Boolean = true,
): Dp = cliHeadingOpticalOffsetFor(text, pixelArtEnabled) - CLI_ICON_OPTICAL_OFFSET

internal val CLI_MODAL_HEADER_ICON_LIFT = (-2).dp

@Composable
internal fun CliModalCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    dimWhenDisabled: Boolean = true,
) {
    CliButton(
        label = label ?: stringResource(R.string.cli_common_close_action),
        color = LocalCliColors.current.accent,
        enabled = enabled,
        dimWhenDisabled = dimWhenDisabled,
        onClick = onClick,
        modifier = modifier,
    )
}

internal fun cliModalSurfaceColor(appearance: PanelAppearance, panelColor: Color): Color =
    if (appearance == PanelAppearance.DARK) Color.Black else panelColor

private const val CLI_SHEET_MAX_BODY_FRACTION = 0.72f
private val CLI_SHEET_EDGE_RIM = 1.dp

@Composable
private fun CliSheetHandle() {
    val colors = LocalCliColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(CLI_SHEET_EDGE_RIM)
                .background(colors.border),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(vertical = CliSpacing.sm)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(
                modifier = Modifier
                    .width(32.dp)
                    .height(3.dp)
                    .background(colors.faint, RoundedCornerShape(CliRadius.hairline)),
            )
        }
    }
}
