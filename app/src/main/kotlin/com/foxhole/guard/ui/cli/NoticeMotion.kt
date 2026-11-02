package com.foxhole.guard.ui.cli

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun NoticeExpiryEffect(noticeId: Long?, lifetime: NoticeLifetime?, onExpire: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val expire = remember(noticeId) { onExpire }
    LaunchedEffect(noticeId, lifetime, lifecycle) {
        if (lifetime == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(lifetime.remainingMillis(SystemClock.elapsedRealtime()))
            expire()
        }
    }
}

@Composable
internal fun rememberNoticeProgress(noticeId: Long, lifetime: NoticeLifetime?): State<Float>? {
    if (lifetime == null) return null
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val progress = remember(noticeId) {
        Animatable(lifetime.remainingFraction(SystemClock.elapsedRealtime()))
    }
    LaunchedEffect(noticeId, lifetime, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Window/lifecycle changes catch up to the same deadline instead of restarting it.
            val remaining = lifetime.remainingMillis(SystemClock.elapsedRealtime())
            progress.snapTo(minOf(progress.value, lifetime.remainingFraction(SystemClock.elapsedRealtime())))
            if (remaining > 0L) progress.animateTo(0f, tween(remaining.toInt(), easing = LinearEasing))
        }
    }
    return progress.asState()
}

internal fun Modifier.noticeRemainingLine(progress: State<Float>?, color: Color): Modifier =
    if (progress == null) this else drawWithContent {
        drawContent()
        val thickness = 2.dp.toPx()
        drawRect(color.copy(alpha = 0.18f), Offset(0f, size.height - thickness), Size(size.width, thickness))
        drawRect(color, Offset(0f, size.height - thickness), Size(size.width * progress.value, thickness))
    }

@Composable
internal fun NoticeDismissible(
    noticeId: Any,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var offsetY by remember(noticeId) { mutableFloatStateOf(0f) }
    var height by remember(noticeId) { mutableFloatStateOf(1f) }
    var dismissing by remember(noticeId) { mutableStateOf(false) }
    var motion by remember(noticeId) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val dismissCurrent = remember(noticeId) { onDismiss }
    DisposableEffect(noticeId) {
        onDispose { motion?.cancel() }
    }
    val maxDistance = with(LocalDensity.current) { 48.dp.toPx() }
    val dismiss: () -> Unit = {
        if (!dismissing) {
            dismissing = true
            motion?.cancel()
            motion = scope.launch {
                animate(offsetY, -height, animationSpec = tween(160, easing = FastOutSlowInEasing)) { value, _ ->
                    offsetY = value
                }
                dismissCurrent()
            }
        }
    }
    val settle: () -> Unit = {
        if (!dismissing) {
            motion?.cancel()
            motion = scope.launch {
                animate(offsetY, 0f, animationSpec = tween(160, easing = FastOutSlowInEasing)) { value, _ ->
                    offsetY = value
                }
            }
        }
    }
    Box(
        modifier.semantics(mergeDescendants = true) { this.dismiss { dismiss(); true } }
            .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
            .graphicsLayer {
                translationY = offsetY
                alpha = (1f + offsetY / height).coerceIn(0f, 1f)
            }
            .pointerInput(noticeId) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // A second tap during exit must not reach an action in the notice.
                        if (dismissing) event.changes.forEach { it.consume() }
                    }
                }
            }
            .pointerInput(noticeId, maxDistance) {
                detectVerticalDragGestures(
                    onDragStart = { if (!dismissing) motion?.cancel() },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        if (!dismissing) offsetY = (offsetY + amount).coerceIn(-height, 0f)
                    },
                    onDragEnd = {
                        if (shouldDismissNoticeUp(offsetY, height, maxDistance)) dismiss() else settle()
                    },
                    onDragCancel = { settle() },
                )
            },
    ) {
        content(dismiss)
    }
}
