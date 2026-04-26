package com.foxhole.beta.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartStartProtocolPresentationTest {
    @Test
    fun `recommended status wins for the recommended healthy candidate`() {
        val presentation =
            resolveSmartStartProtocolPresentation(
                included = true,
                recommended = true,
                latencyMs = 80L,
                latencyDown = false,
                latencyUnavailable = false,
            )

        assertEquals(SmartStartProtocolStatus.RECOMMENDED, presentation.status)
    }

    @Test
    fun `slow status is used for a non recommended candidate with data`() {
        val presentation =
            resolveSmartStartProtocolPresentation(
                included = true,
                recommended = false,
                latencyMs = 220L,
                latencyDown = false,
                latencyUnavailable = false,
            )

        assertEquals(SmartStartProtocolStatus.SLOW, presentation.status)
    }

    @Test
    fun `recently failed status wins over stale recommendation`() {
        val presentation =
            resolveSmartStartProtocolPresentation(
                included = true,
                recommended = true,
                latencyMs = null,
                latencyDown = true,
                latencyUnavailable = false,
            )

        assertEquals(SmartStartProtocolStatus.RECENTLY_FAILED, presentation.status)
    }

    @Test
    fun `no data status is used when no latency exists`() {
        val presentation =
            resolveSmartStartProtocolPresentation(
                included = true,
                recommended = false,
                latencyMs = null,
                latencyDown = false,
                latencyUnavailable = true,
            )

        assertEquals(SmartStartProtocolStatus.NO_DATA, presentation.status)
    }

    @Test
    fun `disabled status wins over recommendation and failure state`() {
        val presentation =
            resolveSmartStartProtocolPresentation(
                included = false,
                recommended = true,
                latencyMs = null,
                latencyDown = true,
                latencyUnavailable = true,
            )

        assertEquals(SmartStartProtocolStatus.DISABLED, presentation.status)
        assertEquals(SmartStartProtocolDisabledReason.MANUAL_OFF, presentation.disabledReason)
    }

    @Test
    fun `dashboard menu keeps header and refresh with compact status rows`() {
        val layout = resolveSmartStartProtocolMenuLayout(showMetricsTable = false)

        assertTrue(layout.showHeader)
        assertFalse(layout.showDetailedMetrics)
        assertTrue(layout.showCompactStatusRows)
    }

    @Test
    fun `profiles menu keeps detailed metrics rows`() {
        val layout = resolveSmartStartProtocolMenuLayout(showMetricsTable = true)

        assertTrue(layout.showHeader)
        assertTrue(layout.showDetailedMetrics)
        assertFalse(layout.showCompactStatusRows)
    }

    @Test
    fun `english and russian copy uses recommended instead of fastest`() {
        val enStrings = resourceText("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
        val ruStrings = resourceText("src/main/res/values-ru/strings.xml", "app/src/main/res/values-ru/strings.xml")

        assertEquals("Recommended", stringValue(enStrings, "smart_profile_menu_recommended_badge"))
        assertEquals("Recommended", stringValue(enStrings, "smart_start_protocol_status_recommended"))
        assertEquals("Рекомендовано", stringValue(ruStrings, "smart_profile_menu_recommended_badge"))
        assertEquals("Рекомендовано", stringValue(ruStrings, "smart_start_protocol_status_recommended"))
        assertFalse(enStrings.contains("Fastest"))
        assertFalse(ruStrings.contains("Fastest"))
    }

    private fun resourceText(vararg candidates: String): String =
        candidates
            .asSequence()
            .map(::File)
            .first { file -> file.isFile }
            .readText()

    private fun stringValue(
        content: String,
        name: String,
    ): String {
        val pattern = Regex("""<string name="$name">([^<]+)</string>""")
        return requireNotNull(pattern.find(content)) { "missing string resource $name" }.groupValues[1]
    }
}
