package com.foxhole.guard.ui

import com.foxhole.core.model.UiSettings
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.core.settings.LEGACY_UPDATE_NOTICE_CONTENT_ID
import com.foxhole.guard.core.settings.normalizedUpdateNoticeAcknowledgement
import com.foxhole.guard.core.settings.withUpdateNoticeChoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class UpdateNoticeStateTest {
    private val legacy = UpdateNoticeContent(LEGACY_UPDATE_NOTICE_CONTENT_ID, 1)

    @Test fun legacyContentDoesNotRepeatAfterLocalBuildChange() {
        val ui = UiSettings(alphaNoticeShownVersionCode = BuildConfig.VERSION_CODE - 1)
        assertFalse(shouldShowUpdateNotice(ui, legacy))
    }

    @Test fun legacyContentDoesNotRepeatOnDowngrade() {
        val ui = UiSettings(alphaNoticeShownVersionCode = BuildConfig.VERSION_CODE + 1)
        assertFalse(shouldShowUpdateNotice(ui, legacy))
    }

    @Test fun oldMarkerPreservesLegacyAndDoesNotAcknowledgeNewContent() {
        val original = UiSettings(alphaNoticeShownVersionCode = 42, monochromeEnabled = true)
        val migrated = original.normalizedUpdateNoticeAcknowledgement()
        assertEquals(42, migrated.alphaNoticeShownVersionCode)
        assertEquals(LEGACY_UPDATE_NOTICE_CONTENT_ID, migrated.updateNoticeAcknowledgedContentId)
        assertEquals(1L, migrated.updateNoticeAcknowledgedRevision)
        assertTrue(migrated.monochromeEnabled)
        assertTrue(shouldShowUpdateNotice(migrated, CURRENT_UPDATE_NOTICE))
    }

    @Test fun newInstallShowsCurrentNoticeOnceAcknowledged() {
        val fresh = UiSettings()
        assertTrue(shouldShowUpdateNotice(fresh, CURRENT_UPDATE_NOTICE))
        val ack = fresh.withUpdateNoticeChoices(false, CURRENT_UPDATE_NOTICE.contentId, CURRENT_UPDATE_NOTICE.revision)
        assertFalse(shouldShowUpdateNotice(ack, CURRENT_UPDATE_NOTICE))
    }

    @Test fun futureAcknowledgementSuppressesOlderContentAndCannotBeOverwritten() {
        val future =
            UiSettings(updateNoticeAcknowledgedContentId = "foxhole.future", updateNoticeAcknowledgedRevision = 10)
        assertFalse(shouldShowUpdateNotice(future, CURRENT_UPDATE_NOTICE))
        assertEquals(
            future.copy(monochromeEnabled = true),
            future.withUpdateNoticeChoices(true, CURRENT_UPDATE_NOTICE.contentId, CURRENT_UPDATE_NOTICE.revision)
        )
    }

    @Test fun sameRevisionDifferentContentRequiresAcknowledgement() {
        val ui =
            UiSettings(
                updateNoticeAcknowledgedContentId = "foxhole.other",
                updateNoticeAcknowledgedRevision = CURRENT_UPDATE_NOTICE.revision
            )
        assertTrue(shouldShowUpdateNotice(ui, CURRENT_UPDATE_NOTICE))
    }

    @Test fun malformedPairCannotSilentlyAcknowledgeContent() {
        val ui = UiSettings(updateNoticeAcknowledgedContentId = " ", updateNoticeAcknowledgedRevision = 100)
        assertTrue(shouldShowUpdateNotice(ui, CURRENT_UPDATE_NOTICE))
        assertEquals(0L, ui.normalizedUpdateNoticeAcknowledgement().updateNoticeAcknowledgedRevision)
    }

    @Test fun choicePreservesEveryOtherPreference() {
        val original =
            UiSettings(pixelArtEnabled = false, showHomeAdditionalInfo = true, alphaNoticeShownVersionCode = 77)
        val updated = original.withUpdateNoticeChoices(
            true,
            CURRENT_UPDATE_NOTICE.contentId,
            CURRENT_UPDATE_NOTICE.revision
        )
        assertEquals(
            original.copy(
                monochromeEnabled = true,
                updateNoticeAcknowledgedContentId = CURRENT_UPDATE_NOTICE.contentId,
                updateNoticeAcknowledgedRevision = CURRENT_UPDATE_NOTICE.revision
            ),
            updated
        )
    }

    @Test fun ordinaryPersistFailureIsReportedWithoutAckAndWithoutAutomaticRetry() = runBlocking {
        val state = UpdateNoticeState()
        var writes = 0
        val error = IOException("owned write failure")
        val result = state.acknowledge {
            writes++
            throw error
        }
        assertSame(error, (result as UpdateNoticeAckResult.Failed).error)
        assertEquals(1, writes)
        assertEquals(1, state.retryGeneration.value)
        assertFalse(state.inProgress.value)
    }

    @Test fun acknowledgementIsNotSuccessfulUntilWriteReturns() = runBlocking {
        val state = UpdateNoticeState()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempt = async {
            state.acknowledge {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        assertTrue(state.inProgress.value)
        assertFalse(attempt.isCompleted)
        assertEquals(UpdateNoticeAckResult.AlreadyPending, state.acknowledge { fail("duplicate write") })
        release.complete(Unit)
        assertEquals(UpdateNoticeAckResult.Saved, attempt.await())
        assertFalse(state.inProgress.value)
    }

    @Test fun cancellationPropagatesWithoutAckOrRetryStorm() = runBlocking {
        val state = UpdateNoticeState()
        val error = CancellationException("owned cancel")
        val thrown = try {
            state.acknowledge { throw error }
            null
        } catch (e: Throwable) { e }
        assertSame(error, thrown)
        assertEquals(0, state.retryGeneration.value)
        assertFalse(state.inProgress.value)
    }

    @Test fun fatalPropagatesWithoutAckOrRetryStorm() = runBlocking {
        val state = UpdateNoticeState()
        val error = AssertionError("owned fatal")
        val thrown = try {
            state.acknowledge { throw error }
            null
        } catch (e: Throwable) { e }
        assertSame(error, thrown)
        assertEquals(0, state.retryGeneration.value)
        assertFalse(state.inProgress.value)
    }

    @Test fun aboutRereadHasNoPersistenceAndDoesNotResetAcknowledgement() {
        val state = UpdateNoticeState()
        val ui = UiSettings().withUpdateNoticeChoices(
            true,
            CURRENT_UPDATE_NOTICE.contentId,
            CURRENT_UPDATE_NOTICE.revision
        )
        state.openForReading()
        assertTrue(state.readOnlyVisible.value)
        state.closeReadOnly()
        assertFalse(state.readOnlyVisible.value)
        assertFalse(shouldShowUpdateNotice(ui, CURRENT_UPDATE_NOTICE))
        assertTrue(ui.monochromeEnabled)
    }
}
