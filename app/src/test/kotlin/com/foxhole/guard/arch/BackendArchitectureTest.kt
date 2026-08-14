package com.foxhole.guard.arch

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Konsist smoke: proves the parser handles this Kotlin 2.4 codebase before any real rules
// depend on it. Real backend invariants land as this suite grows (phase 2+3 of the
// optimization pass); presentation-layer rules are out of scope while the UI awaits the
// CLI redesign.
class BackendArchitectureTest {
    @Test
    fun `konsist parses the runtime modules`() {
        val declarations =
            Konsist
                .scopeFromDirectory("core/runtime/src/main/kotlin")
                .classes()

        assertTrue(declarations.isNotEmpty())
    }

    // The runtime talks to FoxCore through compiled typed JNI bindings. Reflection
    // on string method names (java.lang.reflect.Proxy, javaClass.methods scans) silently defaults
    // would silently hide an ABI mismatch instead of failing the build.
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
