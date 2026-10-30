package com.foxhole.guard.ui.cli

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.FoxholeBannerEvent
import com.foxhole.guard.ui.cli.components.CliIcon
import com.foxhole.guard.ui.cli.components.cliPressable

@Composable
internal fun CliUserNoticeRow(notice: FoxholeBannerEvent, onDismiss: () -> Unit) {
    val colors = LocalCliColors.current
    val color = cliNoticeColor(notice.tone, colors)
    Surface(
        color = lerp(colors.panel, color, 0.08f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.65f)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                notice.message,
                style = CliType.body,
                color = color,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
            CliNoticeCloseButton(color = color, onClose = onDismiss)
        }
    }
}

@Composable
internal fun CliNoticeCloseButton(color: Color, onClose: () -> Unit) {
    val view = LocalView.current
    Box(
        modifier = Modifier.size(48.dp).cliPressable(role = Role.Button, onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onClose()
        }),
        contentAlignment = Alignment.Center,
    ) {
        CliIcon(
            id = R.drawable.lin_cross,
            contentDescription = cliLabelText(stringResource(R.string.cli_common_close_action)),
            tint = color,
            size = 20.dp,
        )
    }
}
