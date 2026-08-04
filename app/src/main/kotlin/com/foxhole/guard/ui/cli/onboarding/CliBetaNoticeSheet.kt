package com.foxhole.guard.ui.cli.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliButton

/**
 * Shown once after the wizard. Dismissing it is the acknowledgement, so the swipe and the button
 * write the same flag — a sheet that could be swiped away without recording it would return on the
 * next cold start.
 */
@Composable
internal fun CliBetaNoticeSheet(onAcknowledge: () -> Unit) {
    val colors = LocalCliColors.current
    CliBottomSheet(
        onDismiss = onAcknowledge,
        title = stringResource(R.string.cli_beta_notice_title),
        icon = R.drawable.pix_info,
    ) {
        Text(
            text = stringResource(R.string.cli_beta_notice_body),
            style = CliType.body,
            color = colors.dim,
        )
        Spacer(modifier = Modifier.height(CliSpacing.md))
        CliButton(
            label = stringResource(R.string.cli_beta_notice_ack),
            onClick = onAcknowledge,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}
