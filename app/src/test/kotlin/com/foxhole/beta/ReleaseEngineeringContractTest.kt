package com.foxhole.beta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseEngineeringContractTest {
    @Test
    fun `public release build type cannot enable release probe diagnostics`() {
        val source = projectFile("build.gradle.kts").readText()
        val releaseBlock =
            source
                .substringAfter("release {")
                .substringBefore("create(\"internalRelease\")")

        assertTrue(source.contains("create(\"internalRelease\")"))
        assertTrue(source.contains("create(\"publicRelease\")"))
        assertTrue(source.contains("val verifyPublicReleasePrivacy by tasks.registering"))
        assertTrue(source.contains("val publicReleasePreflight by tasks.registering"))
        assertTrue(source.contains("foxhole.releaseProbe may only be used with internalRelease"))
        assertTrue(releaseBlock.contains("buildConfigField(\"boolean\", \"ENABLE_DIAGNOSTIC_LOGCAT\", \"false\")"))
        assertFalse(releaseBlock.contains("enableReleaseProbe"))
    }

    @Test
    fun `public release preflight verifies signing bundle sbom and native inventory`() {
        val source = projectFile("build.gradle.kts").readText()

        assertTrue(source.contains("releaseSigningReady"))
        assertTrue(source.contains("outputs/bundle/publicRelease"))
        assertTrue(source.contains("outputs/mapping/publicRelease/mapping.txt"))
        assertTrue(source.contains("collectPublicReleaseNativeSymbols"))
        assertTrue(source.contains("publicRelease-native-symbols.zip"))
        assertTrue(source.contains("val verifyReleaseSbom by tasks.registering"))
        assertTrue(source.contains("cyclonedxBom"))
        assertTrue(source.contains("val verifyPublicReleaseNativeInventory by tasks.registering"))
        assertTrue(source.contains("libbox.so"))
        assertTrue(source.contains("libTor.so"))
        assertTrue(source.contains("liblyrebird.so"))
        assertTrue(source.contains("libconjure_client.so"))
        assertTrue(source.contains("base/assets/"))
        assertTrue(source.contains("prepareFilteredMainAssets"))
        assertTrue(source.contains("tor/**/tor/libTor.so"))
    }

    @Test
    fun `about screen lists release-critical component licenses`() {
        val screen = projectFile("src/main/kotlin/com/foxhole/beta/ui/SettingsAboutDialog.kt").readText()
        val enStrings = projectFile("src/main/res/values/strings.xml").readText()
        val requiredLicenseStrings =
            listOf(
                "about_license_sing_box",
                "about_license_tor",
                "about_license_lyrebird",
                "about_license_conjure_client",
                "about_license_sqlcipher",
                "about_license_okhttp",
                "about_license_androidx_compose",
                "about_license_zxing",
                "about_license_blurview",
            )

        requiredLicenseStrings.forEach { stringName ->
            assertTrue(screen.contains("R.string.$stringName"))
            assertTrue(enStrings.contains("name=\"$stringName\""))
        }
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File("app/$path"), File("../app/$path"))
            .first { file -> file.exists() }
}
