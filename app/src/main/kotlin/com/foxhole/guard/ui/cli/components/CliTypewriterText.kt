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

/**
 * Terminal-style text that TYPES its content in and, when [text] changes, DELETES back to the
 * common prefix before typing the new tail — the home-screen status idiom. Enter into composition
 * types from empty; [instant] renders statically (the "already typed once" path for the cold-start
 * brand header).
 */
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
        if (instant) {
            displayed = text
            return@LaunchedEffect
        }
        // Delete down to the longest common prefix, then type the new tail.
        val common = displayed.commonPrefixWith(text).length
        while (displayed.length > common) {
            delay(DELETE_CHAR_DELAY_MS)
            displayed = displayed.dropLast(1)
        }
        while (displayed.length < text.length) {
            delay(TYPE_CHAR_DELAY_MS)
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

private const val TYPE_CHAR_DELAY_MS = 18L
private const val DELETE_CHAR_DELAY_MS = 9L
