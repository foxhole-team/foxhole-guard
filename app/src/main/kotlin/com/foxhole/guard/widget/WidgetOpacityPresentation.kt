package com.foxhole.guard.widget

internal const val WIDGET_OPACITY_MIN_PERCENT = 0
internal const val WIDGET_OPACITY_MAX_PERCENT = 100
internal const val WIDGET_DEFAULT_OPACITY_PERCENT = 50
internal const val WIDGET_OPACITY_CUSTOM_OPTION_ID = "custom"
internal const val WIDGET_OPACITY_SLIDER_STEPS =
    WIDGET_OPACITY_MAX_PERCENT - WIDGET_OPACITY_MIN_PERCENT - 1

internal data class WidgetOpacityChoice(
    val id: String,
    val percent: Int,
    val custom: Boolean = false,
)

internal data class WidgetOpacityPresentation(
    val percent: Int,
    val selectedId: String,
    val choices: List<WidgetOpacityChoice>,
)

internal fun widgetOpacityPresentation(currentPercent: Int): WidgetOpacityPresentation {
    val percent = currentPercent.coerceIn(WIDGET_OPACITY_MIN_PERCENT, WIDGET_OPACITY_MAX_PERCENT)
    return WidgetOpacityPresentation(
        percent = percent,
        selectedId = if (percent == WIDGET_DEFAULT_OPACITY_PERCENT) {
            WIDGET_DEFAULT_OPACITY_PERCENT.toString()
        } else {
            WIDGET_OPACITY_CUSTOM_OPTION_ID
        },
        choices = listOf(
            WidgetOpacityChoice(
                id = WIDGET_DEFAULT_OPACITY_PERCENT.toString(),
                percent = WIDGET_DEFAULT_OPACITY_PERCENT,
            ),
            WidgetOpacityChoice(
                id = WIDGET_OPACITY_CUSTOM_OPTION_ID,
                percent = percent,
                custom = true,
            ),
        ),
    )
}

internal fun widgetOpacityDraft(raw: String): String? {
    val digits = raw.filter(Char::isDigit).take(WIDGET_OPACITY_INPUT_DIGITS)
    return digits.takeIf {
        it.isEmpty() || it.toIntOrNull() in WIDGET_OPACITY_MIN_PERCENT..WIDGET_OPACITY_MAX_PERCENT
    }
}

private const val WIDGET_OPACITY_INPUT_DIGITS = 3
