package com.foxhole.guard.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.foxhole.guard.R
import com.foxhole.guard.core.sharing.FileShareException
import com.foxhole.guard.core.sharing.FileShareFailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun HomeViewModel.publishFileShare(
    uri: Uri,
    lifetimeMs: Long,
    maxDownloads: Int,
    password: CharArray?,
) {
    viewModelScope.launch {
        try {
            container.fileShareController.publish(uri, lifetimeMs, maxDownloads, password)
            emitSuccess(getApplication<Application>().getString(R.string.cli_file_share_created))
        } catch (error: CancellationException) {
            password?.fill('\u0000')
            throw error
        } catch (error: FileShareException) {
            emitError(getApplication<Application>().getString(error.reason.messageResource()))
        }
    }
}

internal fun HomeViewModel.revokeFileShare(id: String) {
    viewModelScope.launch {
        if (container.fileShareController.revoke(id)) {
            emitInfo(getApplication<Application>().getString(R.string.cli_file_share_revoked))
        }
    }
}

internal suspend fun HomeViewModel.fileShareInvitationIntent(id: String): Intent? =
    container.fileShareController.invitationIntent(id)

private fun FileShareFailureReason.messageResource(): Int =
    when (this) {
        FileShareFailureReason.TOR_REQUIRED -> R.string.cli_file_share_error_tor_required
        FileShareFailureReason.BUSY -> R.string.cli_file_share_error_busy
        FileShareFailureReason.PASSWORD_INVALID -> R.string.cli_file_share_error_password
        FileShareFailureReason.FILE_UNAVAILABLE -> R.string.cli_file_share_error_file
        FileShareFailureReason.FILE_TOO_LARGE -> R.string.cli_file_share_error_too_large
        FileShareFailureReason.STORAGE_UNAVAILABLE -> R.string.cli_file_share_error_storage
        FileShareFailureReason.CORE_UNAVAILABLE -> R.string.cli_file_share_error_core
        FileShareFailureReason.VAULT_UNAVAILABLE -> R.string.cli_file_share_error_vault
        FileShareFailureReason.SHARE_CREATE_FAILED -> R.string.cli_file_share_error_create
        FileShareFailureReason.PUBLICATION_FAILED -> R.string.cli_file_share_error_publish
        FileShareFailureReason.INVITATION_FAILED -> R.string.cli_file_share_error_invitation
    }
