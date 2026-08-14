package com.foxhole.guard.core.webapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Сайт для нативной очистки данных: хост конфигурного https-URL и ничего кроме него — мусор и
 * не-https не превращаются в очистку чужого сайта.
 */
internal class WebAppsDataCleanerTest {
    @Test
    fun `https url yields its host`() {
        assertEquals("mail.example.com", webAppClearSite("https://mail.example.com/inbox"))
    }

    @Test
    fun `port does not change the site`() {
        assertEquals("example.com", webAppClearSite("https://example.com:8443/"))
    }

    @Test
    fun `plain http is refused`() {
        assertNull(webAppClearSite("http://example.com/"))
    }

    @Test
    fun `garbage is refused`() {
        assertNull(webAppClearSite("not a url"))
    }
}
