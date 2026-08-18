package com.foxhole.guard.ui.cli.fox

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.foxhole.guard.widget.FOX_STATUS_ANIMATION_FRAMES
import kotlinx.coroutines.delay

private const val FRAME_MS = 330L

@Composable
internal fun CliFoxHero(
    modifier: Modifier = Modifier,
    size: Dp = 112.dp,
) {
    val frames = FOX_STATUS_ANIMATION_FRAMES
    var frame by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(FRAME_MS)
                frame = (frame + 1) % frames.size
            }
        }
    }
    Image(
        painter = painterResource(frames[frame % frames.size]),
        contentDescription = null,
        modifier = modifier.size(size),
    )
}
