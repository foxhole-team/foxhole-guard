package com.foxhole.guard.ui.cli.profiles

import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliMotion
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliType
import com.foxhole.guard.ui.cli.cliLabelText
import com.foxhole.guard.ui.cli.cliVerticalScalarSpec
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliDashedInfoNote
import com.foxhole.guard.ui.cli.components.LocalCliBottomSheetDismissAfter
import com.foxhole.guard.ui.cli.components.cliSelectionEmphasis

@Composable
internal fun CliProfileTemplateSheet(
    title: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onContinue: (String) -> Unit,
) {
    val colors = LocalCliColors.current
    var selectedType by rememberSaveable { mutableStateOf<String?>(null) }
    CliBottomSheet(
        onDismiss = onDismiss,
        title = title,
        icon = R.drawable.lin_add,
        closeLabel = stringResource(R.string.cli_common_no_cancel),
        footerTrailing = {
            CliTemplateContinueAction(
                selectedType = selectedType,
                busy = busy,
                onContinue = onContinue,
            )
        },
    ) {
        CliDashedInfoNote(
            text = cliLabelText(stringResource(R.string.cli_prof_edit_add_type)),
            centered = true,
            centeredIconLeading = true,
            color = colors.info,
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliButton(
            label = "AmneziaWG",
            color = colors.accent,
            onClick = { selectedType = CLI_AMNEZIA_WIREGUARD_TYPE },
            modifier = Modifier
                .fillMaxWidth()
                .cliSelectionEmphasis(
                    selected = selectedType == CLI_AMNEZIA_WIREGUARD_TYPE,
                    cornerRadius = TEMPLATE_TILE_RADIUS,
                    color = colors.ok,
                ),
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliTemplateGrid(
            types = CLI_NEW_OUTBOUND_TYPES,
            selectedType = selectedType,
            onSelect = { type -> selectedType = type },
        )
    }
}

@Composable
private fun CliTemplateGrid(
    types: List<String>,
    selectedType: String?,
    onSelect: (String) -> Unit,
) {
    val columns = cliTemplateGridColumns(types.size)
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    CliTemplateTileTypography {
        Column(verticalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
            types.chunked(columns).forEach { rowTypes ->
                Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
                    rowTypes.forEach { type ->
                        CliTemplateTile(
                            type = type,
                            selected = type == selectedType,
                            revealed = revealed,
                            onSelect = { onSelect(type) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowTypes.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun CliTemplateTile(
    type: String,
    selected: Boolean,
    revealed: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val reveal by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = cliVerticalScalarSpec(),
        label = "cliTemplateTileReveal",
    )
    CliButton(
        label = cliTemplateTileLabel(type),
        color = colors.accent,
        onClick = onSelect,
        modifier = modifier
            .graphicsLayer {
                alpha = reveal
                val scale = TEMPLATE_REVEAL_MIN_SCALE + (1f - TEMPLATE_REVEAL_MIN_SCALE) * reveal
                scaleX = scale
                scaleY = scale
                translationY = (1f - reveal) * TEMPLATE_REVEAL_RISE.toPx()
            }
            .cliSelectionEmphasis(
                selected = selected,
                cornerRadius = TEMPLATE_TILE_RADIUS,
                color = colors.ok,
            ),
    )
}

@Composable
private fun RowScope.CliTemplateContinueAction(
    selectedType: String?,
    busy: Boolean,
    onContinue: (String) -> Unit,
) {
    val colors = LocalCliColors.current
    val dismissAfter = LocalCliBottomSheetDismissAfter.current
    val transition = updateTransition(targetState = selectedType, label = "cliTemplateContinue")
    val weight by transition.animateFloat(
        transitionSpec = { CliMotion.settle() },
        label = "cliTemplateContinueWeight",
    ) { type ->
        if (type == null) TEMPLATE_HIDDEN_ACTION_WEIGHT else TEMPLATE_VISIBLE_ACTION_WEIGHT
    }
    val alpha by transition.animateFloat(
        transitionSpec = { CliMotion.standard() },
        label = "cliTemplateContinueAlpha",
    ) { type ->
        if (type == null) 0f else 1f
    }
    val slide by transition.animateDp(
        transitionSpec = { CliMotion.settle() },
        label = "cliTemplateContinueSlide",
    ) { type ->
        if (type == null) TEMPLATE_CONTINUE_SLIDE else 0.dp
    }
    Box(
        modifier = Modifier
            .weight(weight)
            .alpha(alpha)
            .clipToBounds()
            .graphicsLayer { translationX = slide.toPx() },
    ) {
        if (transition.currentState != null || transition.targetState != null) {
            CliButton(
                label = stringResource(R.string.cli_wizard_continue),
                color = colors.ok,
                enabled = !busy && selectedType != null,
                onClick = {
                    selectedType?.let { type -> dismissAfter { onContinue(type) } }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CliTemplateTileTypography(content: @Composable () -> Unit) {
    val type = LocalCliType.current
    val scoped = remember(type) { type.copy(button = type.button.scaledBy(TEMPLATE_TILE_STEP_RATIO)) }
    CompositionLocalProvider(LocalCliType provides scoped, content = content)
}

internal fun cliTemplateGridColumns(count: Int): Int =
    if (count % TEMPLATE_WIDE_COLUMNS == 0 || count % TEMPLATE_NARROW_COLUMNS != 0) {
        TEMPLATE_WIDE_COLUMNS
    } else {
        TEMPLATE_NARROW_COLUMNS
    }

internal fun cliTemplateTileLabel(type: String): String =
    when (type) {
        "shadowsocks" -> "ss"
        "hysteria2" -> "hy2"
        "wireguard" -> "wg"
        else -> type
    }

private const val TEMPLATE_WIDE_COLUMNS = 3
private const val TEMPLATE_NARROW_COLUMNS = 2

private const val TEMPLATE_REVEAL_MIN_SCALE = 0.88f
private val TEMPLATE_REVEAL_RISE = 10.dp
private val TEMPLATE_CONTINUE_SLIDE = 24.dp

private val TEMPLATE_TILE_RADIUS = 6.dp

private const val TEMPLATE_TILE_STEP_RATIO = 0.74f
private const val TEMPLATE_HIDDEN_ACTION_WEIGHT = 0.001f
private const val TEMPLATE_VISIBLE_ACTION_WEIGHT = 1f
