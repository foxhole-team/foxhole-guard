package com.foxhole.beta.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startup() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = SHORT_ITERATIONS,
            startupMode = StartupMode.COLD,
        ) {
            pressHome()
            startActivityAndWait()
        }

    @Test
    fun homeScroll() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = SHORT_ITERATIONS,
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait()
            },
        ) {
            val list =
                device.wait(
                    Until.findObject(By.pkg(PACKAGE_NAME).scrollable(true)),
                    WAIT_TIMEOUT_MS,
                )
                    ?: error("Home dashboard scroll container was not found for $PACKAGE_NAME")
            list.fling(Direction.DOWN)
            device.waitForIdle()
            list.fling(Direction.UP)
            device.waitForIdle()
        }

    private val device: UiDevice
        get() = UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())

    private companion object {
        private const val PACKAGE_NAME = "com.foxhole.beta"
        private const val SHORT_ITERATIONS = 3
        private const val WAIT_TIMEOUT_MS = 5_000L
        private val BENCHMARK_COMPILATION_MODE =
            CompilationMode.Partial(
                baselineProfileMode = BaselineProfileMode.Disable,
                warmupIterations = 1,
            )
    }
}
