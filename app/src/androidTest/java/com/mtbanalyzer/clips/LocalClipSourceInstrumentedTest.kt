package com.mtbanalyzer.clips

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtbanalyzer.VideoImporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The one MediaStore query behind the gallery, the capture strip and the viewer server,
 * against a real imported clip.
 */
@RunWith(AndroidJUnit4::class)
class LocalClipSourceInstrumentedTest {

    private lateinit var context: Context
    private lateinit var source: LocalClipSource
    private var clipUri: Uri? = null
    private var clipId = -1L

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        source = LocalClipSource(context)

        val file = File(context.cacheDir, "local_clip_source.mp4")
        InstrumentationRegistry.getInstrumentation().context.resources
            .openRawResource(com.mtbanalyzer.test.R.raw.test_clip)
            .use { input -> file.outputStream().use { input.copyTo(it) } }
        clipUri = VideoImporter(context).import(Uri.fromFile(file))
        assertNotNull("test clip should import", clipUri)
        clipId = ContentUris.parseId(clipUri!!)
    }

    @After
    fun tearDown() {
        clipUri?.let { context.contentResolver.delete(it, null, null) }
    }

    @Test
    fun list_findsTheClipForEveryCaller() {
        val all = source.list()
        val finished = source.list(finishedOnly = true)

        val clip = all.single { it.id == clipId }
        assertTrue(clip.name, clip.name.startsWith("MTB_"))
        assertEquals(ClipNaming.KIND_IMPORT, clip.kind)
        assertTrue(clip.isImport)
        assertEquals(ClipRef.Local(clipId), clip.localRef)
        assertEquals(clip, finished.single { it.id == clipId })
        assertEquals(clipUri, LocalClipSource.uriFor(clipId))
    }

    @Test
    fun list_isNewestFirst() {
        val dates = source.list().map { it.dateAdded }
        assertEquals(dates.sortedDescending(), dates)
    }

    @Test
    fun list_sinceIsExclusive() {
        val clip = source.list().single { it.id == clipId }
        assertTrue(source.list(sinceEpochSeconds = clip.dateAdded - 1).any { it.id == clipId })
        assertTrue(source.list(sinceEpochSeconds = clip.dateAdded).none { it.id == clipId })
    }

    @Test
    fun find_returnsTheListedClipAndNothingElse() {
        assertEquals(source.list().single { it.id == clipId }, source.find(clipId))
        assertNull(source.find(Long.MAX_VALUE))
    }
}
