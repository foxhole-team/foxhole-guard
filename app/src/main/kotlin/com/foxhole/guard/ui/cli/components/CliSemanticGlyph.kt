package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import com.foxhole.guard.R

internal enum class CliSemanticGlyph {
    SUCCESS,
    INFORMATION,
    HELP,
    PENDING,
    WARNING,
    ERROR,
    BLOCKED,
}

@DrawableRes
internal fun cliSemanticIcon(glyph: CliSemanticGlyph): Int = when (glyph) {
    CliSemanticGlyph.SUCCESS -> R.drawable.lin_check
    CliSemanticGlyph.INFORMATION -> R.drawable.lin_info
    CliSemanticGlyph.HELP -> R.drawable.lin_help
    CliSemanticGlyph.PENDING -> R.drawable.lin_clock
    CliSemanticGlyph.WARNING -> R.drawable.lin_warning
    CliSemanticGlyph.ERROR -> R.drawable.lin_error
    CliSemanticGlyph.BLOCKED -> R.drawable.lin_forbidden
}
