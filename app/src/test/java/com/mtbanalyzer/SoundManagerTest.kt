package com.mtbanalyzer

import org.junit.Assert.assertEquals
import org.junit.Test

class SoundManagerTest {

    @Test
    fun startSound_isTheBeepWhenNoCalloutIsWanted() {
        assertEquals(
            SoundManager.StartSound.Beep,
            SoundManager.resolveStartSound(soundFeedbackEnabled = true, calloutEnabled = false, calloutSample = "file://dropping.m4a")
        )
    }

    @Test
    fun startSound_isSilentWithNothingTurnedOn() {
        assertEquals(
            SoundManager.StartSound.None,
            SoundManager.resolveStartSound(soundFeedbackEnabled = false, calloutEnabled = false, calloutSample = "file://dropping.m4a")
        )
    }

    @Test
    fun startSound_calloutReplacesTheBeep() {
        val sample = "content://audio/dropping"
        assertEquals(
            SoundManager.StartSound.Callout(sample),
            SoundManager.resolveStartSound(soundFeedbackEnabled = true, calloutEnabled = true, calloutSample = sample)
        )
        // and plays even when the recording beeps are turned off
        assertEquals(
            SoundManager.StartSound.Callout(sample),
            SoundManager.resolveStartSound(soundFeedbackEnabled = false, calloutEnabled = true, calloutSample = sample)
        )
    }

    @Test
    fun startSound_fallsBackToTheBeepWithNoSample() {
        assertEquals(
            SoundManager.StartSound.Beep,
            SoundManager.resolveStartSound(soundFeedbackEnabled = true, calloutEnabled = true, calloutSample = null)
        )
        assertEquals(
            SoundManager.StartSound.Beep,
            SoundManager.resolveStartSound(soundFeedbackEnabled = true, calloutEnabled = true, calloutSample = "  ")
        )
        assertEquals(
            SoundManager.StartSound.None,
            SoundManager.resolveStartSound(soundFeedbackEnabled = false, calloutEnabled = true, calloutSample = null)
        )
    }
}
