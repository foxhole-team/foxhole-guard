package com.foxhole.guard.core.webapps

import com.foxhole.core.runtime.network.HttpProxyAccess
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class WebAppSecurityPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `only an icon inside the managed directory is accepted`() {
        val filesDir = temporaryFolder.newFolder("files")
        val icons = java.io.File(filesDir, WEB_APP_ICON_DIR).apply { mkdirs() }
        java.io.File(icons, "7.img").writeBytes(byteArrayOf(1))
        val outside = temporaryFolder.newFile("outside.img")

        assertNotNull(resolveWebAppIconFile(filesDir, "$WEB_APP_ICON_DIR/7.img"))
        assertNull(resolveWebAppIconFile(filesDir, outside.absolutePath))
        assertNull(resolveWebAppIconFile(filesDir, "$WEB_APP_ICON_DIR/../../outside.img"))
    }

    @Test
    fun `proxied pages block service worker network while direct pages do not`() {
        assertFalse(WebAppProxyPlan.Direct.mustBlockServiceWorkerNetwork())
        assertTrue(
            WebAppProxyPlan.Http(
                HttpProxyAccess(host = "127.0.0.1", port = 8080, username = "u", password = "p"),
            ).mustBlockServiceWorkerNetwork(),
        )
    }
}
