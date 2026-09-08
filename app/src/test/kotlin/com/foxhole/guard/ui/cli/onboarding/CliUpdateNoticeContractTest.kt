package com.foxhole.guard.ui.cli.onboarding

import com.foxhole.core.model.UiSettings
import com.foxhole.guard.core.settings.withUpdateNoticeChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliUpdateNoticeContractTest {
    @Test
    fun `notice keeps three concise current changes in both locales`() {
        listOf("values", "values-ru").forEach { qualifier ->
            val xml = File("src/main/res/$qualifier/strings.xml").readText()
            val text = Regex("""<string name="cli_update_notice_body">(.*?)</string>""")
                .find(xml)!!.groupValues[1].replace("\\n", "\n")
            val items = updateNoticeItems(text)
            assertEquals(3, items.size)
            assertTrue(items.all { it.text.length < 90 })
        }
    }

    @Test
    fun `notice only changes monochrome preference and acknowledgment`() {
        val original = UiSettings(pixelArtEnabled = false, showHomeAdditionalInfo = true)
        assertFalse(original.monochromeEnabled)
        val changed = original.withUpdateNoticeChoices(true, 120)
        assertEquals(original.copy(monochromeEnabled = true, alphaNoticeShownVersionCode = 120), changed)
        assertEquals(original.copy(alphaNoticeShownVersionCode = 120), changed.withUpdateNoticeChoices(false, 120))
    }
}
