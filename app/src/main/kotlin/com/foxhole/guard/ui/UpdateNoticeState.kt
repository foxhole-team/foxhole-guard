package com.foxhole.guard.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.foxhole.core.model.UiSettings
import com.foxhole.guard.core.settings.normalizedUpdateNoticeAcknowledgement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

internal data class UpdateNoticeContent(val contentId: String, val revision: Long)

internal val CURRENT_UPDATE_NOTICE = UpdateNoticeContent("foxhole.release-notes.v1.20261003", 8L)

internal suspend fun installedUpdateNotice(context: Context): UpdateNoticeContent = withContext(Dispatchers.IO) {
    val info = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    require(versionCode > 0 && info.lastUpdateTime > 0) { "notice_install_generation_unavailable" }
    CURRENT_UPDATE_NOTICE.copy(contentId = "${CURRENT_UPDATE_NOTICE.contentId}:install:$versionCode:${info.lastUpdateTime}")
}

internal fun shouldShowUpdateNotice(ui: UiSettings, content: UpdateNoticeContent): Boolean {
    val acknowledged = ui.normalizedUpdateNoticeAcknowledgement()
    return acknowledged.updateNoticeAcknowledgedContentId != content.contentId ||
        acknowledged.updateNoticeAcknowledgedRevision < content.revision
}

internal sealed interface UpdateNoticeAckResult {
    data object Saved : UpdateNoticeAckResult
    data object AlreadyPending : UpdateNoticeAckResult
    data class Failed(val error: Exception) : UpdateNoticeAckResult
}

internal class UpdateNoticeState(
    private val readContent: suspend () -> UpdateNoticeContent = { CURRENT_UPDATE_NOTICE },
) {
    private val inProgressMutable = MutableStateFlow(false)
    private val retryGenerationMutable = MutableStateFlow(0)
    private val readOnlyVisibleMutable = MutableStateFlow(false)
    private val contentMutable = MutableStateFlow<UpdateNoticeContent?>(null)
    private val loadedMutable = MutableStateFlow(false)
    private val keepVisibleMutable = MutableStateFlow(false)
    private val contentReadGeneration = AtomicLong()
    val inProgress: StateFlow<Boolean> = inProgressMutable.asStateFlow()
    val retryGeneration: StateFlow<Int> = retryGenerationMutable.asStateFlow()
    val readOnlyVisible: StateFlow<Boolean> = readOnlyVisibleMutable.asStateFlow()
    val content: StateFlow<UpdateNoticeContent?> = contentMutable.asStateFlow()
    val loaded: StateFlow<Boolean> = loadedMutable.asStateFlow()
    val keepVisible: StateFlow<Boolean> = keepVisibleMutable.asStateFlow()

    fun openForReading() { readOnlyVisibleMutable.value = true }
    fun closeReadOnly() { readOnlyVisibleMutable.value = false }

    suspend fun load() {
        try { refreshContent() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            contentMutable.value = null
            loadedMutable.value = true
            keepVisibleMutable.value = true
        }
    }

    private suspend fun refreshContent(): UpdateNoticeContent {
        val request = contentReadGeneration.incrementAndGet()
        val content = readContent()
        check(request == contentReadGeneration.get()) { "notice_install_generation_changed" }
        contentMutable.value = content
        loadedMutable.value = true
        return content
    }

    suspend fun acknowledge(write: suspend (UpdateNoticeContent) -> Unit): UpdateNoticeAckResult {
        if (!inProgressMutable.compareAndSet(expect = false, update = true)) return UpdateNoticeAckResult.AlreadyPending
        keepVisibleMutable.value = true
        return try {
            val expected = contentMutable.value ?: refreshContent()
            check(refreshContent() == expected) { "notice_install_generation_changed" }
            write(expected)
            check(refreshContent() == expected && contentMutable.value == expected) { "notice_install_generation_changed" }
            keepVisibleMutable.value = false
            closeReadOnly()
            UpdateNoticeAckResult.Saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadedMutable.value = true
            keepVisibleMutable.value = true
            retryGenerationMutable.value++
            UpdateNoticeAckResult.Failed(error)
        } finally {
            inProgressMutable.value = false
        }
    }
}
