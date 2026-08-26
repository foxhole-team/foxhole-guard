package com.foxhole.guard.arch

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLifecycleSafetyArchTest {
    @Test
    fun `permission revoke invalidates the runtime transition before preempting at kill priority`() {
        val onRevoke =
            appMainScope()
                .classes()
                .single { declaration -> declaration.name == "FoxholeVpnService" }
                .functions(includeNested = false, includeLocal = false)
                .single { function -> function.name == "onRevoke" }
                .text

        val transitionIndex = indexOfPattern(onRevoke, Regex("""begin(Runtime)?Transition\s*\("""))
        val killIndex = indexOfPattern(onRevoke, Regex("""RuntimeCommandPriority\.KILL"""))

        assertTrue(
            "onRevoke must invalidate the in-flight runtime transition (beginTransition) — " +
                "permission loss may never leave queued START/SWITCH work believing its " +
                "transition is still current.\n$onRevoke",
            transitionIndex >= 0,
        )
        assertTrue(
            "onRevoke must tear down at RuntimeCommandPriority.KILL so it preempts queued " +
                "start/switch commands.\n$onRevoke",
            killIndex >= 0,
        )
        assertTrue(
            "onRevoke must invalidate the transition BEFORE scheduling the KILL-priority " +
                "teardown.\n$onRevoke",
            transitionIndex < killIndex,
        )
    }

    @Test
    fun `runtime wake locks are owned by android services not global registries`() {
        val scope = appMainScope()
        val wakeLockConstruction = Regex("""\bRuntimeWakeLock\s*\(""")

        val topLevelOffenders =
            scope
                .properties(includeNested = false)
                .filter { property -> property.text.contains(wakeLockConstruction) }
                .map { property -> property.location }
        assertEquals(
            "Runtime wake locks must not live in top-level state; they belong to the service " +
                "instance whose lifecycle bounds them.",
            emptyList<String>(),
            topLevelOffenders,
        )

        val owningClasses =
            scope
                .classes()
                .filter { declaration ->
                    declaration
                        .properties(includeNested = false)
                        .any { property -> property.text.contains(wakeLockConstruction) }
                }
        assertTrue(
            "Expected at least one service-owned RuntimeWakeLock property; if wake-lock " +
                "ownership moved elsewhere on purpose, update this rule with the new owner.",
            owningClasses.isNotEmpty(),
        )
        val nonServiceOwners =
            owningClasses
                .filterNot { declaration ->
                    declaration.parents(indirectParents = false).any { parent -> parent.name.endsWith("Service") }
                }.map { declaration -> declaration.name }
        assertEquals(
            "Runtime wake locks may only be constructed by Android service classes.",
            emptyList<String>(),
            nonServiceOwners,
        )

        val registryOffenders =
            scope
                .files
                .filter { file -> file.text.contains(Regex("""WeakHashMap<[^>]*RuntimeWakeLock""")) }
                .map { file -> file.name }
        assertEquals(
            "A wake-lock registry keyed by the service re-creates the historic self-retaining " +
                "leak; keep wake locks as plain service-owned properties.",
            emptyList<String>(),
            registryOffenders,
        )
    }

    private fun appMainScope() = Konsist.scopeFromDirectory("app/src/main/kotlin")

    private fun indexOfPattern(
        text: String,
        pattern: Regex,
    ): Int = pattern.find(text)?.range?.first ?: -1
}
