package com.foxhole.guard.arch

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendArchitectureTest {
    @Test
    fun `konsist parses the runtime modules`() {
        val declarations =
            Konsist
                .scopeFromDirectory("core/runtime/src/main/kotlin")
                .classes()

        assertTrue(declarations.isNotEmpty())
    }

    @Test
    fun `runtime main sources never dispatch FoxCore through reflection`() {
        val offenders =
            Konsist
                .scopeFromDirectory("core/runtime/src/main/kotlin")
                .files
                .filter { file ->
                    file.text.contains("java.lang.reflect.Proxy") ||
                        file.text.contains("javaClass.methods")
                }.map { file -> file.name }

        assertEquals(
            "FoxCore dispatch must stay typed; reflective dispatch found in:",
            emptyList<String>(),
            offenders,
        )
    }
}
