package com.foxhole.guard.ui.cli

import android.os.SystemClock
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.onboardingCompletedRule
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.settings.rememberCliAboutFoxFrame
import com.foxhole.guard.widget.FOX_STATUS_FRAME_DURATION_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.util.concurrent.atomic.AtomicReference

class CliRouteVisibilityLifecycleAndroidTest {
    private val compose = createAndroidComposeRule<CliMainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ownedDebugTargetRule()).around(onboardingCompletedRule()).around(compose)

    @Test
    fun stoppedHomeReleasesUiPollingAndRestoresVisibilityOnResume() {
        lateinit var viewModel: HomeViewModel
        compose.runOnUiThread { viewModel = ViewModelProvider(compose.activity)[HomeViewModel::class.java] }
        fun flags(): List<Boolean> {
            lateinit var result: List<Boolean>
            compose.runOnUiThread {
                result = listOf(viewModel.dashboardVisible, viewModel.profilesUiVisible, viewModel.trafficUiVisible, viewModel.statisticsVisible)
            }
            return result
        }
        val visible = listOf(true, false, true, false)
        compose.waitUntil(10000) { flags() == visible }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(listOf(false, false, false, false), flags())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10000) { flags() == visible }
    }
}

class CliAboutMotionLifecycleAndroidTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ownedDebugTargetRule()).around(compose)

    @Test
    fun aboutFramesStopWithTheVisibleLifecycleAndResumeWithoutRemounting() {
        lateinit var owner: FrontTestLifecycleOwner
        compose.runOnUiThread { owner = FrontTestLifecycleOwner().apply { registry.currentState = Lifecycle.State.STARTED } }
        val frame = AtomicReference<State<Int>>()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val current = rememberCliAboutFoxFrame()
                SideEffect { frame.set(current) }
            }
        }
        compose.waitUntil { frame.get() != null }
        val initial = frame.get().value
        compose.waitUntil(2500) { frame.get().value != initial }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        val stopped = frame.get().value
        SystemClock.sleep(FOX_STATUS_FRAME_DURATION_MS * 3)
        assertEquals(stopped, frame.get().value)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitUntil(2500) { frame.get().value != stopped }
        assertNotEquals(stopped, frame.get().value)
    }

    @Test
    fun disposedAboutContentCannotKeepItsFrameTimerAlive() {
        lateinit var owner: FrontTestLifecycleOwner
        compose.runOnUiThread { owner = FrontTestLifecycleOwner().apply { registry.currentState = Lifecycle.State.STARTED } }
        val mounted = mutableStateOf(true)
        val frame = AtomicReference<State<Int>>()
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val current = rememberCliAboutFoxFrame()
                SideEffect { frame.set(current) }
            }
        }
        compose.waitUntil { frame.get() != null }
        val initial = frame.get().value
        compose.waitUntil(2500) { frame.get().value != initial }
        compose.runOnIdle { mounted.value = false }
        val disposed = frame.get().value
        SystemClock.sleep(FOX_STATUS_FRAME_DURATION_MS * 3)
        assertEquals(disposed, frame.get().value)
    }
}

private class FrontTestLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
}

private fun ownedDebugTargetRule(): TestRule = TestRule { base, _ ->
    object : Statement() {
        override fun evaluate() {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            assertEquals("com.foxhole.guard.debug", target.packageName)
            assertEquals("debug", BuildConfig.BUILD_TYPE)
            assertTrue(target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            base.evaluate()
        }
    }
}
