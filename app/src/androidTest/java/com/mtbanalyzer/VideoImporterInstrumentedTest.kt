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

    /**
     * The same clip twice (a re-import, or Save to this phone twice from a recorder) must
     * give a second clip, not fail: Android 10 refuses a second row for the same path, and
     * a previous install can leave that row behind where this one cannot see it.
     */
    @Test
    fun import_sameNameTwice_keepsBothUnderDistinctNames() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val source = File(appContext.cacheDir, "import_twice.mp4")
        testContext.resources.openRawResource(com.mtbanalyzer.test.R.raw.test_clip).use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val importer = VideoImporter(appContext)

        val first = importer.import(Uri.fromFile(source))
        val second = importer.import(Uri.fromFile(source))
        try {
            assertNotNull("first import", first)
            assertNotNull("second import of the same name", second)
            assertTrue("two distinct rows", first != second)

            val names = listOf(first!!, second!!).map { uri ->
                appContext.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    .use { cursor -> cursor!!.moveToFirst(); cursor.getString(0) }
            }
            assertEquals(2, names.toSet().size)
            names.forEach { assertTrue(it, it.startsWith("MTB_import_twice")) }
        } finally {
            listOfNotNull(first, second).forEach { appContext.contentResolver.delete(it, null, null) }
        }
    }
}
