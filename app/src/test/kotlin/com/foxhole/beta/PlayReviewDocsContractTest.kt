package com.foxhole.beta

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayReviewDocsContractTest {
    @Test
    fun `play review packet includes required draft files`() {
        requiredDocs.forEach { path ->
            assertTrue("Missing Play review doc: $path", rootFile(path).isFile)
        }
    }

    @Test
    fun `privacy policy draft covers user data policy basics`() {
        val policy = rootFile("docs/play-review/privacy-policy.md").readText()

        assertContainsAll(
            policy,
            listOf(
                "FoxHole Privacy Policy",
                "Privacy contact",
                "FoxHole does not sell user data",
                "Data Stored On The Device",
                "Sensitive Permissions And Access",
                "QUERY_ALL_PACKAGES",
                "PACKAGE_USAGE_STATS",
                "Network Requests",
                "Retention And Deletion",
                "Factory reset FoxHole local data",
            ),
        )
    }

    @Test
    fun `sensitive declarations cover vpn fgs and package visibility`() {
        val vpn = rootFile("docs/play-review/vpn-service-declaration.md").readText()
        val fgs = rootFile("docs/play-review/fgs-declaration.md").readText()
        val queryAll = rootFile("docs/play-review/query-all-packages-declaration.md").readText()

        assertContainsAll(vpn, listOf("VpnService", "Core Functionality", "Prominent Disclosure", "90 seconds"))
        assertContainsAll(fgs, listOf("specialUse", "FoxholeVpnService", "FoxholeProxyService", "User impact"))
        assertContainsAll(
            queryAll,
            listOf("QUERY_ALL_PACKAGES", "Split routing", "DNS exceptions", "Why Narrower Visibility Is Not Enough"),
        )
    }

    @Test
    fun `reviewer steps include local privacy deletion and videos`() {
        val steps = rootFile("docs/play-review/reviewer-test-steps.md").readText()
        val videos = rootFile("docs/play-review/review-video-shot-list.md").readText()
        val dataSafety = rootFile("docs/play-review/data-safety-draft.md").readText()

        assertContainsAll(steps, listOf("No FoxHole account is required", "Usage Access Flow", "Privacy And Deletion"))
        assertContainsAll(videos, listOf("VpnService Video", "Foreground Service Video", "QUERY_ALL_PACKAGES Video"))
        assertContainsAll(dataSafety, listOf("Ads: No", "Sale of user data: No", "Data That Stays Local By Default"))
    }

    private fun assertContainsAll(
        source: String,
        required: List<String>,
    ) {
        required.forEach { value ->
            assertTrue("Expected to find '$value'", source.contains(value))
        }
    }

    private fun rootFile(path: String): File =
        candidateRoots
            .map { root -> File(root, path) }
            .first { file -> file.exists() || file.parentFile?.exists() == true }

    private companion object {
        val candidateRoots =
            listOf(
                File("."),
                File(".."),
            )

        val requiredDocs =
            listOf(
                "docs/play-review/README.md",
                "docs/play-review/privacy-policy.md",
                "docs/play-review/data-safety-draft.md",
                "docs/play-review/permissions-declarations.md",
                "docs/play-review/vpn-service-declaration.md",
                "docs/play-review/fgs-declaration.md",
                "docs/play-review/query-all-packages-declaration.md",
                "docs/play-review/reviewer-test-steps.md",
                "docs/play-review/review-video-shot-list.md",
            )
    }
}
