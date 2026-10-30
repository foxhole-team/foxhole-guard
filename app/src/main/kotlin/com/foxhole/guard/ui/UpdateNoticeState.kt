package com.foxhole.guard.ui

import com.foxhole.core.model.UiSettings
import com.foxhole.guard.core.settings.normalizedUpdateNoticeAcknowledgement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class UpdateNoticeContent(val contentId: String, val revision: Long)

internal val CURRENT_UPDATE_NOTICE = UpdateNoticeContent("foxhole.release-notes.v1.20261003", 7L)

internal fun shouldShowUpdateNotice(ui: UiSettings, content: UpdateNoticeContent): Boolean {
    val acknowledged = ui.normalizedUpdateNoticeAcknowledgement()
    return acknowledged.updateNoticeAcknowledgedRevision < content.revision ||
        acknowledged.updateNoticeAcknowledgedRevision == content.revision &&
        acknowledged.updateNoticeAcknowledgedContentId != content.contentId
}

internal sealed interface UpdateNoticeAckResult {
    data object Saved : UpdateNoticeAckResult
    data object AlreadyPending : UpdateNoticeAckResult
    data class Failed(val error: Exception) : UpdateNoticeAckResult
}

internal class UpdateNoticeState {
    private val inProgressMutable = MutableStateFlow(false)
    private val retryGenerationMutable = MutableStateFlow(0)
    private val readOnlyVisibleMutable = MutableStateFlow(false)
    val inProgress: StateFlow<Boolean> = inProgressMutable.asStateFlow()
    val retryGeneration: StateFlow<Int> = retryGenerationMutable.asStateFlow()
    val readOnlyVisible: StateFlow<Boolean> = readOnlyVisibleMutable.asStateFlow()

    fun openForReading() { readOnlyVisibleMutable.value = true }
    fun closeReadOnly() { readOnlyVisibleMutable.value = false }

    suspend fun acknowledge(write: suspend () -> Unit): UpdateNoticeAckResult {
        if (!inProgressMutable.compareAndSet(expect = false, update = true)) return UpdateNoticeAckResult.AlreadyPending
        return try {
            write()
            closeReadOnly()
            UpdateNoticeAckResult.Saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            retryGenerationMutable.value++
            UpdateNoticeAckResult.Failed(error)
        } finally {
            inProgressMutable.value = false
        }
    }
}
