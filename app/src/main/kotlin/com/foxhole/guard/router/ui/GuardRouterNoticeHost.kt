package com.foxhole.guard.router.ui

import android.os.SystemClock
import androidx.compose.material3.SnackbarData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.foxhole.guard.ui.FoxholeBannerEvent
import com.foxhole.guard.ui.FoxholeBannerTone
import com.foxhole.guard.ui.cli.CliUserNoticeRow
import com.foxhole.guard.ui.cli.NoticeExpiryEffect
import com.foxhole.guard.ui.cli.NoticeLifetime

@Composable
internal fun GuardRouterNoticeContent(data: SnackbarData) {
    val visuals = data.visuals as? GuardRouterNoticeVisuals ?: return
    val lifetime = remember(data) {
        visuals.lifetime
    }
    val event = remember(data) {
        FoxholeBannerEvent(visuals.message, if (visuals.error) FoxholeBannerTone.ERROR else FoxholeBannerTone.INFO)
    }
    NoticeExpiryEffect(visuals.id, lifetime) { data.dismiss() }
    CliUserNoticeRow(event, noticeId = visuals.id, lifetime = lifetime, onDismiss = data::dismiss)
}
