package com.foxhole.guard.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileDraftCreationContractTest {
    @Test
    fun `blank template bypasses only the strict import parser`() {
        val source = source("HomeViewModelProfileEditorSupport.kt")
            .substringAfter("internal suspend fun HomeViewModel.createProfileFromTemplate")

        assertTrue(source.contains("createDraftProfile("))
        assertTrue(source.contains("cliBlankOutbound(normalizedType)"))
        assertFalse(source.substringBefore("}.onFailure").contains("importProfile("))
    }

    private fun source(name: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui", name),
            File("app/src/main/kotlin/com/foxhole/guard/ui", name),
            File("../app/src/main/kotlin/com/foxhole/guard/ui", name),
        ).first(File::isFile).readText()
}
