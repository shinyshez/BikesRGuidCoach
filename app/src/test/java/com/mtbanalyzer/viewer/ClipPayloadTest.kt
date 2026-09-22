package com.mtbanalyzer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipPayloadTest {

    @Test
    fun `timestamped names are recordings`() {
        assertEquals(
            ClipNaming.KIND_RECORDING,
            ClipNaming.kindOf("MTB_2026-09-20-14-31-07-482.mp4")
        )
    }

    @Test
    fun `anything else under the prefix is an import`() {
        assertEquals(ClipNaming.KIND_IMPORT, ClipNaming.kindOf("MTB_import_source.mp4"))
        assertEquals(ClipNaming.KIND_IMPORT, ClipNaming.kindOf("MTB_rider_run.mp4"))
        // Close but not the recording pattern: milliseconds missing.
        assertEquals(ClipNaming.KIND_IMPORT, ClipNaming.kindOf("MTB_2026-09-20-14-31-07.mp4"))
    }

    @Test
    fun `json escapes what a filename can carry`() {
        // Left side spells out the literal characters the payload must contain.
        assertEquals("a\\\"b", Json.escape("a\"b"))
        assertEquals("a\\\\b", Json.escape("a\\b"))
        assertEquals("line\\nbreak", Json.escape("line\nbreak"))
        assertEquals("tab\\there", Json.escape("tab\there"))
        assertEquals("ctrl\\u0001char", Json.escape("ctrl\u0001char"))
        assertEquals("\"quoted\"", Json.string("quoted"))
    }

    @Test
    fun `clip serialises every field the viewer reads`() {
        val json = ClipDto(
            id = 42,
            name = "odd\"name.mp4",
            dateAdded = 1758378667,
            durationMs = 8012,
            sizeBytes = 9437184,
            kind = ClipNaming.KIND_IMPORT
        ).toJson()

        assertTrue(json, json.contains("\"id\":42"))
        // The quote in the filename has to come back escaped, not raw.
        assertTrue(json, json.contains("\"name\":\"odd\\\"name.mp4\""))
        assertTrue(json, json.contains("\"dateAdded\":1758378667"))
        assertTrue(json, json.contains("\"durationMs\":8012"))
        assertTrue(json, json.contains("\"sizeBytes\":9437184"))
        assertTrue(json, json.contains("\"kind\":\"import\""))
    }
}
