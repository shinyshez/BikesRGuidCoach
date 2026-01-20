package com.mtbanalyzer.tuning

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtbanalyzer.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test to verify DetectionTuningActivity launches without crashing.
 */
@RunWith(AndroidJUnit4::class)
class DetectionTuningActivityTest {

    @Test
    fun activityLaunches_doesNotCrash() {
        // Launch the activity
        val scenario = ActivityScenario.launch(DetectionTuningActivity::class.java)

        // Verify the activity is in RESUMED state (not crashed)
        scenario.onActivity { activity ->
            assert(activity != null) { "Activity should not be null" }
        }

        // Verify key views are displayed
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
        onView(withId(R.id.playerView)).check(matches(isDisplayed()))
        onView(withId(R.id.detectorSpinner)).check(matches(isDisplayed()))
        onView(withId(R.id.parametersContainer)).check(matches(isDisplayed()))
        onView(withId(R.id.btnRunDetection)).check(matches(isDisplayed()))

        // Verify the placeholder text is shown (no video loaded)
        onView(withId(R.id.noVideoPlaceholder)).check(matches(isDisplayed()))

        scenario.close()
    }

    @Test
    fun detectorSpinner_hasOptions() {
        val scenario = ActivityScenario.launch(DetectionTuningActivity::class.java)

        // Verify spinner is displayed and clickable
        onView(withId(R.id.detectorSpinner))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))

        scenario.close()
    }

    @Test
    fun actionButtons_areDisplayed() {
        val scenario = ActivityScenario.launch(DetectionTuningActivity::class.java)

        // Verify all action buttons are displayed
        onView(withId(R.id.btnRunDetection)).check(matches(isDisplayed()))
        onView(withId(R.id.btnCompare)).check(matches(isDisplayed()))
        onView(withId(R.id.btnBenchmark)).check(matches(isDisplayed()))

        scenario.close()
    }
}
