package com.foxhole.guard.ui

import com.foxhole.core.model.UiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNoticeContentRevision4Test {
    @Test fun updatedReleaseCopyIsShownOnceBeyondRevisionSix() {
        assertEquals(7L, CURRENT_UPDATE_NOTICE.revision)
        val previous = UiSettings(
            updateNoticeAcknowledgedContentId = CURRENT_UPDATE_NOTICE.contentId,
            updateNoticeAcknowledgedRevision = 6,
        )
        assertTrue(shouldShowUpdateNotice(previous, CURRENT_UPDATE_NOTICE))
        val acknowledged = previous.copy(updateNoticeAcknowledgedRevision = CURRENT_UPDATE_NOTICE.revision)
        assertFalse(shouldShowUpdateNotice(acknowledged, CURRENT_UPDATE_NOTICE))
        val technicalBuildChange = acknowledged.copy(alphaNoticeShownVersionCode = Int.MAX_VALUE)
        assertFalse(shouldShowUpdateNotice(technicalBuildChange, CURRENT_UPDATE_NOTICE))
    }
}
