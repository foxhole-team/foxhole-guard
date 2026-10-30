package com.foxhole.guard.core.backup

import com.foxhole.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupUpdateNoticeAcknowledgementTest {
    @Test
    fun `portable export clears notice acknowledgement without changing appearance`() {
        val current = Settings().let { state ->
            state.copy(
                ui = state.ui.copy(
                    alphaNoticeShownVersionCode = 139,
                    updateNoticeAcknowledgedContentId = "owned-device-notes",
                    updateNoticeAcknowledgedRevision = 7L,
                    monochromeEnabled = true,
                )
            )
        }
        val sanitized = current.sanitizedForBackup()
        assertEquals(0, sanitized.ui.alphaNoticeShownVersionCode)
        assertEquals("", sanitized.ui.updateNoticeAcknowledgedContentId)
        assertEquals(0L, sanitized.ui.updateNoticeAcknowledgedRevision)
        assertTrue(sanitized.ui.monochromeEnabled)
        val encoded = encodeBackupDocument(BackupDocument(settings = current))
        assertFalse(encoded.contains("owned-device-notes"))
    }

    @Test
    fun `restore preserves current device notice identity revision and legacy marker`() {
        val current = Settings().let { state ->
            state.copy(
                ui = state.ui.copy(
                    alphaNoticeShownVersionCode = 139,
                    updateNoticeAcknowledgedContentId = "current-device-notes",
                    updateNoticeAcknowledgedRevision = 7L,
                )
            )
        }
        val incoming = Settings().let { state ->
            state.copy(
                ui = state.ui.copy(
                    alphaNoticeShownVersionCode = 999,
                    updateNoticeAcknowledgedContentId = "future-import-notes",
                    updateNoticeAcknowledgedRevision = Long.MAX_VALUE,
                    monochromeEnabled = true,
                )
            )
        }
        val restored = mergeRestoredSettings(incoming, current)
        assertEquals(current.ui.alphaNoticeShownVersionCode, restored.ui.alphaNoticeShownVersionCode)
        assertEquals(current.ui.updateNoticeAcknowledgedContentId, restored.ui.updateNoticeAcknowledgedContentId)
        assertEquals(current.ui.updateNoticeAcknowledgedRevision, restored.ui.updateNoticeAcknowledgedRevision)
        assertTrue(restored.ui.monochromeEnabled)
    }

    @Test
    fun `incoming future notice cannot acknowledge new notes on an unacknowledged device`() {
        val incoming = Settings().let { state ->
            state.copy(
                ui = state.ui.copy(
                    updateNoticeAcknowledgedContentId = "future-import-notes",
                    updateNoticeAcknowledgedRevision = Long.MAX_VALUE,
                )
            )
        }
        val restored = mergeRestoredSettings(incoming, Settings())
        assertEquals("", restored.ui.updateNoticeAcknowledgedContentId)
        assertEquals(0L, restored.ui.updateNoticeAcknowledgedRevision)
    }
}
