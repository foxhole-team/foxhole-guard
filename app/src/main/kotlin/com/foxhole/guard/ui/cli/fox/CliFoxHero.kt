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
import com.foxhole.guard.R
import kotlinx.coroutines.delay

/**
 * The home screen's brand hero: a cyber fox before a neon portal, six frames of the final logo
 * looping forever at 330ms a frame. The frames share the app's black background, so the art merges
 * into the terminal without a frame.
 */
private val HERO_FRAMES = intArrayOf(
    R.drawable.fox_hero_1,
    R.drawable.fox_hero_2,
    R.drawable.fox_hero_3,
    R.drawable.fox_hero_4,
    R.drawable.fox_hero_5,
    R.drawable.fox_hero_6,
)

private const val FRAME_MS = 330L

@Composable
internal fun CliFoxHero(
    modifier: Modifier = Modifier,
    size: Dp = 112.dp,
) {
    var frame by remember { mutableIntStateOf(0) }
    // The ticker runs only while the hero is composed *and* the app is visible: a delay loop is not
    // tied to the frame clock and would keep ticking in the background without this gate.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(FRAME_MS)
                frame = (frame + 1) % HERO_FRAMES.size
            }
        }
    }
    Image(
        painter = painterResource(HERO_FRAMES[frame]),
        contentDescription = null,
        modifier = modifier.size(size),
    )
}
