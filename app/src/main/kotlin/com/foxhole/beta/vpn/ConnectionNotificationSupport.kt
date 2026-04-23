package com.foxhole.beta.vpn

import android.app.Service
import androidx.annotation.StringRes
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionState

internal data class ConnectionNotificationAction(
    val serviceAction: String,
    @param:StringRes val labelRes: Int,
    val requestCode: Int,
    val ongoing: Boolean,
)

internal fun notificationActionForState(state: ConnectionState): ConnectionNotificationAction =
    when (state) {
        ConnectionState.CONNECTING,
        ConnectionState.CONNECTED,
        ConnectionState.RECONNECTING,
        -> ConnectionNotificationAction(
            serviceAction = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
            labelRes = R.string.disconnect,
            requestCode = 2,
            ongoing = true,
        )

        ConnectionState.IDLE,
        ConnectionState.ERROR,
        -> ConnectionNotificationAction(
            serviceAction = FoxholeConnectionServiceContract.ACTION_RESTORE,
            labelRes = R.string.connect,
            requestCode = 3,
            ongoing = false,
        )
    }

internal fun Service.detachForegroundNotification() {
    stopForeground(Service.STOP_FOREGROUND_DETACH)
}
