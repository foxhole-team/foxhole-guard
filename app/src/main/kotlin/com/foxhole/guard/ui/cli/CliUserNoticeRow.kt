package com.foxhole.guard.ui.cli

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.foxhole.guard.ui.FoxholeBannerEvent

@Composable
internal fun CliUserNoticeRow(
    notice: FoxholeBannerEvent,
    noticeId: Long = 0L,
    lifetime: NoticeLifetime? = null,
    onDismiss: () -> Unit,
) {
    val colors = LocalCliColors.current
    val color = cliNoticeColor(notice.tone, colors)
    val view = LocalView.current
    val languageTags = LocalResources.current.configuration.locales.toLanguageTags()
    val noticeStyle = CliType.body.copy(
        localeList = if (languageTags.isBlank()) LocaleList.current else LocaleList(languageTags),
        lineBreak = LineBreak.Paragraph,
        hyphens = Hyphens.Auto,
    )
    val progress = rememberNoticeProgress(noticeId, lifetime)
    NoticeDismissible(
        noticeId = noticeId,
        onDismiss = {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onDismiss()
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) { _ ->
        Surface(
            color = lerp(colors.panel, color, 0.08f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.65f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.noticeRemainingLine(progress, color).padding(12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    notice.message,
                    style = noticeStyle,
                    softWrap = true,
                    color = color,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

