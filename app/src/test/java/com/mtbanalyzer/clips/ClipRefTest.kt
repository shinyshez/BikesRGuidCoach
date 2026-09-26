package com.mtbanalyzer.clips

import com.mtbanalyzer.CompareSyncStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipRefTest {

    private val recorder = "7f3c2a9e-1b4d-4c8e-9a2f-5d6e7f8a9b0c"

    @Test
    fun `a local key is the bare id, as compare state was keyed before`() {
        // Compare used to key on the content Uri's last segment,
        // content://media/external/video/media/1337 -> "1337". Pairs saved that way must
        // still be found.
        assertEquals("1337", ClipRef.Local(1337).key)
        assertEquals(
            "12|34",
            CompareSyncStore.pairKey(ClipRef.Local(12).key, ClipRef.Local(34).key)
        )
    }

    @Test
    fun `a remote clip never shares a key with the local clip of the same id`() {
        // http://…/api/clips/1337 ends in the same segment as local clip 1337, which is the
        // collision the key exists to prevent.
        val remote = ClipRef.Remote(recorder, 1337)
        assertEquals("r:$recorder:1337", remote.key)
        assertNotEquals(ClipRef.Local(1337).key, remote.key)
        assertNotEquals(ClipRef.Remote("other-recorder", 1337).key, remote.key)
    }

    @Test
    fun `keys round-trip`() {
        listOf(
            ClipRef.Local(0),
            ClipRef.Local(1337),
            ClipRef.Local(Long.MAX_VALUE),
            ClipRef.Remote(recorder, 1337),
            ClipRef.Remote("x", 0)
        ).forEach { ref -> assertEquals(ref, ClipRef.fromKey(ref.key)) }
    }

    @Test
    fun `anything a key cannot be is refused`() {
        listOf(
            "", "abc", "-1", "12.5", "r:", "r:$recorder", "r:$recorder:", "r::12",
            "r:$recorder:abc", "r:$recorder:-1", "r:a:b:12", "content://media/external/video/media/12"
        ).forEach { key -> assertNull(key, ClipRef.fromKey(key)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a recorder id with a colon would make keys ambiguous`() {
        ClipRef.Remote("a:b", 1)
    }
}
