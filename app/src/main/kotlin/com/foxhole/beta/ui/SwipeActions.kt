package com.foxhole.beta.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import kotlin.math.roundToInt

internal data class FoxholeSwipeAction(
    val icon: ImageVector,
    val contentDescription: String,
    val testTag: String? = null,
    val tint: Color? = null,
    val onClick: () -> Unit,
)

@Composable
internal fun FoxholeSwipeActions(
    key: Any,
    actions: List<FoxholeSwipeAction>,
    modifier: Modifier = Modifier,
    revealed: Boolean? = null,
    onRevealChange: ((Boolean) -> Unit)? = null,
    startAction: FoxholeSwipeAction? = null,
    onSwipeRight: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (actions.isEmpty() && startAction == null && onSwipeRight == null) {
        content()
        return
    }
    var localRevealed by rememberSaveable(key) { mutableStateOf(false) }
    val isRevealed = revealed ?: localRevealed
    fun setRevealed(value: Boolean) {
        if (onRevealChange == null) {
            localRevealed = value
        } else {
            onRevealChange(value)
        }
    }
    val haptic = LocalHapticFeedback.current
    val startSwipeAction = startAction?.onClick ?: onSwipeRight
    val dragSession =
        rememberSwipeActionsDragSession(
            key = key,
            actionCount = actions.size,
            isRevealed = isRevealed,
            startSwipeAction = startSwipeAction,
            setRevealed = ::setRevealed,
        )
    val accessibilityActions =
        swipeActionsAccessibilityActions(
            startAction = startAction,
            actions = actions,
            setRevealed = ::setRevealed,
        )

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics {
                    customActions = accessibilityActions
                },
    ) {
        if (startAction != null) {
            SwipeActionsStartBackground(
                action = startAction,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        if (actions.isNotEmpty()) {
            SwipeActionsBackground(
                actions = actions,
                onAction = {},
                exposeTestTags = false,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        SwipeActionsGestureContent(dragSession = dragSession, content = content)
        if (isRevealed && actions.isNotEmpty()) {
            SwipeActionsBackground(
                actions = actions,
                onAction = {
                    setRevealed(false)
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                },
                exposeTestTags = true,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

private enum class SwipeActionsValue {
    Settled,
    EndRevealed,
    StartAction,
}

private data class SwipeActionsDragSession(
    val state: AnchoredDraggableState<SwipeActionsValue>,
    val contentOffset: Int,
    val flingBehavior: TargetedFlingBehavior,
)

@Composable
private fun rememberSwipeActionsDragSession(
    key: Any,
    actionCount: Int,
    isRevealed: Boolean,
    startSwipeAction: (() -> Unit)?,
    setRevealed: (Boolean) -> Unit,
): SwipeActionsDragSession {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val endSwipeEnabled = actionCount > 0
    val actionWidth = if (endSwipeEnabled) (actionCount * 48).dp + 12.dp else 0.dp
    val actionWidthPx = with(density) { actionWidth.toPx() }
    val startActionWidthPx = with(density) { 72.dp.toPx() }
    val currentStartSwipeAction by rememberUpdatedState(startSwipeAction)
    val dragState =
        rememberSaveable(key, saver = AnchoredDraggableState.Saver()) {
            AnchoredDraggableState(SwipeActionsValue.Settled)
        }
    val settleAnimationSpec = swipeActionsSettleAnimationSpec()
    val flingBehavior =
        AnchoredDraggableDefaults.flingBehavior(
            state = dragState,
            positionalThreshold = { distance -> distance * SWIPE_ACTION_REVEAL_THRESHOLD_FRACTION },
            animationSpec = settleAnimationSpec,
        )
    SyncSwipeActionsAnchors(
        dragState = dragState,
        actionWidthPx = actionWidthPx,
        startActionWidthPx = startActionWidthPx,
        endSwipeEnabled = endSwipeEnabled,
        startSwipeEnabled = startSwipeAction != null,
        isRevealed = isRevealed,
    )
    SyncSwipeActionsSettledValue(
        dragState = dragState,
        isRevealed = isRevealed,
        endSwipeEnabled = endSwipeEnabled,
        settleAnimationSpec = settleAnimationSpec,
        setRevealed = setRevealed,
        onStartSwipeAction = { currentStartSwipeAction?.invoke() },
        haptic = haptic,
    )
    return SwipeActionsDragSession(
        state = dragState,
        contentOffset = dragState.offset.takeUnless { it.isNaN() }?.roundToInt() ?: 0,
        flingBehavior = flingBehavior,
    )
}

@Composable
private fun swipeActionsAccessibilityActions(
    startAction: FoxholeSwipeAction?,
    actions: List<FoxholeSwipeAction>,
    setRevealed: (Boolean) -> Unit,
): List<CustomAccessibilityAction> {
    val defaultActionLabel = stringResource(R.string.action_label)
    return buildList {
        startAction?.let { action ->
            add(swipeAccessibilityAction(action, defaultActionLabel, setRevealed))
        }
        actions.mapTo(this) { action ->
            swipeAccessibilityAction(action, defaultActionLabel, setRevealed)
        }
    }
}

private fun swipeAccessibilityAction(
    action: FoxholeSwipeAction,
    defaultLabel: String,
    setRevealed: (Boolean) -> Unit,
): CustomAccessibilityAction =
    CustomAccessibilityAction(
        label = action.contentDescription.ifBlank { defaultLabel },
    ) {
        setRevealed(false)
        action.onClick()
        true
    }

@Composable
private fun SyncSwipeActionsAnchors(
    dragState: AnchoredDraggableState<SwipeActionsValue>,
    actionWidthPx: Float,
    startActionWidthPx: Float,
    endSwipeEnabled: Boolean,
    startSwipeEnabled: Boolean,
    isRevealed: Boolean,
) {
    LaunchedEffect(actionWidthPx, startActionWidthPx, endSwipeEnabled, startSwipeEnabled) {
        dragState.updateAnchors(
            DraggableAnchors {
                SwipeActionsValue.Settled at 0f
                if (endSwipeEnabled) {
                    SwipeActionsValue.EndRevealed at -actionWidthPx
                }
                if (startSwipeEnabled) {
                    SwipeActionsValue.StartAction at startActionWidthPx
                }
            },
            newTarget = if (isRevealed && endSwipeEnabled) SwipeActionsValue.EndRevealed else SwipeActionsValue.Settled,
        )
    }
}

@Composable
private fun SyncSwipeActionsSettledValue(
    dragState: AnchoredDraggableState<SwipeActionsValue>,
    isRevealed: Boolean,
    endSwipeEnabled: Boolean,
    settleAnimationSpec: AnimationSpec<Float>,
    setRevealed: (Boolean) -> Unit,
    onStartSwipeAction: () -> Unit,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
) {
    LaunchedEffect(isRevealed, endSwipeEnabled) {
        val target = if (isRevealed && endSwipeEnabled) SwipeActionsValue.EndRevealed else SwipeActionsValue.Settled
        if (dragState.settledValue != target && dragState.targetValue != target) {
            dragState.animateTo(target, settleAnimationSpec)
        }
    }
    LaunchedEffect(dragState.settledValue) {
        handleSwipeActionsSettledValue(
            dragState = dragState,
            isRevealed = isRevealed,
            settleAnimationSpec = settleAnimationSpec,
            setRevealed = setRevealed,
            onStartSwipeAction = onStartSwipeAction,
            haptic = haptic,
        )
    }
}

private suspend fun handleSwipeActionsSettledValue(
    dragState: AnchoredDraggableState<SwipeActionsValue>,
    isRevealed: Boolean,
    settleAnimationSpec: AnimationSpec<Float>,
    setRevealed: (Boolean) -> Unit,
    onStartSwipeAction: () -> Unit,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
) {
    when (dragState.settledValue) {
        SwipeActionsValue.EndRevealed -> {
            if (!isRevealed) {
                setRevealed(true)
                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            }
        }
        SwipeActionsValue.StartAction -> {
            setRevealed(false)
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onStartSwipeAction()
            dragState.animateTo(SwipeActionsValue.Settled, settleAnimationSpec)
        }
        SwipeActionsValue.Settled -> {
            if (isRevealed) {
                setRevealed(false)
                haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
            }
        }
    }
}

@Composable
private fun SwipeActionsGestureContent(
    dragSession: SwipeActionsDragSession,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(x = dragSession.contentOffset, y = 0) }
                .anchoredDraggable(
                    state = dragSession.state,
                    orientation = Orientation.Horizontal,
                    flingBehavior = dragSession.flingBehavior,
                ),
    ) {
        content()
    }
}

private fun swipeActionsSettleAnimationSpec(): AnimationSpec<Float> =
    tween(
        durationMillis = FoxholeMotionTokens.StandardDurationMs,
        easing = FoxholeMotionTokens.NavigationIndicatorEasing,
    )

@Composable
private fun SwipeActionsStartBackground(
    action: FoxholeSwipeAction,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(start = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = action.contentDescription.ifBlank { stringResource(R.string.action_label) },
            tint = action.tint ?: MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun SwipeActionsBackground(
    actions: List<FoxholeSwipeAction>,
    onAction: () -> Unit,
    exposeTestTags: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            val destructive = action.tint == MaterialTheme.colorScheme.error
            IconButton(
                onClick = {
                    onAction()
                    action.onClick()
                },
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor =
                            if (destructive) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                        contentColor =
                            if (destructive) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                action.tint ?: MaterialTheme.colorScheme.onSecondaryContainer
                            },
                    ),
                modifier =
                    Modifier
                        .size(44.dp)
                        .then(
                            if (exposeTestTags) {
                                action.testTag?.let { Modifier.testTag(it) } ?: Modifier
                            } else {
                                Modifier
                            },
                        ),
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = action.contentDescription.ifBlank { stringResource(R.string.action_label) },
                    tint =
                        if (destructive) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            action.tint ?: MaterialTheme.colorScheme.onSecondaryContainer
                        },
                )
            }
        }
    }
}

private const val SWIPE_ACTION_REVEAL_THRESHOLD_FRACTION = 0.45f
