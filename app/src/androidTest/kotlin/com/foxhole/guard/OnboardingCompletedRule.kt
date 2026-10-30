package com.foxhole.guard

import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.InterfaceMode
import com.foxhole.guard.core.settings.completeOnboarding
import com.foxhole.guard.core.settings.updateInterfaceMode
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

fun onboardingCompletedRule(interfaceMode: InterfaceMode = InterfaceMode.EXPERT): TestRule =
    TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val application =
                    InstrumentationRegistry
                        .getInstrumentation()
                        .targetContext
                        .applicationContext as FoxholeApplication
                runBlocking {
                    application.appGraph.settingsRepository.completeOnboarding()
                    application.appGraph.settingsRepository.updateInterfaceMode(interfaceMode)
                }
                base.evaluate()
            }
        }
    }
