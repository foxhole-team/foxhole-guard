package com.foxhole.guard

import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.core.settings.completeOnboarding
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/**
 * Marks first-run onboarding as done, before the activity under test is created.
 *
 * The connected-test runner reinstalls the app for every spec, so each UI test starts on a
 * genuinely fresh profile — and a fresh profile opens the first-run wizard, not the dock. Any test
 * that waits for a normal screen therefore waits forever, which is exactly how two suites here
 * failed with nothing wrong in the product.
 *
 * Ordering matters: chain this OUTSIDE the compose rule (`RuleChain.outerRule(this).around(compose)`),
 * because the compose rule launches the activity as it evaluates and a `@Before` would be too late.
 *
 * Deliberately a settings write rather than three clicks through the wizard: driving its screens
 * would make every unrelated UI test depend on the wizard's copy and layout, so a change there
 * would break suites that have nothing to do with it. The wizard has its own tests.
 */
fun onboardingCompletedRule(): TestRule =
    TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val application =
                    InstrumentationRegistry
                        .getInstrumentation()
                        .targetContext
                        .applicationContext as FoxholeApplication
                runBlocking { application.appGraph.settingsRepository.completeOnboarding() }
                base.evaluate()
            }
        }
    }
