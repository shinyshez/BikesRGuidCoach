package com.mtbanalyzer

import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Imports the bundled clip into MediaStore the way the gallery does and checks it lands in
 * Movies/MTBAnalyzer under an MTB_ name that the gallery's query will match.
 */
@RunWith(AndroidJUnit4::class)
class VideoImporterInstrumentedTest {

    @Test
    fun import_copiesClipIntoMediaStoreWithGalleryName() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val source = File(appContext.cacheDir, "import_source.mp4")
        testContext.resources.openRawResource(com.mtbanalyzer.test.R.raw.test_clip).use { input ->
            source.outputStream().use { input.copyTo(it) }
        }

        val imported = VideoImporter(appContext).import(Uri.fromFile(source))
        assertNotNull("import should return a MediaStore uri", imported)

        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        appContext.contentResolver.query(imported!!, projection, null, null, null).use { cursor ->
            assertNotNull(cursor)
            assertTrue(cursor!!.moveToFirst())
            assertEquals("MTB_import_source.mp4", cursor.getString(0))
            assertEquals(source.length(), cursor.getLong(1))
            assertTrue(cursor.getString(2).startsWith("Movies/MTBAnalyzer"))
        }

        // Owned by this install, so deleting needs no consent
        assertEquals(1, appContext.contentResolver.delete(imported, null, null))
    }
}
