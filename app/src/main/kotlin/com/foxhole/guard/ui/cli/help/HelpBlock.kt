package com.foxhole.guard.ui.cli.help

import androidx.annotation.StringRes

internal sealed interface HelpText {
    data class Resource(@StringRes val id: Int) : HelpText
    data class Literal(val value: String) : HelpText
}

internal data class HelpRow(val label: HelpText, val detail: HelpText, val code: Boolean = false, val latencyMs: Long? = null)

internal data class HelpInlineLink(@StringRes val label: Int, val url: String)

internal sealed interface HelpBlock {
    data class Heading(@StringRes val text: Int, val icon: Int? = null) : HelpBlock
    data class Paragraph(@StringRes val text: Int) : HelpBlock
    data class Items(val items: List<Int>, val numbered: Boolean = false, val start: Int = 1,
        val links: Map<Int, HelpInlineLink> = emptyMap()) : HelpBlock
    data class Notice(@StringRes val text: Int) : HelpBlock
    data class Comparison(@StringRes val title: Int?, val rows: List<HelpRow>,
        val twoColumns: Boolean = false, val labelFraction: Float = .3f, val contentSizedDetails: Boolean = false) : HelpBlock
    data class Chips(val labels: List<String>) : HelpBlock
    data class Details(val id: String, @StringRes val title: Int, val blocks: List<HelpBlock>) : HelpBlock
    data class Link(@StringRes val label: Int, val destination: HelpDestination) : HelpBlock
}

internal enum class HelpDestination { INSTALLATION, ANDROID_VPN_SETTINGS }

internal fun helpUsesVerticalRows(widthDp: Float, fontScale: Float): Boolean = widthDp < 480f || fontScale > 1.2f
