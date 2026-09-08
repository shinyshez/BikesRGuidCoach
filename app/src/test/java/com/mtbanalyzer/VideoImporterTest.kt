package com.mtbanalyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class VideoImporterTest {

    @Test
    fun importDisplayName_prefixesOriginalName() {
        assertEquals("MTB_holiday_ride.mp4", VideoImporter.importDisplayName("holiday_ride.mp4"))
    }

    @Test
    fun importDisplayName_doesNotDoublePrefix() {
        assertEquals("MTB_2026-09-08_test.mp4", VideoImporter.importDisplayName("MTB_2026-09-08_test.mp4"))
    }

    @Test
    fun importDisplayName_addsExtensionAndSanitises() {
        assertEquals("MTB_clip.mp4", VideoImporter.importDisplayName("clip"))
        assertEquals("MTB_a_b_c.mov", VideoImporter.importDisplayName("a/b:c.mov"))
    }

    @Test
    fun importDisplayName_fallsBackToTimestamp() {
        val name = VideoImporter.importDisplayName(null, Date(0))
        assertTrue(name, name.startsWith("MTB_import_") && name.endsWith(".mp4"))
        assertEquals(name, VideoImporter.importDisplayName("   ", Date(0)))
        // The Photo Picker only exposes its numeric id as the display name
        assertEquals(name, VideoImporter.importDisplayName("1000000038", Date(0)))
        assertEquals(name, VideoImporter.importDisplayName("1000000038.mp4", Date(0)))
    }
}
