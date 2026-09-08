package com.mtbanalyzer


import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the decluttered playback screen: the activity launches with a bundled
 * clip, the new chrome is present, and the frame badge is only shown while paused.
 */
@RunWith(AndroidJUnit4::class)
class VideoPlaybackActivityTest {

    private fun launch(): ActivityScenario<VideoPlaybackActivity> {
        // ExoPlayer only resolves android.resource:// for the app's own package, so copy the
        // test package's clip into the app cache and hand over a file URI instead.
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clip = java.io.File(appContext.cacheDir, "test_clip.mp4")
        testContext.resources.openRawResource(com.mtbanalyzer.test.R.raw.test_clip).use { input ->
            clip.outputStream().use { input.copyTo(it) }
        }
        val uri = Uri.fromFile(clip)
        val intent = Intent(ApplicationProvider.getApplicationContext(), VideoPlaybackActivity::class.java)
            .putExtra(VideoPlaybackActivity.EXTRA_VIDEO_URI, uri.toString())
            .putExtra(VideoPlaybackActivity.EXTRA_VIDEO_NAME, "MTB_test_clip.mp4")
        return ActivityScenario.launch(intent)
    }

    @Test
    fun launches_showsDeclutteredControlsWhilePaused() {
        val scenario = launch()
        Thread.sleep(2500) // let ExoPlayer reach READY

        assertEquals(Lifecycle.State.RESUMED, scenario.state)

        onView(withId(R.id.progressLine)).check(matches(isDisplayed()))
        onView(withId(R.id.titleText)).check(matches(withText("test_clip")))
        onView(withId(R.id.poseToggleButton)).check(matches(isDisplayed()))
        onView(withId(R.id.drawingToggleButton)).check(matches(isDisplayed()))
        onView(withId(R.id.playPauseButton)).check(matches(isDisplayed()))
        onView(withId(R.id.seekBar)).check(matches(isDisplayed()))
        // Paused on entry, so the frame badge is visible and the draw rail is not
        onView(withId(R.id.frameBadge)).check(matches(isDisplayed()))
        onView(withId(R.id.drawingToolbar)).check(matches(withEffectiveVisibility(Visibility.GONE)))

        scenario.close()
    }

    @Test
    fun drawMode_showsRailAndDoneInsteadOfPlay() {
        val scenario = launch()
        Thread.sleep(2500)

        scenario.onActivity { activity ->
            activity.findViewById<android.view.View>(R.id.drawingToggleButton).performClick()
        }
        Thread.sleep(500)

        onView(withId(R.id.drawingToolbar)).check(matches(isDisplayed()))
        onView(withId(R.id.doneDrawingButton)).check(matches(isDisplayed()))
        onView(withId(R.id.playPauseButton)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        onView(withId(R.id.titleText)).check(matches(withEffectiveVisibility(Visibility.GONE)))

        scenario.onActivity { activity ->
            activity.findViewById<android.view.View>(R.id.doneDrawingButton).performClick()
        }
        Thread.sleep(500)
        onView(withId(R.id.drawingToolbar)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        onView(withId(R.id.playPauseButton)).check(matches(isDisplayed()))

        scenario.close()
    }
}
