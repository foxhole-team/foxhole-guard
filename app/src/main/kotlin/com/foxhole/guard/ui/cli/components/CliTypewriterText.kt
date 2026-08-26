package com.foxhole.guard.ui.cli.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.foxhole.guard.ui.cli.CliType
import kotlinx.coroutines.delay

@Composable
internal fun CliTypewriterText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = CliType.small,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    instant: Boolean = false,
    onFullyTyped: (() -> Unit)? = null,
) {
    var displayed by remember { mutableStateOf(if (instant) text else "") }
    LaunchedEffect(text, instant) {
        if (instant || !cliSystemMotionEnabled()) {
            displayed = text
            onFullyTyped?.invoke()
            return@LaunchedEffect
        }
        val common = displayed.commonPrefixWith(text).length
        while (displayed.length > common) {
            delay(CLI_ERASE_STEP_MS)
            displayed = displayed.dropLast(1)
        }
        while (displayed.length < text.length) {
            delay(CLI_TYPE_STEP_MS)
            displayed = text.take(displayed.length + 1)
        }
        onFullyTyped?.invoke()
    }
    Text(
        text = displayed,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
internal fun rememberCliTypedText(
    text: String,
    enabled: Boolean = true,
): String {
    var displayed by remember { mutableStateOf(text) }
    LaunchedEffect(text, enabled) {
        if (!enabled || !cliSystemMotionEnabled()) {
            displayed = text
            return@LaunchedEffect
        }
        val common = displayed.commonPrefixWith(text).length
        while (displayed.length > common) {
            delay(CLI_ERASE_STEP_MS)
            displayed = displayed.dropLast(1)
        }
        while (displayed.length < text.length) {
            delay(CLI_TYPE_STEP_MS)
            displayed = text.take(displayed.length + 1)
        }
    }
    return if (enabled) displayed else text
}
