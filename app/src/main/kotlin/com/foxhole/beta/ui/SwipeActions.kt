package com.foxhole.beta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.foxhole.beta.R

internal data class FoxholeSwipeAction(
    val icon: ImageVector,
    val contentDescription: String,
    val tint: Color? = null,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoxholeSwipeActions(
    key: Any,
    actions: List<FoxholeSwipeAction>,
    modifier: Modifier = Modifier,
    revealed: Boolean? = null,
    onRevealChange: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (actions.isEmpty()) {
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
    val actionWidth = (actions.size * 44).dp + 12.dp
    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) {
                    setRevealed(true)
                    haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                }
                false
            },
            positionalThreshold = { distance -> distance * 0.28f },
        )

    Box(modifier = modifier.fillMaxWidth()) {
        SwipeActionsBackground(
            actions = actions,
            onAction = {},
            modifier = Modifier.align(Alignment.CenterEnd),
        )
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = {},
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true,
            content = {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .offset(x = if (isRevealed) -actionWidth else 0.dp),
                ) {
                    content()
                }
            },
        )
        if (isRevealed) {
            SwipeActionsBackground(
                actions = actions,
                onAction = {
                    setRevealed(false)
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                },
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .zIndex(1f),
            )
        }
    }
}

@Composable
private fun SwipeActionsBackground(
    actions: List<FoxholeSwipeAction>,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            IconButton(
                onClick = {
                    onAction()
                    action.onClick()
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = action.contentDescription.ifBlank { stringResource(R.string.action_label) },
                    tint = action.tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
