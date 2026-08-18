package com.foxhole.guard.ui.cli.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.cliMarchingBorder

@Composable
internal fun CliBetaNoticeSheet(onAcknowledge: () -> Unit) {
    val colors = LocalCliColors.current
    CliBottomSheet(
        onDismiss = onAcknowledge,
        title = stringResource(R.string.cli_beta_notice_title),
        icon = R.drawable.pix_info,
    ) {
        val body = stringResource(R.string.cli_beta_notice_body)
        val dnsLimit = stringResource(R.string.cli_beta_notice_dns_protocols)
        val items = remember(body, dnsLimit) { betaNoticeItems(body, dnsLimit) }
        items.firstOrNull()?.let { donation ->
            CliIconTextItems(
                items = listOf(donation),
                framed = true,
                modifier = Modifier
                    .cliMarchingBorder(colors.accent)
                    .padding(CliSpacing.xs),
            )
        }
        if (items.size > 1) {
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliIconTextItems(
                items = items.drop(1),
                framed = true,
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.md))
        CliButton(
            label = stringResource(R.string.cli_beta_notice_ack),
            filled = true,
            color = colors.ok,
            onClick = onAcknowledge,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

internal fun betaNoticeItems(body: String, dnsLimit: String): List<CliQuickStartItem> =
    body.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapIndexed { index, text ->
            CliQuickStartItem(
                text = text,
                icon = BETA_NOTICE_ICONS.getOrElse(index) { R.drawable.pix_info },
            )
        }
        .toList()
        .let(::donationFirst) + CliQuickStartItem(dnsLimit.trim(), R.drawable.pix_dns)

private fun donationFirst(items: List<CliQuickStartItem>): List<CliQuickStartItem> {
    if (items.size <= DONATION_SOURCE_INDEX) return items
    val donation = items[DONATION_SOURCE_INDEX]
    return listOf(donation) + items.filterIndexed { index, _ -> index != DONATION_SOURCE_INDEX }
}

private val BETA_NOTICE_ICONS = listOf(
    R.drawable.pix_forbidden,
    R.drawable.pix_info,
    R.drawable.pix_clock,
    R.drawable.pix_settings,
    R.drawable.pix_journal,
    R.drawable.pix_star,
)

private const val DONATION_SOURCE_INDEX = 5
