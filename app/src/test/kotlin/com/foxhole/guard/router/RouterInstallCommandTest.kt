package com.foxhole.guard.router

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterInstallCommandTest {
    private val release = "https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.0"
    private val hash = "a".repeat(64)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun scriptBytesMustMatchTheAuthenticatedReleaseAndStayImmutable() {
        val bytes = "#!/bin/sh\nexit 0\n".toByteArray()
        val verified = RouterVerifiedInstallScript(bytes, sha(bytes))
        bytes.fill(0)
        val copy = verified.bytes()
        copy.fill(0)
        assertEquals("#!/bin/sh\nexit 0\n", verified.bytes().toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { RouterVerifiedInstallScript(bytes, hash) }
    }

    @Test fun scriptMustBeBoundedUtf8Text() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0xc0.toByte()), ByteArray(1048577))) {
            assertThrows(Exception::class.java) { RouterVerifiedInstallScript(bytes, sha(bytes)) }
        }
    }

    @Test fun hysteriaOnlyAndStorageModeAreExplicit() {
        val command = RouterInstallCommand(release, hash, RouterStorageMode.RAM, setOf(RouterInstallProtocol.HYSTERIA2), licenseAccepted = true).command
        assertTrue(command.startsWith("sh -s -- "))
        assertTrue(command.contains("'--ram' '--hy2' '--apply'"))
        assertFalse(command.contains("--all"))
        assertFalse(command.contains("--development"))
    }

    @Test fun selectionOrderDoesNotChangeTheInstallationCommand() {
        val first = linkedSetOf(RouterInstallProtocol.WIREGUARD, RouterInstallProtocol.VLESS)
        val second = linkedSetOf(RouterInstallProtocol.VLESS, RouterInstallProtocol.WIREGUARD)
        assertEquals(RouterInstallCommand(release, hash, RouterStorageMode.FLASH, first, licenseAccepted = true).command,
            RouterInstallCommand(release, hash, RouterStorageMode.FLASH, second, licenseAccepted = true).command)
    }

    @Test fun arbitraryHostsPathsAndShellInputCannotBecomeAnInstallationCommand() {
        for (url in listOf("http://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.0",
            "https://github.com.evil.example/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.0",
            "$release?token=fixture", "$release#ignored", "$release/../../../evil", "$release';touch /tmp/unowned")) {
            assertThrows(IllegalArgumentException::class.java) {
                RouterInstallCommand(url, hash, RouterStorageMode.FLASH, setOf(RouterInstallProtocol.HYSTERIA2), licenseAccepted = true)
            }
        }
    }

    @Test fun typedDefaultSourcePreservesTheExactStrictGithubCommand() {
        val protocols = setOf(RouterInstallProtocol.HYSTERIA2)
        val expected = "sh -s -- '--base-url' '$release' '--manifest-sha256' '$hash' '--flash' '--hy2' '--apply' '--accept-license'"
        assertEquals(expected, RouterInstallCommand(release, hash, RouterStorageMode.FLASH, protocols, licenseAccepted = true).command)
        assertEquals(expected, RouterInstallCommand(RouterInstallCommandSource.github(release + "/"), hash, RouterStorageMode.FLASH, protocols, licenseAccepted = true).command)
        assertThrows(IllegalArgumentException::class.java) { RouterInstallCommandSource.github("https://example.com/releases/v1.0.0") }
    }

    @Test fun sourceSelectionCannotChangeManifestOrProtocolAdmission() {
        val source = RouterInstallCommandSource.github(release)
        assertThrows(IllegalArgumentException::class.java) { RouterInstallCommand(source, "not-a-digest", RouterStorageMode.FLASH, setOf(RouterInstallProtocol.HYSTERIA2), licenseAccepted = true) }
        assertThrows(IllegalArgumentException::class.java) { RouterInstallCommand(source, hash, RouterStorageMode.FLASH, emptySet(), licenseAccepted = true) }
    }
    @Test fun firstSetupCannotRenderAnApplyCommandWithoutExplicitLicenseAcceptance() {
        assertThrows(RouterLicenseRequired::class.java) {
            RouterInstallCommand(release, hash, RouterStorageMode.FLASH, setOf(RouterInstallProtocol.HYSTERIA2))
        }
    }

    @Test fun acceptedSetupCarriesTheExplicitInstallerFlag() {
        val command = RouterInstallCommand(release, hash, RouterStorageMode.FLASH,
            setOf(RouterInstallProtocol.HYSTERIA2), licenseAccepted = true).command
        assertTrue(command.endsWith("'--apply' '--accept-license'"))
    }

}
