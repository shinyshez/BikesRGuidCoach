package com.mtbanalyzer.tuning

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtbanalyzer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented test to verify DetectionTuningActivity launches without crashing.
 */
@RunWith(AndroidJUnit4::class)
class DetectionTuningActivityTest {

    companion object {
        private const val TAG = "DetectionTuningTest"
    }

    @Test
    fun activityLaunches_staysInResumedState() {
        Log.d(TAG, "Starting test: activityLaunches_staysInResumedState")

        val scenario = ActivityScenario.launch(DetectionTuningActivity::class.java)

        // Wait a moment for any async initialization
        Thread.sleep(1000)

        // Check the lifecycle state
        val state = scenario.state
        Log.d(TAG, "Activity state after launch: $state")

        // The activity should NOT be destroyed
        assertNotEquals(
            "Activity should not be destroyed immediately after launch",
            Lifecycle.State.DESTROYED,
            state
        )

        // The activity should be at least STARTED
        assertEquals(
            "Activity should be in RESUMED state",
            Lifecycle.State.RESUMED,
            state
        )

        Log.d(TAG, "Activity is in RESUMED state, test passed")
        scenario.close()
    }

    @Test
    fun activityLaunches_viewsAreDisplayed() {
        Log.d(TAG, "Starting test: activityLaunches_viewsAreDisplayed")

        val scenario = ActivityScenario.launch(DetectionTuningActivity::class.java)

        // Wait for activity to fully initialize
        Thread.sleep(2000)

        // Check state first
        val state = scenario.state
        Log.d(TAG, "Activity state: $state")

        if (state == Lifecycle.State.DESTROYED) {
            Log.e(TAG, "Activity was destroyed - cannot check views")
            throw AssertionError("Activity was destroyed immediately after launch")
        }

        // Verify key views are displayed
        Log.d(TAG, "Checking toolbar...")
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()))

        Log.d(TAG, "Checking playerView...")
        onView(withId(R.id.playerView)).check(matches(isDisplayed()))

        Log.d(TAG, "Checking detectorSpinner...")
        onView(withId(R.id.detectorSpinner)).check(matches(isDisplayed()))

        Log.d(TAG, "Checking parametersContainer...")
        onView(withId(R.id.parametersContainer)).check(matches(isDisplayed()))

        Log.d(TAG, "Checking btnRunDetection...")
        onView(withId(R.id.btnRunDetection)).check(matches(isDisplayed()))

        Log.d(TAG, "Checking noVideoPlaceholder...")
        onView(withId(R.id.noVideoPlaceholder)).check(matches(isDisplayed()))

        Log.d(TAG, "All views verified, test passed")
        scenario.close()
    }

    @Test
    fun detectorSpinner_isClickable() {
        Log.d(TAG, "Starting test: detectorSpinner_isClickable")

        val scenario = ActivityScenario.launch(DetectionTuningActivity::class.java)
        Thread.sleep(1500)

        val state = scenario.state
        if (state == Lifecycle.State.DESTROYED) {
            throw AssertionError("Activity was destroyed immediately after launch")
        }

        onView(withId(R.id.detectorSpinner))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))

        Log.d(TAG, "Spinner is clickable, test passed")
        scenario.close()
    }

    @Test
    fun actionButtons_areDisplayed() {
        Log.d(TAG, "Starting test: actionButtons_areDisplayed")

        val scenario = ActivityScenario.launch(DetectionTuningActivity::class.java)
        Thread.sleep(1500)

        val state = scenario.state
        if (state == Lifecycle.State.DESTROYED) {
            throw AssertionError("Activity was destroyed immediately after launch")
        }

        onView(withId(R.id.btnRunDetection)).check(matches(isDisplayed()))
        onView(withId(R.id.btnCompare)).check(matches(isDisplayed()))
        onView(withId(R.id.btnBenchmark)).check(matches(isDisplayed()))

        Log.d(TAG, "All action buttons displayed, test passed")
        scenario.close()
    }
}
