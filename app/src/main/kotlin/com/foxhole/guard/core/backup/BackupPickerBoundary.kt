package com.foxhole.guard.core.backup

import kotlinx.coroutines.CancellationException

internal fun launchBackupPicker(
    cleanup: () -> Unit,
    onFailure: (Exception) -> Unit,
    launch: () -> Unit,
) {
    var launched = false
    try {
        launch()
        launched = true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure(error)
    } finally {
        if (!launched) cleanup()
    }
}

internal fun launchPendingBackupExportPicker(
    request: PendingBackupExport,
    clearPending: () -> Unit,
    onFailure: (Exception) -> Unit,
    launch: (String) -> Unit,
) = launchBackupPicker(
    cleanup = {
        request.close()
        clearPending()
    },
    onFailure = onFailure,
    launch = {
        launch(backupFileName(request.selection.includeProfiles, request.selection.includeSettings))
    },
)

internal fun <Uri> completeBackupExportPicker(
    uri: Uri?,
    request: PendingBackupExport?,
    onExport: (Uri, PendingBackupExport) -> Unit,
    onCancelled: () -> Unit,
) {
    if (uri == null || request == null) {
        request?.close()
        onCancelled()
    } else {
        try {
            onExport(uri, request)
        } finally {
            request.close()
        }
    }
}
