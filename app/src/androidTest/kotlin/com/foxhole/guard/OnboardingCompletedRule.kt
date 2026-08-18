package com.foxhole.guard

import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.core.settings.completeOnboarding
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/**
 * The connected-test runner reinstalls per spec, so a fresh profile opens the first-run wizard and any test waiting for a normal screen waits forever.
 * Chain outside the compose rule (RuleChain.outerRule(this).around(compose)): the compose rule launches the activity as it evaluates, so a @Before is too late.
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
