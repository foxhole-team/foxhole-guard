package com.foxhole.guard.ui

import com.foxhole.guard.core.data.ProfileImportPreview
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileImportConfirmationSecurityGateTest {
    @Test
    fun `subscription cannot be confirmed before its TLS inspection finishes`() {
        val ready = confirmation()

        assertTrue(ready.canConfirm)
        assertFalse(ready.copy(subscriptionInspectionInProgress = true).canConfirm)
        assertFalse(ready.copy(subscriptionInspectionFailed = true).canConfirm)
    }

    @Test
    fun `confirmation UI and actions both enforce the same fail closed gate`() {
        val dialog = projectSource("ui/cli/profiles/CliProfileDialogs.kt")
        val actions = projectSource("ui/HomeViewModelImportActions.kt")
        val flow = projectSource("ui/HomeViewModelProfileImportSupport.kt")

        assertTrue(dialog.contains("enabled = confirmation.canConfirm"))
        assertTrue(actions.contains("if (!pending.canConfirm) return"))
        assertTrue(flow.contains("subscriptionInspectionInProgress = preview.subscription"))
        assertTrue(flow.contains("subscriptionInspectionFailed = true"))
        assertTrue(flow.contains("insecureTlsProtocolLabels = fetched.insecureTlsProtocolLabels"))
    }

    private fun confirmation(): ProfileImportConfirmationState =
        ProfileImportConfirmationState(
            rawInput = "https://subscription.example/config",
            preview = ProfileImportPreview(
                displayName = "test",
                subscription = true,
                protocolHints = emptyList(),
                host = "subscription.example",
                nodesCount = 0,
            ),
        )

    private fun projectSource(relative: String): String =
        sequenceOf(
            java.io.File("src/main/kotlin/com/foxhole/guard/$relative"),
            java.io.File("app/src/main/kotlin/com/foxhole/guard/$relative"),
            java.io.File("../app/src/main/kotlin/com/foxhole/guard/$relative"),
        ).first(java.io.File::exists).readText()
}
