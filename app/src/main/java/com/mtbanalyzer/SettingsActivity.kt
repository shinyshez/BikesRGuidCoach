package com.mtbanalyzer

import android.content.ContentResolver
import android.content.ContentUris
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"
        
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            
            // Set up preference listeners
            findPreference<SeekBarPreference>("recording_duration")?.setOnPreferenceChangeListener { _, newValue ->
                val duration = newValue as Int
                Toast.makeText(context, "Recording duration: ${duration}s", Toast.LENGTH_SHORT).show()
                true
            }
            
            findPreference<SeekBarPreference>("detection_sensitivity")?.setOnPreferenceChangeListener { _, newValue ->
                val sensitivity = newValue as Int
                Toast.makeText(context, "Detection sensitivity: ${sensitivity}%", Toast.LENGTH_SHORT).show()
                true
            }
            
            findPreference<SwitchPreferenceCompat>("haptic_feedback")?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                Toast.makeText(context, "Haptic feedback: ${if (enabled) "On" else "Off"}", Toast.LENGTH_SHORT).show()
                true
            }
            
            findPreference<androidx.preference.Preference>("clear_cache")?.setOnPreferenceClickListener {
                showClearVideosDialog()
                true
            }
        }
        
        private fun showClearVideosDialog() {
            AlertDialog.Builder(requireContext())
                .setTitle("Clear All Videos")
                .setMessage("Are you sure you want to delete all recorded MTB videos? This action cannot be undone.")
                .setPositiveButton("Delete All") { _, _ ->
                    clearAllVideos()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        
        private fun clearAllVideos() {
            try {
                val contentResolver = requireContext().contentResolver
                val projection = arrayOf(MediaStore.Video.Media._ID)
                val selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
                val selectionArgs = arrayOf("MTB_%")
                
                val cursor = contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )
                
                var deletedCount = 0
                cursor?.use {
                    val idColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    while (it.moveToNext()) {
                        val id = it.getLong(idColumn)
                        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        val deleted = contentResolver.delete(uri, null, null)
                        if (deleted > 0) {
                            deletedCount++
                        }
                    }
                }
                
                Toast.makeText(
                    requireContext(), 
                    if (deletedCount > 0) "Deleted $deletedCount videos" else "No videos to delete", 
                    Toast.LENGTH_LONG
                ).show()
                
            } catch (e: SecurityException) {
                Log.e("SettingsFragment", "Permission denied to delete videos", e)
                Toast.makeText(requireContext(), "Permission needed to delete videos", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("SettingsFragment", "Error deleting videos", e)
                Toast.makeText(requireContext(), "Error deleting videos: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}