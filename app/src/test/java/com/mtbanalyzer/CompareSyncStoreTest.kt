package com.mtbanalyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CompareSyncStoreTest {

    @Test
    fun pairKey_isOrderSensitive() {
        assertNotEquals(CompareSyncStore.pairKey("12", "34"), CompareSyncStore.pairKey("34", "12"))
        assertEquals("12|34", CompareSyncStore.pairKey("12", "34"))
    }

    @Test
    fun formatOffsetFrames_roundsToWholeFramesWithSign() {
        val frame = 1000.0 / 30.0
        assertEquals("Δ 0f", CompareSyncStore.formatOffsetFrames(0, frame))
        assertEquals("Δ +1f", CompareSyncStore.formatOffsetFrames(33, frame))
        assertEquals("Δ +7f", CompareSyncStore.formatOffsetFrames(233, frame))
        assertEquals("Δ −3f", CompareSyncStore.formatOffsetFrames(-100, frame))
    }

    @Test
    fun formatOffsetFrames_handlesUnknownFrameRate() {
        assertEquals("Δ 0f", CompareSyncStore.formatOffsetFrames(500, 0.0))
    }

    @Test
    fun default_isLockedAtZero() {
        assertEquals(CompareSyncStore.SyncState(locked = true, offsetMs = 0), CompareSyncStore.DEFAULT)
    }
}
