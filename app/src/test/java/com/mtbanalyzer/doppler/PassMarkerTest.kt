package com.mtbanalyzer.doppler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassMarkerTest {

    private val s = 1_000_000_000L
    private val marks = mutableListOf<Pair<String, Int>>()
    private val marker = PassMarker({ key, ms -> marks += key to ms })

    @Test
    fun passDuringRecording_isMarkedWhenTheClipIsSaved() {
        marker.onPass(crossingNanos = 103 * s + 250_000_000, nowNanos = 103 * s + 400_000_000)
        assertTrue(marks.isEmpty())

        marker.onClip("42", startNanos = 100 * s, endNanos = 108 * s, nowNanos = 108 * s + 300_000_000)
        assertEquals(listOf("42" to 3250), marks)
    }

    @Test
    fun passConfirmedJustAfterTheClipStopped_isStillMarked() {
        marker.onClip("42", startNanos = 100 * s, endNanos = 108 * s, nowNanos = 108 * s)
        marker.onPass(crossingNanos = 107 * s + 900_000_000, nowNanos = 108 * s + 200_000_000)
        assertEquals(listOf("42" to 7900), marks)
    }

    @Test
    fun passesOutsideEveryClip_markNothing() {
        marker.onPass(crossingNanos = 99 * s, nowNanos = 99 * s)
        marker.onClip("42", startNanos = 100 * s, endNanos = 108 * s, nowNanos = 108 * s)
        marker.onPass(crossingNanos = 109 * s, nowNanos = 109 * s)
        assertTrue(marks.isEmpty())
    }

    @Test
    fun aPassLongAfterTheClip_isNotAddedToIt() {
        marker.onClip("42", startNanos = 100 * s, endNanos = 108 * s, nowNanos = 108 * s)
        // Inside the window on paper, but reported far too late to belong to it.
        marker.onPass(crossingNanos = 107 * s, nowNanos = 120 * s)
        assertTrue(marks.isEmpty())
    }

    @Test
    fun oldPasses_areForgotten() {
        marker.onPass(crossingNanos = 10 * s, nowNanos = 10 * s)
        marker.onPass(crossingNanos = 200 * s, nowNanos = 200 * s)
        // A clip spanning both: only the recent pass is still remembered.
        marker.onClip("7", startNanos = 5 * s, endNanos = 205 * s, nowNanos = 205 * s)
        assertEquals(listOf("7" to 195_000), marks)
    }

    @Test
    fun twoPassesInOneClip_areBothMarked() {
        marker.onPass(crossingNanos = 101 * s, nowNanos = 101 * s)
        marker.onPass(crossingNanos = 105 * s, nowNanos = 105 * s)
        marker.onClip("42", startNanos = 100 * s, endNanos = 108 * s, nowNanos = 108 * s)
        assertEquals(listOf("42" to 1000, "42" to 5000), marks)
    }

    @Test
    fun store_encodingRoundTripsAndIgnoresJunk() {
        assertEquals("120,3250", PassMarkStore.encode(listOf(120, 3250)))
        assertEquals(listOf(120, 3250), PassMarkStore.decode("3250,120"))
        assertEquals(listOf(5), PassMarkStore.decode("x,-3,5,"))
        assertEquals(emptyList<Int>(), PassMarkStore.decode(null))
    }

    @Test
    fun sampleClock_placesASampleOnTheBootClock() {
        val clock = SampleClock(48_000)
        // Sample 96 000 (2.0 s of audio) was captured at boot time 50 s.
        clock.anchor(96_000, 50 * s)
        assertEquals(50 * s + 500_000_000, clock.toElapsedNanos(2.5))
        assertEquals(49 * s, clock.toElapsedNanos(1.0))
    }
}
