package com.samsung.agenticgallery.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgenticGalleryMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()
    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = {
            wakeAndUnlock()
            pressHome()
        },
    ) {
        startActivityAndWait()
    }

    @Test
    fun galleryScrollFrames() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = 5,
        setupBlock = {
            wakeAndUnlock()
            pressHome()
        },
    ) {
        startActivityAndWait()
        val gallery = device.wait(Until.findObject(By.text("Gallery")), UI_TIMEOUT_MS)
        assertNotNull("Gallery navigation was unavailable", gallery)
        gallery.click()
        device.waitForIdle()
        val root = device.wait(Until.findObject(By.scrollable(true)), UI_TIMEOUT_MS)
        assertNotNull("Gallery did not expose a scrollable result grid", root)
        root.scroll(Direction.DOWN, 0.8f)
        root.scroll(Direction.UP, 0.8f)
    }

    @Test
    fun fixtureQueryToFirstAnswer() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = 5,
        setupBlock = {
            wakeAndUnlock()
            pressHome()
            startActivityAndWait()
        },
    ) {
        val ask = device.wait(Until.findObject(By.text("Ask")), UI_TIMEOUT_MS)
        assertNotNull("Ask navigation was unavailable", ask)
        ask.click()
        val input = device.wait(Until.findObject(By.desc("Gallery question")), UI_TIMEOUT_MS)
        assertNotNull("Gallery question input was unavailable", input)
        input.text = "Show Amsterdam photos"
        val submit = device.wait(Until.findObject(By.desc("Submit question")), UI_TIMEOUT_MS)
        assertNotNull("Submit question action was unavailable", submit)
        submit.click()
        val answer = device.wait(Until.findObject(By.descContains("Answer")), QUERY_TIMEOUT_MS)
        assertNotNull("Fixture query did not render an answer", answer)
    }

    private fun wakeAndUnlock() {
        device.wakeUp()
        val width = device.displayWidth
        val height = device.displayHeight
        device.swipe(width / 2, (height * 0.85f).toInt(), width / 2, (height * 0.2f).toInt(), 30)
        device.waitForIdle()
    }

    private companion object {
        const val TARGET_PACKAGE = "com.samsung.agenticgallery.benchmark"
        const val UI_TIMEOUT_MS = 10_000L
        const val QUERY_TIMEOUT_MS = 30_000L
    }
}
