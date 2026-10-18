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
    fun gateOffset_isClip2PassMinusClip1Pass() {
        assertEquals(1200, CompareSyncStore.gateOffsetMs(listOf(3000), listOf(4200)))
        assertEquals(-800, CompareSyncStore.gateOffsetMs(listOf(5000, 7000), listOf(4200)))
        assertEquals(null, CompareSyncStore.gateOffsetMs(emptyList(), listOf(4200)))
        assertEquals(null, CompareSyncStore.gateOffsetMs(listOf(3000), emptyList()))
    }

    @Test
    fun default_isLockedAtZero() {
        assertEquals(CompareSyncStore.SyncState(locked = true, offsetMs = 0), CompareSyncStore.DEFAULT)
    }
}
