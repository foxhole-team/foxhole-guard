package com.foxhole.guard

import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.core.settings.completeOnboarding
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

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
