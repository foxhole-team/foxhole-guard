package com.foxhole.guard.ui.cli

import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.foxhole.guard.ui.FoxholeBannerEvent
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

private class CliTopNoticeState(
    val visible: State<Boolean>,
    val content: State<@Composable () -> Unit>,
) {
    val windows = CliNoticeWindowRegistry()
}

internal val LocalCliNoticeSink = staticCompositionLocalOf<((FoxholeBannerEvent) -> Unit)?> { null }
internal val LocalCliNoticeMotion = staticCompositionLocalOf<NoticeDismissState?> { null }
internal val LocalCliNoticeScope = staticCompositionLocalOf<CoroutineScope?> { null }

private val LocalCliTopNoticeState = staticCompositionLocalOf<CliTopNoticeState?> { null }

@Composable
internal fun CliTopNoticeProvider(
    visible: Boolean,
    noticeContent: @Composable () -> Unit,
    noticeKey: Any? = null,
    onNotice: ((FoxholeBannerEvent) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val currentVisible = rememberUpdatedState(visible)
    val currentContent = rememberUpdatedState(noticeContent)
    val state = remember { CliTopNoticeState(currentVisible, currentContent) }
    val scope = rememberCoroutineScope()
    val motion = remember(noticeKey) { NoticeDismissState() }
    DisposableEffect(motion) { onDispose { motion.stop() } }
    CompositionLocalProvider(LocalCliTopNoticeState provides state, LocalCliNoticeSink provides onNotice,
        LocalCliNoticeMotion provides motion, LocalCliNoticeScope provides scope, content = content)
}

@Composable
internal fun CliTopNoticeHost(closing: Boolean = false) {
    if (!CLI_EXPERIMENTAL_TOP_NOTICES_ENABLED) return
    val state = LocalCliTopNoticeState.current ?: return
    val owner = remember { Any() }
    DisposableEffect(state, owner) {
        state.windows.add(owner)
        onDispose { state.windows.remove(owner) }
    }
    SideEffect { state.windows.handoff(owner, closing) }
    if (!state.visible.value) return
    val active = state.windows.isForemost(owner)
    val visible = state.windows.draws(owner)

    val view = LocalView.current
    val root = view.rootView
    val frame = Rect().also(root::getWindowVisibleDisplayFrame)
    val width = with(LocalDensity.current) { (frame.width().takeIf { it > 0 } ?: root.width.coerceAtLeast(1)).toDp() }
    val position = remember(view) { CliTopNoticePositionProvider(view, margin = 0) }
    Popup(
        popupPositionProvider = position,
        properties = PopupProperties(
            flags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (active) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            inheritSecurePolicy = true, dismissOnBackPress = false, dismissOnClickOutside = false,
            excludeFromSystemGesture = false, usePlatformDefaultWidth = false,
        ),
    ) {
        Column(Modifier.width(width).graphicsLayer { alpha = if (visible) 1f else 0f }
            .then(if (active) Modifier else Modifier.clearAndSetSemantics { })) {
            state.content.value()
        }
    }
}

private class CliTopNoticePositionProvider(private val view: View, private val margin: Int) : PopupPositionProvider {
    private val frame = Rect()
    private val onScreen = IntArray(2)
    private val inWindow = IntArray(2)

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val root = view.rootView
        root.getLocationOnScreen(onScreen)
        root.getLocationInWindow(inWindow)
        root.getWindowVisibleDisplayFrame(frame)
        val visible = if (frame.isEmpty) {
            IntRect(0, 0, windowSize.width, windowSize.height)
        } else {
            IntRect(frame.left, frame.top, frame.right, frame.bottom)
        }
        val systemTop = ViewCompat.getRootWindowInsets(root)
            ?.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())?.top ?: 0
        // Popup offsets use the parent window's origin, including centered dialogs.
        return cliTopNoticePosition(
            visible,
            IntOffset(onScreen[0] - inWindow[0], onScreen[1] - inWindow[1]),
            popupContentSize,
            systemTop,
            margin,
        )
    }
}

internal fun cliTopNoticePosition(
    frame: IntRect,
    windowOrigin: IntOffset,
    contentSize: IntSize,
    systemTop: Int,
    margin: Int,
): IntOffset {
    val top = maxOf(frame.top, systemTop)
    val x = frame.left + (frame.width - contentSize.width).coerceAtLeast(0) / 2
    val y = (top + margin).coerceAtMost((frame.bottom - contentSize.height).coerceAtLeast(top))
    return IntOffset(x - windowOrigin.x, y - windowOrigin.y)
}
